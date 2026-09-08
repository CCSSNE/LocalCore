#include "localcore_core_api.h"

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

constexpr const char * TAG = "LocalCoreLoader";

using abi_version_fn = uint32_t (*)();
using version_fn = const char * (*)();
using create_fn = void * (*)(char **);
using destroy_fn = void (*)(void *);
using load_model_fn = int (*)(void *, const char *, char **, char **);
using unload_model_fn = int (*)(void *, char **);
using infer_fn = int (*)(void *, const char *, localcore_token_callback, void *, char **, char **);
using infer2_fn = int (*)(void *, const char *, localcore_token_callback, void *,
        localcore_progress_callback, void *, char **, char **);
using infer3_fn = int (*)(void *, const char *, localcore_token_callback, void *,
        localcore_progress_callback2, void *, char **, char **);
using cancel_fn = void (*)(void *);
using free_string_fn = void (*)(char *);

template <typename T>
T symbol(void * library, const char * name) {
    dlerror();
    auto value = reinterpret_cast<T>(dlsym(library, name));
    const char * error = dlerror();
    if (error != nullptr || value == nullptr) {
        throw std::runtime_error(std::string("核心缺少 ABI 符号 ") + name + ": "
                + (error == nullptr ? "unknown" : error));
    }
    return value;
}

// Additive symbols may be absent on older cores; callers fall back explicitly.
template <typename T>
T optional_symbol(void * library, const char * name) {
    dlerror();
    auto value = reinterpret_cast<T>(dlsym(library, name));
    dlerror();
    return value;
}

struct Core {
    void * library = nullptr;
    void * instance = nullptr;
    version_fn version = nullptr;
    destroy_fn destroy = nullptr;
    load_model_fn load_model = nullptr;
    unload_model_fn unload_model = nullptr;
    infer_fn infer = nullptr;
    infer2_fn infer2 = nullptr;
    infer3_fn infer3 = nullptr;
    cancel_fn cancel = nullptr;
    free_string_fn free_string = nullptr;
    std::mutex operation;

    ~Core() {
        if (instance != nullptr && destroy != nullptr) destroy(instance);
        if (library != nullptr) dlclose(library);
    }
};

std::string utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("字符串参数不能为 null");
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    if (bytes == nullptr || env->ExceptionCheck()) throw std::runtime_error("无法按 UTF-8 读取 Java 字符串");
    jsize size = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes);
    return result;
}

jstring java_string(JNIEnv * env, const char * value, size_t size) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(size));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(value));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(bytes);
    return result;
}

void throw_java(JNIEnv * env, const std::string & message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message.c_str());
    env->DeleteLocalRef(type);
}

Core * from(jlong handle) {
    auto * core = reinterpret_cast<Core *>(handle);
    if (core == nullptr) throw std::logic_error("动态核心尚未打开");
    return core;
}

struct CallbackState {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
};

int emit_token(const char * text, size_t size, void * opaque) {
    auto * state = static_cast<CallbackState *>(opaque);
    if (state == nullptr || state->callback == nullptr) return 1;
    jstring value = java_string(state->env, text, size);
    if (value == nullptr) return 0;
    jboolean keep = state->env->CallBooleanMethod(state->callback, state->method, value);
    state->env->DeleteLocalRef(value);
    return state->env->ExceptionCheck() ? 0 : keep == JNI_TRUE;
}

struct ProgressState {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
};

void emit_progress(const char * phase, int32_t done, int32_t total, void * opaque) {
    auto * state = static_cast<ProgressState *>(opaque);
    if (state == nullptr || state->callback == nullptr) return;
    jstring name = java_string(state->env, phase, strlen(phase));
    if (name == nullptr) return;
    state->env->CallVoidMethod(state->callback, state->method, name, done, total);
    state->env->DeleteLocalRef(name);
}

struct ProgressState2 {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
};

void emit_progress2(const char * phase, int32_t done, int32_t total, int64_t elapsed_ms, void * opaque) {
    auto * state = static_cast<ProgressState2 *>(opaque);
    if (state == nullptr || state->callback == nullptr) return;
    jstring name = java_string(state->env, phase, strlen(phase));
    if (name == nullptr) return;
    state->env->CallVoidMethod(state->callback, state->method, name, done, total, elapsed_ms);
    state->env->DeleteLocalRef(name);
}

std::string take(Core * core, char * value) {
    if (value == nullptr) return {};
    std::string result(value);
    core->free_string(value);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeOpenCore(JNIEnv * env, jclass, jstring path) {
    try {
        std::string core_path = utf8(env, path);
        std::unique_ptr<Core> core(new Core());
        core->library = dlopen(core_path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (core->library == nullptr) {
            throw std::runtime_error(std::string("dlopen 核心失败: ") + dlerror());
        }
        auto abi_version = symbol<abi_version_fn>(core->library, "localcore_core_abi_version");
        uint32_t actual = abi_version();
        if (actual != LOCALCORE_CORE_ABI_VERSION) {
            throw std::runtime_error("核心 ABI 不兼容: loader="
                    + std::to_string(LOCALCORE_CORE_ABI_VERSION) + ", core=" + std::to_string(actual));
        }
        core->version = symbol<version_fn>(core->library, "localcore_core_version");
        auto create = symbol<create_fn>(core->library, "localcore_core_create");
        core->destroy = symbol<destroy_fn>(core->library, "localcore_core_destroy");
        core->load_model = symbol<load_model_fn>(core->library, "localcore_core_load_model");
        core->unload_model = symbol<unload_model_fn>(core->library, "localcore_core_unload_model");
        core->infer = symbol<infer_fn>(core->library, "localcore_core_infer");
        core->infer2 = symbol<infer2_fn>(core->library, "localcore_core_infer2");
        core->infer3 = optional_symbol<infer3_fn>(core->library, "localcore_core_infer3");
        core->infer3 = symbol<infer3_fn>(core->library, "localcore_core_infer3");
        core->cancel = symbol<cancel_fn>(core->library, "localcore_core_cancel");
        core->free_string = symbol<free_string_fn>(core->library, "localcore_core_free_string");
        char * error = nullptr;
        core->instance = create(&error);
        if (core->instance == nullptr) {
            std::string detail = take(core.get(), error);
            throw std::runtime_error("核心初始化失败: " + detail);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "opened %s (%s)", core_path.c_str(), core->version());
        return reinterpret_cast<jlong>(core.release());
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", error.what());
        throw_java(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeLoadModel(
        JNIEnv * env, jclass, jlong handle, jstring request) {
    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        std::string json = utf8(env, request);
        char * result = nullptr;
        char * error = nullptr;
        if (core->load_model(core->instance, json.c_str(), &result, &error) != 0) {
            throw std::runtime_error(take(core, error));
        }
        std::string output = take(core, result);
        return java_string(env, output.data(), output.size());
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeEstimateMemory(
        JNIEnv * env, jclass, jlong handle, jstring request) {
    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        auto estimate = optional_symbol<load_model_fn>(core->library, "localcore_core_estimate_memory");
        if (estimate == nullptr) {
            throw std::runtime_error("当前核心不支持内存估算，请更新核心 SO（缺少 localcore_core_estimate_memory）");
        }
        std::string json = utf8(env, request);
        char * result = nullptr;
        char * error = nullptr;
        const int code = estimate(core->instance, json.c_str(), &result, &error);
        std::string output = take(core, result);
        std::string detail = take(core, error);
        if (code != 0) throw std::runtime_error(detail);
        return java_string(env, output.data(), output.size());
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeInfer(
        JNIEnv * env, jclass, jlong handle, jstring request, jobject callback) {
    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        std::string json = utf8(env, request);
        CallbackState state{env, callback, nullptr};
        if (callback != nullptr) {
            jclass type = env->GetObjectClass(callback);
            state.method = env->GetMethodID(type, "onToken", "(Ljava/lang/String;)Z");
            env->DeleteLocalRef(type);
            if (state.method == nullptr) throw std::runtime_error("TokenConsumer.onToken 方法不存在");
        }
        char * result = nullptr;
        char * error = nullptr;
        if (core->infer(core->instance, json.c_str(), callback == nullptr ? nullptr : emit_token,
                        &state, &result, &error) != 0) {
            if (env->ExceptionCheck()) return nullptr;
            throw std::runtime_error(take(core, error));
        }
        std::string output = take(core, result);
        return java_string(env, output.data(), output.size());
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeInfer2(
        JNIEnv * env, jclass, jlong handle, jstring request, jobject token_callback, jobject progress_callback) {
    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        std::string json = utf8(env, request);
        CallbackState token_state{env, token_callback, nullptr};
        if (token_callback != nullptr) {
            jclass type = env->GetObjectClass(token_callback);
            token_state.method = env->GetMethodID(type, "onToken", "(Ljava/lang/String;)Z");
            env->DeleteLocalRef(type);
            if (token_state.method == nullptr) throw std::runtime_error("TokenConsumer.onToken 方法不存在");
        }
        ProgressState progress_state{env, progress_callback, nullptr};
        if (progress_callback != nullptr) {
            jclass type = env->GetObjectClass(progress_callback);
            progress_state.method = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;II)V");
            env->DeleteLocalRef(type);
            if (progress_state.method == nullptr) throw std::runtime_error("ProgressConsumer.onProgress 方法不存在");
        }
        char * result = nullptr;
        char * error = nullptr;
        int code;
        code = core->infer2(core->instance, json.c_str(),
                    token_callback == nullptr ? nullptr : emit_token, &token_state,
                    progress_callback == nullptr ? nullptr : emit_progress, &progress_state,
                    &result, &error);
        if (code != 0) {
            if (env->ExceptionCheck()) return nullptr;
            throw std::runtime_error(take(core, error));
        }
        std::string output = take(core, result);
        return java_string(env, output.data(), output.size());
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeInfer3(
        JNIEnv * env, jclass, jlong handle, jstring request, jobject token_callback, jobject progress_callback) {
    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        std::string json = utf8(env, request);
        CallbackState token_state{env, token_callback, nullptr};
        if (token_callback != nullptr) {
            jclass type = env->GetObjectClass(token_callback);
            token_state.method = env->GetMethodID(type, "onToken", "(Ljava/lang/String;)Z");
            env->DeleteLocalRef(type);
            if (token_state.method == nullptr) throw std::runtime_error("TokenConsumer.onToken 方法不存在");
        }
        ProgressState2 progress_state{env, progress_callback, nullptr};
        if (progress_callback != nullptr) {
            jclass type = env->GetObjectClass(progress_callback);
            progress_state.method = env->GetMethodID(type, "onProgress", "(Ljava/lang/String;IIJ)V");
            env->DeleteLocalRef(type);
            if (progress_state.method == nullptr) throw std::runtime_error("ProgressConsumer.onProgress 方法不存在");
        }
        char * result = nullptr;
        char * error = nullptr;
        int code;
        if (core->infer3 != nullptr) {
            code = core->infer3(core->instance, json.c_str(),
                    token_callback == nullptr ? nullptr : emit_token, &token_state,
                    progress_callback == nullptr ? nullptr : emit_progress2, &progress_state,
                    &result, &error);
        } else {
            // Old core without v2 progress: plain infer, no progress events at all.
            code = core->infer(core->instance, json.c_str(),
                    token_callback == nullptr ? nullptr : emit_token, &token_state,
                    &result, &error);
        }
        if (code != 0) {
            if (env->ExceptionCheck()) return nullptr;
            throw std::runtime_error(take(core, error));
        }
        std::string output = take(core, result);
        return java_string(env, output.data(), output.size());
    } catch (const std::exception & error) {
        if (!env->ExceptionCheck()) throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeUnloadModel(JNIEnv * env, jclass, jlong handle) {    try {
        Core * core = from(handle);
        std::lock_guard<std::mutex> lock(core->operation);
        char * error = nullptr;
        if (core->unload_model(core->instance, &error) != 0) {
            throw std::runtime_error(take(core, error));
        }
    } catch (const std::exception & error) {
        throw_java(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeCancel(JNIEnv * env, jclass, jlong handle) {
    try {
        Core * core = from(handle);
        core->cancel(core->instance);
    } catch (const std::exception & error) {
        throw_java(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeRuntime_nativeCloseCore(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Core *>(handle);
}
