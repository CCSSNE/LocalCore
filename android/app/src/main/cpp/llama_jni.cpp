#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * TAG = "LocalCoreNative";

struct Api {
    decltype(llama_backend_init) * llama_backend_init;
    decltype(llama_backend_free) * llama_backend_free;
    decltype(llama_load_model_from_file) * llama_load_model_from_file;
    decltype(llama_free_model) * llama_free_model;
    decltype(llama_init_from_model) * llama_init_from_model;
    decltype(llama_free) * llama_free;
    decltype(llama_model_get_vocab) * llama_model_get_vocab;
    decltype(llama_model_meta_val_str) * llama_model_meta_val_str;
    decltype(llama_n_ctx) * llama_n_ctx;
    decltype(llama_n_batch) * llama_n_batch;
    decltype(llama_tokenize) * llama_tokenize;
    decltype(llama_token_to_piece) * llama_token_to_piece;
    decltype(llama_vocab_is_eog) * llama_vocab_is_eog;
    decltype(llama_decode) * llama_decode;
    decltype(llama_batch_get_one) * llama_batch_get_one;
    decltype(llama_get_memory) * llama_get_memory;
    decltype(llama_memory_clear) * llama_memory_clear;
    decltype(llama_sampler_chain_init) * llama_sampler_chain_init;
    decltype(llama_sampler_chain_default_params) * llama_sampler_chain_default_params;
    decltype(llama_sampler_chain_add) * llama_sampler_chain_add;
    decltype(llama_sampler_sample) * llama_sampler_sample;
    decltype(llama_sampler_accept) * llama_sampler_accept;
    decltype(llama_sampler_free) * llama_sampler_free;
    decltype(llama_sampler_init_greedy) * llama_sampler_init_greedy;
    decltype(llama_sampler_init_dist) * llama_sampler_init_dist;
    decltype(llama_sampler_init_top_k) * llama_sampler_init_top_k;
    decltype(llama_sampler_init_top_p) * llama_sampler_init_top_p;
    decltype(llama_sampler_init_temp) * llama_sampler_init_temp;
    decltype(llama_sampler_init_grammar) * llama_sampler_init_grammar;
};

struct Host {
    void * library = nullptr;
    std::unique_ptr<Api> api;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string error;
    std::atomic_bool cancelled{false};
    std::mutex mutex;
};

Host * host(jlong pointer) {
    return reinterpret_cast<Host *>(pointer);
}

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

int fail(Host * host, int code, const std::string & message) {
    host->error = message;
    __android_log_write(ANDROID_LOG_ERROR, TAG, message.c_str());
    return code;
}

std::vector<llama_token> tokenize(Host * host, const char * text, bool add_special) {
    size_t length = std::strlen(text);
    int32_t required = -host->api->llama_tokenize(host->vocab, text, static_cast<int32_t>(length),
            nullptr, 0, add_special, true);
    if (required <= 0) throw std::runtime_error("tokenizer 未返回有效 token 数量");
    std::vector<llama_token> tokens(static_cast<size_t>(required));
    int32_t actual = host->api->llama_tokenize(host->vocab, text, static_cast<int32_t>(length),
            tokens.data(), static_cast<int32_t>(tokens.size()), add_special, true);
    if (actual < 0) throw std::runtime_error("tokenizer 在已分配缓冲区上失败");
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

size_t utf8_complete_prefix(const std::string & value, size_t limit) {
    if (limit == 0) return 0;
    size_t index = 0;
    size_t complete = 0;
    while (index < limit) {
        unsigned char first = static_cast<unsigned char>(value[index]);
        size_t width = first < 0x80 ? 1 : (first & 0xe0) == 0xc0 ? 2
                : (first & 0xf0) == 0xe0 ? 3 : (first & 0xf8) == 0xf0 ? 4 : 0;
        if (width == 0) throw std::runtime_error("核心生成了非法 UTF-8 起始字节");
        if (index + width > limit) break;
        for (size_t offset = 1; offset < width; ++offset) {
            unsigned char continuation = static_cast<unsigned char>(value[index + offset]);
            if ((continuation & 0xc0) != 0x80) throw std::runtime_error("核心生成了非法 UTF-8 延续字节");
        }
        index += width;
        complete = index;
    }
    return complete;
}

bool emit_safe(std::string & pending, size_t safe_limit, JNIEnv * env, jobject callback,
        jmethodID method, bool * callback_failed) {
    size_t complete = utf8_complete_prefix(pending, safe_limit);
    if (complete == 0) return true;
    jbyteArray value = env->NewByteArray(static_cast<jsize>(complete));
    if (value == nullptr) { *callback_failed = true; return false; }
    env->SetByteArrayRegion(value, 0, static_cast<jsize>(complete),
            reinterpret_cast<const jbyte *>(pending.data()));
    jboolean keep_going = env->CallBooleanMethod(callback, method, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck() || !keep_going) { *callback_failed = true; return false; }
    pending.erase(0, complete);
    return true;
}

size_t protected_suffix(const std::string & value, const std::vector<std::string> & stops) {
    size_t keep = 0;
    for (const std::string & stop : stops) {
        size_t max_prefix = std::min(value.size(), stop.size() - 1);
        for (size_t length = max_prefix; length > 0; --length) {
            if (value.compare(value.size() - length, length, stop, 0, length) == 0) {
                keep = std::max(keep, length);
                break;
            }
        }
    }
    return keep;
}

size_t earliest_stop(const std::string & value, const std::vector<std::string> & stops) {
    size_t found = std::string::npos;
    for (const std::string & stop : stops) {
        size_t at = value.find(stop);
        if (at != std::string::npos) found = std::min(found, at);
    }
    return found;
}

struct JavaSink {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
    bool failed = false;
};

bool sink_emit(JavaSink & sink, std::string & pending, size_t safe_limit) {
    return emit_safe(pending, safe_limit, sink.env, sink.callback, sink.method, &sink.failed);
}

llama_sampler * create_sampler(Host * host, float temperature, float top_p, int32_t top_k,
        uint32_t seed, const std::string & grammar) {
    llama_sampler_chain_params chain_params = host->api->llama_sampler_chain_default_params();
    llama_sampler * chain = host->api->llama_sampler_chain_init(chain_params);
    if (chain == nullptr) throw std::runtime_error("无法创建采样器链");
    if (!grammar.empty()) {
        host->api->llama_sampler_chain_add(chain,
                host->api->llama_sampler_init_grammar(host->vocab, grammar.c_str(), nullptr));
    }
    if (temperature <= 0.0f) {
        host->api->llama_sampler_chain_add(chain, host->api->llama_sampler_init_greedy());
    } else {
        if (top_k > 0) host->api->llama_sampler_chain_add(chain, host->api->llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) {
            host->api->llama_sampler_chain_add(chain, host->api->llama_sampler_init_top_p(top_p, 1));
        }
        host->api->llama_sampler_chain_add(chain, host->api->llama_sampler_init_temp(temperature));
        host->api->llama_sampler_chain_add(chain, host->api->llama_sampler_init_dist(seed));
    }
    return chain;
}

int32_t generate_internal(Host * host, const std::string & prompt, int32_t max_tokens,
        float temperature, float top_p, int32_t top_k, uint32_t seed,
        const std::vector<std::string> & stops, const std::string & grammar,
        JNIEnv * env, jobject callback, jmethodID method) {
    std::lock_guard<std::mutex> lock(host->mutex);
    host->cancelled.store(false);
    host->error.clear();
    try {
        if (host->context == nullptr) return fail(host, -1, "尚未加载模型");
        std::vector<llama_token> prompt_tokens = tokenize(host, prompt.c_str(), true);
        uint32_t context_size = host->api->llama_n_ctx(host->context);
        if (prompt_tokens.size() + static_cast<size_t>(max_tokens) > context_size) {
            return fail(host, -2, "prompt token 与 max_tokens 之和超过模型上下文");
        }
        host->api->llama_memory_clear(host->api->llama_get_memory(host->context), true);
        uint32_t batch_size = host->api->llama_n_batch(host->context);
        for (size_t offset = 0; offset < prompt_tokens.size(); offset += batch_size) {
            size_t count = std::min(static_cast<size_t>(batch_size), prompt_tokens.size() - offset);
            llama_batch batch = host->api->llama_batch_get_one(prompt_tokens.data() + offset,
                    static_cast<int32_t>(count));
            int32_t decode = host->api->llama_decode(host->context, batch);
            if (decode != 0) return fail(host, -3, "prompt 解码失败: " + std::to_string(decode));
        }

        JavaSink sink{env, callback, method};
        std::unique_ptr<llama_sampler, void (*)(llama_sampler *)> sampler(
                create_sampler(host, temperature, top_p, top_k, seed, grammar),
                [host](llama_sampler * value) { host->api->llama_sampler_free(value); });
        int32_t generated = 0;
        std::string pending;
        for (; generated < max_tokens && !host->cancelled.load(); ++generated) {
            llama_token token = host->api->llama_sampler_sample(sampler.get(), host->context, -1);
            host->api->llama_sampler_accept(sampler.get(), token, true);
            if (host->api->llama_vocab_is_eog(host->vocab, token)) break;
            int32_t required = -host->api->llama_token_to_piece(host->vocab, token, nullptr, 0, 0, false);
            if (required == 0) continue;
            if (required <= 0) return fail(host, -4, "token 文本转换失败");
            std::vector<char> piece(static_cast<size_t>(required));
            int32_t written = host->api->llama_token_to_piece(host->vocab, token, piece.data(),
                    required, 0, false);
            if (written < 0) return fail(host, -5, "token 文本转换失败");
            pending.append(piece.data(), static_cast<size_t>(written));
            size_t stop = earliest_stop(pending, stops);
            if (stop != std::string::npos) {
                sink_emit(sink, pending, stop);
                return generated + 1;
            }
            size_t keep = protected_suffix(pending, stops);
            if (!sink_emit(sink, pending, pending.size() - keep)) return generated + 1;
            llama_batch batch = host->api->llama_batch_get_one(&token, 1);
            int32_t decode = host->api->llama_decode(host->context, batch);
            if (decode != 0) return fail(host, -6, "生成 token 解码失败: " + std::to_string(decode));
        }
        if (!pending.empty() && !sink_emit(sink, pending, pending.size())) return generated;
        if (!pending.empty()) return fail(host, -7, "生成在不完整 UTF-8 序列处结束");
        return generated;
    } catch (const std::exception & error) {
        return fail(host, -8, error.what());
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localcore_runtime_NativeBridge_open(JNIEnv * env, jclass, jstring path_value) {
    std::string path = to_utf8(env, path_value);
    auto result = std::make_unique<Host>();
    try {
        result->library = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (result->library == nullptr) {
            throw std::string("无法加载动态核心 ") + path + ": " + dlerror();
        }
        result->api = std::make_unique<Api>();
        #define LC_LOAD(field) { *(void **) (&result->api->field) = dlsym(result->library, #field); \
            if (result->api->field == nullptr) throw std::string("核心缺少符号: " #field); }
        LC_LOAD(llama_backend_init)
        LC_LOAD(llama_backend_free)
        LC_LOAD(llama_load_model_from_file)
        LC_LOAD(llama_free_model)
        LC_LOAD(llama_init_from_model)
        LC_LOAD(llama_free)
        LC_LOAD(llama_model_get_vocab)
        LC_LOAD(llama_model_meta_val_str)
        LC_LOAD(llama_n_ctx)
        LC_LOAD(llama_n_batch)
        LC_LOAD(llama_tokenize)
        LC_LOAD(llama_token_to_piece)
        LC_LOAD(llama_vocab_is_eog)
        LC_LOAD(llama_decode)
        LC_LOAD(llama_batch_get_one)
        LC_LOAD(llama_get_memory)
        LC_LOAD(llama_memory_clear)
        LC_LOAD(llama_sampler_chain_init)
        LC_LOAD(llama_sampler_chain_default_params)
        LC_LOAD(llama_sampler_chain_add)
        LC_LOAD(llama_sampler_sample)
        LC_LOAD(llama_sampler_accept)
        LC_LOAD(llama_sampler_free)
        LC_LOAD(llama_sampler_init_greedy)
        LC_LOAD(llama_sampler_init_dist)
        LC_LOAD(llama_sampler_init_top_k)
        LC_LOAD(llama_sampler_init_top_p)
        LC_LOAD(llama_sampler_init_temp)
        LC_LOAD(llama_sampler_init_grammar)
        #undef LC_LOAD
        result->api->llama_backend_init();
        return reinterpret_cast<jlong>(result.release());
    } catch (const std::string & message) {
        if (result->library != nullptr) dlclose(result->library);
        throw_java(env, "java/lang/IllegalStateException", message);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_close(JNIEnv * env, jclass, jlong pointer) {
    auto * value = host(pointer);
    if (value == nullptr) return;
    if (value->model != nullptr) {
        value->api->llama_free_model(value->model);
        value->model = nullptr;
        value->context = nullptr;
        value->vocab = nullptr;
    }
    value->api->llama_backend_free();
    dlclose(value->library);
    delete value;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeBridge_version(JNIEnv * env, jclass, jlong pointer) {
    Host * value = host(pointer);
    if (value == nullptr || value->model == nullptr) return env->NewStringUTF("");
    char buffer[256] = {0};
    if (value->api->llama_model_meta_val_str(value->model, "general.name", buffer, sizeof(buffer)) < 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(buffer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_loadModel(JNIEnv * env, jclass, jlong pointer,
        jstring path_value, jint context_size, jint batch_size, jint threads, jint gpu_layers) {
    Host * value = host(pointer);
    if (value == nullptr) { throw_java(env, "java/lang/IllegalStateException", "动态核心句柄无效"); return; }
    std::string path = to_utf8(env, path_value);
    std::lock_guard<std::mutex> lock(value->mutex);
    value->error.clear();
    try {
        llama_model_params model_params = value->api->llama_model_default_params();
        model_params.n_gpu_layers = gpu_layers;
        value->model = value->api->llama_load_model_from_file(path.c_str(), model_params);
        if (value->model == nullptr) throw std::runtime_error("模型加载失败");
        value->vocab = value->api->llama_model_get_vocab(value->model);
        llama_context_params context_params = value->api->llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = static_cast<uint32_t>(batch_size);
        context_params.n_ubatch = static_cast<uint32_t>(batch_size);
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        value->context = value->api->llama_init_from_model(value->model, context_params);
        if (value->context == nullptr) throw std::runtime_error("推理上下文创建失败");
    } catch (const std::exception & error) {
        if (value->context != nullptr) { value->api->llama_free(value->context); value->context = nullptr; }
        if (value->model != nullptr) { value->api->llama_free_model(value->model); value->model = nullptr; }
        value->vocab = nullptr;
        throw_java(env, "java/lang/IllegalStateException",
                std::string("加载模型失败: ") + error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_unloadModel(JNIEnv * env, jclass, jlong pointer) {
    Host * value = host(pointer);
    if (value == nullptr) return;
    std::lock_guard<std::mutex> lock(value->mutex);
    if (value->context != nullptr) { value->api->llama_free(value->context); value->context = nullptr; }
    if (value->model != nullptr) { value->api->llama_free_model(value->model); value->model = nullptr; }
    value->vocab = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcore_runtime_NativeBridge_applyChatTemplate(JNIEnv * env, jclass, jlong pointer,
        jobjectArray roles, jobjectArray contents, jstring template_value) {
    Host * value = host(pointer);
    if (value == nullptr || value->vocab == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "动态核心句柄无效");
        return nullptr;
    }
    jsize role_count = env->GetArrayLength(roles);
    jsize content_count = env->GetArrayLength(contents);
    if (role_count != content_count) {
        throw_java(env, "java/lang/IllegalArgumentException", "role 与 content 数量不一致");
        return nullptr;
    }
    std::vector<std::string> role_strings;
    std::vector<std::string> content_strings;
    std::vector<llama_chat_message> messages;
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
    int32_t required = value->api->llama_chat_apply_template(template_pointer,
            messages.data(), messages.size(), true, nullptr, 0);
    if (required < 0) {
        throw_java(env, "java/lang/IllegalStateException", "渲染聊天模板失败(code=" + std::to_string(required) + ")");
        return nullptr;
    }
    std::vector<char> output(static_cast<size_t>(required) + 1);
    int32_t written = value->api->llama_chat_apply_template(template_pointer,
            messages.data(), messages.size(), true, output.data(), static_cast<int32_t>(output.size()));
    if (written < 0 || written > required) {
        throw_java(env, "java/lang/IllegalStateException", "渲染聊天模板失败(code=" + std::to_string(written) + ")");
        return nullptr;
    }
    output[static_cast<size_t>(written)] = '\0';
    return env->NewStringUTF(output.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localcore_runtime_NativeBridge_tokenCount(JNIEnv * env, jclass, jlong pointer, jstring text_value) {
    Host * value = host(pointer);
    if (value == nullptr || value->vocab == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "动态核心句柄无效");
        return -1;
    }
    std::string text = to_utf8(env, text_value);
    try {
        return static_cast<jint>(tokenize(value, text.c_str(), false).size());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException",
                std::string("计算 token 失败: ") + error.what());
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localcore_runtime_NativeBridge_generate(JNIEnv * env, jclass, jlong pointer,
        jstring prompt_value, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
        jlong seed, jobjectArray stop_values, jstring grammar_value, jobject callback) {
    Host * value = host(pointer);
    if (value == nullptr) { throw_java(env, "java/lang/IllegalStateException", "动态核心句柄无效"); return -1; }
    std::string prompt = to_utf8(env, prompt_value);
    std::vector<std::string> stop_strings;
    jsize stop_count = env->GetArrayLength(stop_values);
    stop_strings.reserve(stop_count);
    for (jsize i = 0; i < stop_count; ++i) {
        auto stop = static_cast<jstring>(env->GetObjectArrayElement(stop_values, i));
        stop_strings.push_back(to_utf8(env, stop));
        env->DeleteLocalRef(stop);
    }
    std::string grammar = to_utf8(env, grammar_value);
    jclass callback_class = env->GetObjectClass(callback);
    jmethodID callback_method = env->GetMethodID(callback_class, "onToken", "([B)Z");
    env->DeleteLocalRef(callback_class);
    if (callback_method == nullptr) return -1;
    uint32_t effective_seed = seed < 0 ? UINT32_MAX : static_cast<uint32_t>(seed);
    int32_t result = generate_internal(value, prompt, max_tokens, temperature, top_p, top_k,
            effective_seed, stop_strings, grammar, env, callback, callback_method);
    if (result < 0 && !env->ExceptionCheck()) {
        std::string detail = value->error.empty() ? "未知错误" : value->error;
        throw_java(env, "java/lang/IllegalStateException", "推理失败(code=" + std::to_string(result) + "): " + detail);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcore_runtime_NativeBridge_cancel(JNIEnv * env, jclass, jlong pointer) {
    Host * value = host(pointer);
    if (value != nullptr) value->cancelled.store(true);
}
