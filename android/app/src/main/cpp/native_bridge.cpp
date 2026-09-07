#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "localcore_runtime_api.h"

namespace {

constexpr const char * TAG = "LocalCoreNative";

struct HostRuntime {
    void * library = nullptr;
    const lc_runtime_api_v1 * api = nullptr;
    void * runtime = nullptr;
};

void throw_java(JNIEnv * env, const char * type, const std::string & message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

std::string to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

HostRuntime * host(jlong pointer) {
    return reinterpret_cast<HostRuntime *>(pointer);
}

bool require_host(JNIEnv * env, HostRuntime * value) {
    if (value != nullptr && value->api != nullptr && value->runtime != nullptr) return true;
    throw_java(env, "java/lang/IllegalStateException", "动态核心句柄无效");
    return false;
}

std::string runtime_error(HostRuntime * value, const std::string & operation, int32_t code) {
    const char * detail = value->api->last_error(value->runtime);
    return operation + "失败(code=" + std::to_string(code) + "): "
            + (detail == nullptr ? "核心未提供错误信息" : detail);
}

void log_callback(int level, const char * message, void *) {
    int priority = level >= 4 ? ANDROID_LOG_ERROR : level >= 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(priority, TAG, message == nullptr ? "" : message);
}

struct JavaCallback {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
};

bool token_callback(const char * text, size_t length, void * user_data) {
    auto * target = static_cast<JavaCallback *>(user_data);
    jbyteArray value = target->env->NewByteArray(static_cast<jsize>(length));
    if (value == nullptr) return false;
    target->env->SetByteArrayRegion(value, 0, static_cast<jsize>(length),
            reinterpret_cast<const jbyte *>(text));
    jboolean keep_going = target->env->CallBooleanMethod(target->callback, target->method, value);
    target->env->DeleteLocalRef(value);
    return !target->env->ExceptionCheck() && keep_going == JNI_TRUE;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localcore_runtime_NativeBridge_open(JNIEnv * env, jclass, jstring path_value) {
    std::string path = to_utf8(env, path_value);
    void * library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "无法加载动态核心 " + path + ": " + dlerror());
        return 0;
    }
    dlerror();
    auto get_api = reinterpret_cast<lc_get_runtime_api_v1_fn>(dlsym(library, "localcore_runtime_api_v1"));
    const char * symbol_error = dlerror();
    if (symbol_error != nullptr || get_api == nullptr) {
        std::string message = "动态核心缺少 localcore_runtime_api_v1: ";
        message += symbol_error == nullptr ? "未知 dlsym 错误" : symbol_error;
        dlclose(library);
        throw_java(env, "java/lang/IllegalStateException", message);
        return 0;
    }
    const lc_runtime_api_v1 * api = get_api();
    if (api == nullptr || api->abi_version != LOCALCORE_RUNTIME_ABI_VERSION
            || api->struct_size < sizeof(lc_runtime_api_v1)) {
        std::string message = "动态核心 ABI 不兼容，宿主要求 "
                + std::to_string(LOCALCORE_RUNTIME_ABI_VERSION);
        dlclose(library);
        throw_java(env, "java/lang/IllegalStateException", message);
        return 0;
    }
    void * runtime = api->create(log_callback, nullptr);
    if (runtime == nullptr) {
        dlclose(library);
        throw_java(env, "java/lang/IllegalStateException", "动态核心初始化失败");
        return 0;
    }
    auto result = std::make_unique<HostRuntime>();
    result->library = library;
    result->api = api;
    result->runtime = runtime;
    return reinterpret_cast<jlong>(result.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_close(JNIEnv * env, jclass, jlong pointer) {
    std::unique_ptr<HostRuntime> value(host(pointer));
    if (!require_host(env, value.get())) return;
    value->api->destroy(value->runtime);
    value->runtime = nullptr;
    if (dlclose(value->library) != 0) {
        throw_java(env, "java/lang/IllegalStateException", std::string("卸载动态核心失败: ") + dlerror());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeBridge_version(JNIEnv * env, jclass, jlong pointer) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return nullptr;
    const char * version = value->api->runtime_version();
    return env->NewStringUTF(version == nullptr ? "" : version);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_loadModel(JNIEnv * env, jclass, jlong pointer,
        jstring path_value, jint context_size, jint batch_size, jint threads, jint gpu_layers) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return;
    std::string path = to_utf8(env, path_value);
    lc_load_params params{};
    params.struct_size = sizeof(params);
    params.context_size = static_cast<uint32_t>(context_size);
    params.batch_size = static_cast<uint32_t>(batch_size);
    params.threads = threads;
    params.gpu_layers = gpu_layers;
    int32_t code = value->api->load_model(value->runtime, path.c_str(), &params);
    if (code != 0) throw_java(env, "java/lang/IllegalStateException", runtime_error(value, "加载模型", code));
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_unloadModel(JNIEnv * env, jclass, jlong pointer) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return;
    value->api->unload_model(value->runtime);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeBridge_applyChatTemplate(JNIEnv * env, jclass, jlong pointer,
        jobjectArray roles, jobjectArray contents, jstring template_value) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return nullptr;
    jsize role_count = env->GetArrayLength(roles);
    jsize content_count = env->GetArrayLength(contents);
    if (role_count != content_count) {
        throw_java(env, "java/lang/IllegalArgumentException", "role 与 content 数量不一致");
        return nullptr;
    }
    std::vector<std::string> role_strings;
    std::vector<std::string> content_strings;
    std::vector<lc_chat_message> messages;
    role_strings.reserve(role_count);
    content_strings.reserve(role_count);
    messages.reserve(role_count);
    for (jsize i = 0; i < role_count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_strings.push_back(to_utf8(env, role));
        content_strings.push_back(to_utf8(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    for (jsize i = 0; i < role_count; ++i) {
        messages.push_back({role_strings[i].c_str(), content_strings[i].c_str()});
    }
    std::string custom_template = to_utf8(env, template_value);
    const char * template_pointer = template_value == nullptr ? nullptr : custom_template.c_str();
    int32_t required = value->api->apply_chat_template(value->runtime, template_pointer,
            messages.data(), messages.size(), true, nullptr, 0);
    if (required < 0) {
        throw_java(env, "java/lang/IllegalStateException", runtime_error(value, "渲染 Jinja 模板", required));
        return nullptr;
    }
    std::vector<char> output(static_cast<size_t>(required) + 1);
    int32_t written = value->api->apply_chat_template(value->runtime, template_pointer,
            messages.data(), messages.size(), true, output.data(), static_cast<int32_t>(output.size()));
    if (written < 0 || written > static_cast<int32_t>(output.size())) {
        throw_java(env, "java/lang/IllegalStateException", runtime_error(value, "渲染 Jinja 模板", written));
        return nullptr;
    }
    output[static_cast<size_t>(written)] = '\0';
    return env->NewStringUTF(output.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localcore_runtime_NativeBridge_tokenCount(JNIEnv * env, jclass, jlong pointer, jstring text_value) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return -1;
    std::string text = to_utf8(env, text_value);
    int32_t result = value->api->token_count(value->runtime, text.c_str());
    if (result < 0) throw_java(env, "java/lang/IllegalStateException", runtime_error(value, "计算 token", result));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localcore_runtime_NativeBridge_generate(JNIEnv * env, jclass, jlong pointer,
        jstring prompt_value, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
        jlong seed, jobjectArray stop_values, jobject callback) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return -1;
    std::string prompt = to_utf8(env, prompt_value);
    std::vector<std::string> stop_strings;
    std::vector<const char *> stops;
    jsize stop_count = env->GetArrayLength(stop_values);
    stop_strings.reserve(stop_count);
    stops.reserve(stop_count);
    for (jsize i = 0; i < stop_count; ++i) {
        auto stop = static_cast<jstring>(env->GetObjectArrayElement(stop_values, i));
        stop_strings.push_back(to_utf8(env, stop));
        env->DeleteLocalRef(stop);
    }
    for (const std::string & stop : stop_strings) stops.push_back(stop.c_str());

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID callback_method = env->GetMethodID(callback_class, "onToken", "([B)Z");
    env->DeleteLocalRef(callback_class);
    if (callback_method == nullptr) return -1;
    JavaCallback java_callback{env, callback, callback_method};

    lc_generation_params params{};
    params.struct_size = sizeof(params);
    params.max_tokens = max_tokens;
    params.temperature = temperature;
    params.top_p = top_p;
    params.top_k = top_k;
    params.seed = seed < 0 ? UINT32_MAX : static_cast<uint32_t>(seed);
    params.stop = stops.data();
    params.stop_count = stops.size();
    int32_t result = value->api->generate(value->runtime, prompt.c_str(), &params,
            token_callback, &java_callback);
    if (result < 0 && !env->ExceptionCheck()) {
        throw_java(env, "java/lang/IllegalStateException", runtime_error(value, "推理", result));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_cancel(JNIEnv * env, jclass, jlong pointer) {
    HostRuntime * value = host(pointer);
    if (!require_host(env, value)) return;
    value->api->cancel(value->runtime);
}
