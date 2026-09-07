#include "localcore_runtime_api.h"

#include "chat.h"
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct Runtime {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string error;
    lc_log_callback logger = nullptr;
    void * logger_data = nullptr;
    std::atomic_bool cancelled{false};
    std::mutex mutex;
};

void report(Runtime * runtime, int level, const std::string & message) {
    if (runtime->logger != nullptr) runtime->logger(level, message.c_str(), runtime->logger_data);
}

int fail(Runtime * runtime, int code, const std::string & message) {
    runtime->error = message;
    report(runtime, 4, message);
    return code;
}

void unload(Runtime * runtime) {
    if (runtime->context != nullptr) {
        llama_free(runtime->context);
        runtime->context = nullptr;
    }
    if (runtime->model != nullptr) {
        llama_model_free(runtime->model);
        runtime->model = nullptr;
    }
    runtime->vocab = nullptr;
}

std::vector<llama_token> tokenize(Runtime * runtime, const char * text) {
    size_t length = std::strlen(text);
    int32_t required = -llama_tokenize(runtime->vocab, text, static_cast<int32_t>(length),
            nullptr, 0, true, true);
    if (required <= 0) throw std::runtime_error("tokenizer 未返回有效 token 数量");
    std::vector<llama_token> tokens(static_cast<size_t>(required));
    int32_t actual = llama_tokenize(runtime->vocab, text, static_cast<int32_t>(length),
            tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
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

bool emit_safe(std::string & pending, size_t safe_limit, lc_token_callback callback, void * user_data) {
    size_t complete = utf8_complete_prefix(pending, safe_limit);
    if (complete == 0) return true;
    bool keep_going = callback(pending.data(), complete, user_data);
    pending.erase(0, complete);
    return keep_going;
}

size_t protected_suffix(const std::string & value, const lc_generation_params * params) {
    size_t keep = 0;
    for (size_t i = 0; i < params->stop_count; ++i) {
        const std::string stop(params->stop[i]);
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

size_t earliest_stop(const std::string & value, const lc_generation_params * params) {
    size_t result = std::string::npos;
    for (size_t i = 0; i < params->stop_count; ++i) {
        size_t found = value.find(params->stop[i]);
        if (found != std::string::npos) result = std::min(result, found);
    }
    return result;
}

const char * runtime_version() {
    return LOCALCORE_RUNTIME_VERSION;
}

void * create(lc_log_callback callback, void * user_data) {
    try {
        llama_backend_init();
        auto runtime = std::make_unique<Runtime>();
        runtime->logger = callback;
        runtime->logger_data = user_data;
        report(runtime.get(), 2, std::string("动态核心已初始化 ") + runtime_version());
        return runtime.release();
    } catch (...) {
        return nullptr;
    }
}

void destroy(void * opaque) {
    auto runtime = std::unique_ptr<Runtime>(static_cast<Runtime *>(opaque));
    if (runtime == nullptr) return;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    unload(runtime.get());
    llama_backend_free();
}

const char * last_error(void * opaque) {
    auto * runtime = static_cast<Runtime *>(opaque);
    return runtime == nullptr ? "运行时为空" : runtime->error.c_str();
}

int32_t load_model(void * opaque, const char * path, const lc_load_params * params) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime == nullptr || path == nullptr || params == nullptr
            || params->struct_size < sizeof(lc_load_params)) return -1;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    unload(runtime);
    runtime->error.clear();
    try {
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = params->gpu_layers;
        model_params.check_tensors = true;
        runtime->model = llama_model_load_from_file(path, model_params);
        if (runtime->model == nullptr) return fail(runtime, -2, "llama.cpp 无法加载或校验 GGUF 模型");
        if (llama_model_has_encoder(runtime->model)) {
            unload(runtime);
            return fail(runtime, -3, "当前核心仅支持 decoder-only GGUF 模型");
        }
        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = params->context_size;
        context_params.n_batch = params->batch_size;
        context_params.n_ubatch = params->batch_size;
        context_params.n_threads = params->threads;
        context_params.n_threads_batch = params->threads;
        runtime->context = llama_init_from_model(runtime->model, context_params);
        if (runtime->context == nullptr) {
            unload(runtime);
            return fail(runtime, -4, "llama.cpp 无法创建推理上下文");
        }
        runtime->vocab = llama_model_get_vocab(runtime->model);
        report(runtime, 2, std::string("模型已加载: ") + path);
        return 0;
    } catch (const std::exception & error) {
        unload(runtime);
        return fail(runtime, -5, error.what());
    }
}

void unload_model(void * opaque) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime == nullptr) return;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    unload(runtime);
}

int32_t apply_chat_template(void * opaque, const char * source, const lc_chat_message * messages,
        size_t message_count, bool add_assistant, char * output, int32_t output_size) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime == nullptr || runtime->model == nullptr || messages == nullptr) return -1;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    try {
        std::string override_template = source == nullptr ? "" : source;
        common_chat_templates_ptr templates = common_chat_templates_init(runtime->model, override_template);
        common_chat_templates_inputs inputs;
        inputs.add_generation_prompt = add_assistant;
        inputs.use_jinja = true;
        for (size_t i = 0; i < message_count; ++i) {
            if (messages[i].role == nullptr || messages[i].content == nullptr) {
                return fail(runtime, -2, "消息 role/content 不能为空");
            }
            common_chat_msg message;
            message.role = messages[i].role;
            message.content = messages[i].content;
            inputs.messages.push_back(std::move(message));
        }
        std::string prompt = common_chat_templates_apply(templates.get(), inputs).prompt;
        if (output == nullptr || output_size <= static_cast<int32_t>(prompt.size())) {
            return static_cast<int32_t>(prompt.size());
        }
        std::memcpy(output, prompt.data(), prompt.size());
        output[prompt.size()] = '\0';
        return static_cast<int32_t>(prompt.size());
    } catch (const std::exception & error) {
        return fail(runtime, -3, std::string("Jinja 模板错误: ") + error.what());
    }
}

int32_t token_count(void * opaque, const char * text) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime == nullptr || runtime->model == nullptr || text == nullptr) return -1;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    try {
        return static_cast<int32_t>(tokenize(runtime, text).size());
    } catch (const std::exception & error) {
        return fail(runtime, -2, error.what());
    }
}

int32_t generate(void * opaque, const char * prompt, const lc_generation_params * params,
        lc_token_callback callback, void * user_data) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime == nullptr || runtime->context == nullptr || prompt == nullptr || params == nullptr
            || params->struct_size < sizeof(lc_generation_params) || callback == nullptr) return -1;
    std::lock_guard<std::mutex> lock(runtime->mutex);
    runtime->cancelled.store(false);
    runtime->error.clear();
    try {
        std::vector<llama_token> prompt_tokens = tokenize(runtime, prompt);
        uint32_t context_size = llama_n_ctx(runtime->context);
        if (prompt_tokens.size() + static_cast<size_t>(params->max_tokens) > context_size) {
            return fail(runtime, -2, "prompt token 与 max_tokens 之和超过模型上下文");
        }
        llama_memory_clear(llama_get_memory(runtime->context), true);
        uint32_t batch_size = llama_n_batch(runtime->context);
        for (size_t offset = 0; offset < prompt_tokens.size(); offset += batch_size) {
            int32_t count = static_cast<int32_t>(std::min(static_cast<size_t>(batch_size),
                    prompt_tokens.size() - offset));
            llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, count);
            int32_t decode = llama_decode(runtime->context, batch);
            if (decode != 0) return fail(runtime, -3, "prompt 解码失败: " + std::to_string(decode));
        }

        llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(chain_params);
        if (params->temperature == 0.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        } else {
            if (params->top_k > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(params->top_k));
            llama_sampler_chain_add(sampler, llama_sampler_init_top_p(params->top_p, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(params->temperature));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(params->seed));
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler_guard(sampler, llama_sampler_free);

        int32_t generated = 0;
        std::string pending;
        for (; generated < params->max_tokens && !runtime->cancelled.load(); ++generated) {
            llama_token token = llama_sampler_sample(sampler, runtime->context, -1);
            if (llama_vocab_is_eog(runtime->vocab, token)) break;
            int32_t required = llama_token_to_piece(runtime->vocab, token, nullptr, 0, 0, false);
            if (required == 0) continue;
            if (required > 0) return fail(runtime, -4, "llama_token_to_piece 未按缓冲区查询协议返回长度");
            std::vector<char> piece(static_cast<size_t>(-required));
            int32_t written = llama_token_to_piece(runtime->vocab, token, piece.data(),
                    static_cast<int32_t>(piece.size()), 0, false);
            if (written < 0) return fail(runtime, -5, "token 文本转换失败");
            pending.append(piece.data(), static_cast<size_t>(written));

            size_t stop = earliest_stop(pending, params);
            if (stop != std::string::npos) {
                if (!emit_safe(pending, stop, callback, user_data)) return generated + 1;
                return generated + 1;
            }
            size_t keep = protected_suffix(pending, params);
            if (!emit_safe(pending, pending.size() - keep, callback, user_data)) return generated + 1;

            llama_batch batch = llama_batch_get_one(&token, 1);
            int32_t decode = llama_decode(runtime->context, batch);
            if (decode != 0) return fail(runtime, -6, "生成 token 解码失败: " + std::to_string(decode));
        }
        if (!pending.empty() && !emit_safe(pending, pending.size(), callback, user_data)) return generated;
        if (!pending.empty()) return fail(runtime, -7, "生成在不完整 UTF-8 序列处结束");
        return generated;
    } catch (const std::exception & error) {
        return fail(runtime, -8, error.what());
    }
}

void cancel(void * opaque) {
    auto * runtime = static_cast<Runtime *>(opaque);
    if (runtime != nullptr) runtime->cancelled.store(true);
}

const lc_runtime_api_v1 API = {
    sizeof(lc_runtime_api_v1),
    LOCALCORE_RUNTIME_ABI_VERSION,
    runtime_version,
    create,
    destroy,
    last_error,
    load_model,
    unload_model,
    apply_chat_template,
    token_count,
    generate,
    cancel,
};

} // namespace

extern "C" LOCALCORE_RUNTIME_EXPORT const lc_runtime_api_v1 * localcore_runtime_api_v1(void) {
    return &API;
}

