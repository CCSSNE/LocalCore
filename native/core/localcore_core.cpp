#include "localcore_core_api.h"
#include "prefill_progress.h"

#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifndef LOCALCORE_CORE_VERSION
#define LOCALCORE_CORE_VERSION "development"
#endif

#ifndef LOCALCORE_LLAMA_VERSION
#define LOCALCORE_LLAMA_VERSION "unknown"
#endif

namespace {

template <typename T, void (*Free)(T *)>
using owned = std::unique_ptr<T, decltype(Free)>;

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    common_chat_templates_ptr templates;
    std::atomic_bool cancelled{false};
    PrefillProgress progress{cancelled};
    std::mutex operation;
    int32_t batch_size = 512;

    Engine() {
        llama_backend_init();
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
        }, nullptr);
        mtmd_helper_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
        }, nullptr);
    }

    ~Engine() {
        unload();
        llama_backend_free();
    }

    void unload() {
        progress.stop();
        templates.reset();
        if (vision != nullptr) {
            mtmd_free(vision);
            vision = nullptr;
        }
        if (context != nullptr) {
            llama_free(context);
            context = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

char * copy_string(const std::string & value) {
    auto * result = static_cast<char *>(std::malloc(value.size() + 1));
    if (result == nullptr) return nullptr;
    std::memcpy(result, value.data(), value.size());
    result[value.size()] = '\0';
    return result;
}

void set_string(char ** target, const std::string & value) {
    if (target != nullptr) *target = copy_string(value);
}

Engine & engine(void * instance) {
    if (instance == nullptr) throw std::invalid_argument("核心实例为空");
    return *static_cast<Engine *>(instance);
}

const common_json & required(const common_json & object, const char * key) {
    if (!object.contains(key)) throw std::invalid_argument(std::string("缺少字段: ") + key);
    return object.at(key);
}

std::string string_value(const common_json & object, const char * key, const std::string & fallback = {}) {
    return object.contains(key) && !object.at(key).is_null()
            ? object.at(key).get<std::string>() : fallback;
}

int32_t int_value(const common_json & object, const char * key, int32_t fallback) {
    return object.contains(key) ? object.at(key).get<int32_t>() : fallback;
}

float float_value(const common_json & object, const char * key, float fallback) {
    return object.contains(key) ? object.at(key).get<float>() : fallback;
}

bool bool_value(const common_json & object, const char * key, bool fallback) {
    return object.contains(key) ? object.at(key).get<bool>() : fallback;
}

std::vector<std::string> string_array(const common_json & object, const char * key) {
    std::vector<std::string> result;
    if (!object.contains(key) || object.at(key).is_null()) return result;
    const common_json & values = object.at(key);
    if (values.is_string()) {
        result.push_back(values.get<std::string>());
        return result;
    }
    if (!values.is_array()) throw std::invalid_argument(std::string(key) + " 必须是字符串或字符串数组");
    for (size_t i = 0; i < values.size(); i++) result.push_back(values.at(i).get<std::string>());
    return result;
}

int evaluate_text(Engine & runtime, const std::string & prompt) {
    runtime.progress.preparing("context_prepare");
    const llama_vocab * vocab = llama_model_get_vocab(runtime.model);
    std::vector<llama_token> tokens = common_tokenize(vocab, prompt, true, true);
    if (tokens.empty()) throw std::runtime_error("提示词分词结果为空");
    runtime.progress.context.total = static_cast<int64_t>(tokens.size());
    runtime.progress.select_chunk(false, tokens.size());
    size_t offset = 0;
    while (offset < tokens.size()) {
        int32_t count = static_cast<int32_t>(std::min<size_t>(runtime.batch_size, tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(runtime.context, batch) != 0) throw std::runtime_error("llama_decode 提示词失败");
        offset += static_cast<size_t>(count);
        runtime.progress.finish_chunk();
    }
    return static_cast<int>(tokens.size());
}

int evaluate_media(Engine & runtime, const std::string & prompt, const std::vector<std::string> & paths) {
    if (runtime.vision == nullptr) throw std::runtime_error("请求包含图片，但当前模型没有加载 MMPROJ");
    runtime.progress.preparing("image_prepare");
    std::vector<mtmd_bitmap *> bitmaps;
    std::vector<mtmd_helper_video *> videos;
    mtmd_helper_init_opt options = mtmd_helper_init_opt_default();
    try {
        for (const std::string & path : paths) {
            mtmd_helper_bitmap_wrapper media = mtmd_helper_bitmap_init_from_file(
                    runtime.vision, path.c_str(), false, options);
            if (media.bitmap == nullptr) throw std::runtime_error("MTMD 无法解码图片: " + path);
            bitmaps.push_back(media.bitmap);
            if (media.video_ctx != nullptr) videos.push_back(media.video_ctx);
        }
        owned<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
        mtmd_input_text text{prompt.data(), prompt.size(), true, true};
        std::vector<const mtmd_bitmap *> pointers(bitmaps.begin(), bitmaps.end());
        int32_t tokenized = mtmd_tokenize(runtime.vision, chunks.get(), &text, pointers.data(), pointers.size());
        if (tokenized != 0) {
            throw std::runtime_error("MTMD 提示词与图片分词失败，错误码 " + std::to_string(tokenized));
        }
        // Same semantics as mtmd_helper_eval_chunks, unrolled for per-chunk progress.
        size_t chunk_count = mtmd_input_chunks_size(chunks.get());
        for (size_t i = 0; i < chunk_count; i++) {
            const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks.get(), i);
            if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                runtime.progress.context.total += mtmd_input_chunk_get_n_tokens(chunk);
            } else {
                runtime.progress.image.total += mtmd_input_chunk_get_n_tokens(chunk);
            }
        }
        runtime.progress.image_context.total = runtime.progress.image.total;
        llama_pos position = 0;
        for (size_t i = 0; i < chunk_count; i++) {
            const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks.get(), i);
            runtime.progress.select_chunk(mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT,
                    mtmd_input_chunk_get_n_tokens(chunk));
            bool chunk_logits_last = (i == chunk_count - 1);
            int32_t evaluated = mtmd_helper_eval_chunk_single(runtime.vision, runtime.context, chunk,
                    position, 0, runtime.batch_size, chunk_logits_last, &position);
            if (evaluated != 0) {
                throw std::runtime_error("MTMD 图片编码或 llama_decode 失败，错误码 " + std::to_string(evaluated));
            }
            runtime.progress.finish_chunk();
        }
        int count = static_cast<int>(mtmd_helper_get_n_tokens(chunks.get()));
        for (mtmd_bitmap * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
        for (mtmd_helper_video * video : videos) mtmd_helper_video_free(video);
        return count;
    } catch (...) {
        for (mtmd_bitmap * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
        for (mtmd_helper_video * video : videos) mtmd_helper_video_free(video);
        throw;
    }
}

common_chat_params format_chat(Engine & runtime, const common_json & request) {
    common_chat_templates_inputs inputs;
    inputs.messages = common_chat_msgs_parse_oaicompat(required(request, "messages"));
    if (request.contains("tools") && request.at("tools").is_array()) {
        inputs.tools = common_chat_tools_parse_oaicompat(request.at("tools"));
    }
    inputs.tool_choice = common_chat_tool_choice_parse_oaicompat(
            string_value(request, "tool_choice", "auto"));
    inputs.parallel_tool_calls = bool_value(request, "parallel_tool_calls", false);
    inputs.add_generation_prompt = bool_value(request, "add_generation_prompt", true);
    inputs.enable_thinking = bool_value(request, "enable_thinking", true);
    inputs.reasoning_format = common_reasoning_format_from_name(
            string_value(request, "reasoning_format", "none"));
    inputs.grammar = string_value(request, "grammar");
    if (request.contains("response_format")) {
        const common_json & response_format = request.at("response_format");
        std::string type = string_value(response_format, "type");
        if (type == "json_schema") {
            const common_json & wrapper = required(response_format, "json_schema");
            inputs.json_schema = required(wrapper, "schema").dump();
        } else if (type == "json_object") {
            inputs.json_schema = response_format.contains("schema")
                    ? response_format.at("schema").dump() : common_json::object().dump();
        } else if (!type.empty() && type != "text") {
            throw std::invalid_argument("不支持的 response_format.type: " + type);
        }
    }
    if (request.contains("chat_template_kwargs")) {
        for (const auto & item : request.at("chat_template_kwargs").items()) {
            inputs.chat_template_kwargs[item.key()] = item.value().dump();
        }
    }
    return common_chat_templates_apply(runtime.templates.get(), inputs);
}

common_params_sampling sampling_params(Engine & runtime, const common_json & request,
                                       const common_chat_params * chat) {
    common_params_sampling params;
    params.seed = static_cast<uint32_t>(int_value(request, "seed", LLAMA_DEFAULT_SEED));
    params.top_k = int_value(request, "top_k", 40);
    params.top_p = float_value(request, "top_p", 0.95f);
    params.min_p = float_value(request, "min_p", 0.05f);
    params.temp = float_value(request, "temperature", 0.7f);
    params.penalty_repeat = float_value(request, "repeat_penalty", 1.0f);
    params.penalty_freq = float_value(request, "frequency_penalty", 0.0f);
    params.penalty_present = float_value(request, "presence_penalty", 0.0f);
    if (chat != nullptr && !chat->grammar.empty()) {
        params.grammar = {COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat->grammar};
        params.grammar_lazy = chat->grammar_lazy;
        const llama_vocab * vocab = llama_model_get_vocab(runtime.model);
        for (const std::string & value : chat->preserved_tokens) {
            std::vector<llama_token> ids = common_tokenize(vocab, value, false, true);
            if (ids.size() == 1) params.preserved_tokens.insert(ids[0]);
        }
        for (common_grammar_trigger trigger : chat->grammar_triggers) {
            if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
                std::vector<llama_token> ids = common_tokenize(vocab, trigger.value, false, true);
                if (ids.size() == 1) {
                    trigger.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    trigger.token = ids[0];
                }
            }
            params.grammar_triggers.push_back(std::move(trigger));
        }
        params.generation_prompt = chat->generation_prompt;
    } else {
        std::string grammar = string_value(request, "grammar");
        if (!grammar.empty()) params.grammar = {COMMON_GRAMMAR_TYPE_USER, std::move(grammar)};
    }
    return params;
}

bool stopped(const std::string & text, const std::vector<std::string> & stops, size_t & stop_at) {
    for (const std::string & stop : stops) {
        if (stop.empty() || text.size() < stop.size()) continue;
        if (text.compare(text.size() - stop.size(), stop.size(), stop) == 0) {
            stop_at = text.size() - stop.size();
            return true;
        }
    }
    return false;
}

std::string generate(Engine & runtime, common_params_sampling & params,
                     int32_t max_tokens, const std::vector<std::string> & stops,
                     int & completion_tokens, localcore_token_callback callback,
                     void * user_data, bool & streamed) {
    owned<common_sampler, common_sampler_free> sampler(
            common_sampler_init(runtime.model, params), common_sampler_free);
    if (!sampler) throw std::runtime_error("采样器初始化失败");
    const llama_vocab * vocab = llama_model_get_vocab(runtime.model);
    std::string output;
    completion_tokens = 0;
    for (int32_t i = 0; i < max_tokens; i++) {
        if (runtime.cancelled.load(std::memory_order_relaxed)) break;
        llama_token token = common_sampler_sample(sampler.get(), runtime.context, -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::string piece = common_token_to_piece(vocab, token, true);
        output += piece;
        completion_tokens++;
        if (callback != nullptr && !piece.empty()) {
            streamed = true;
            if (callback(piece.data(), piece.size(), user_data) == 0) break;
        }
        size_t stop_at = 0;
        if (stopped(output, stops, stop_at)) {
            output.resize(stop_at);
            break;
        }
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(runtime.context, batch) != 0) {
            throw std::runtime_error("llama_decode 生成 token 失败");
        }
    }
    return output;
}

common_json make_result(int prompt_tokens, int completion_tokens, const std::string & text,
                        const common_chat_msg * message) {
    common_json result = common_json::object({
            {"promptTokens", prompt_tokens},
            {"completionTokens", completion_tokens},
            {"text", text},
            {"structured", message != nullptr},
    });
    result["message"] = message == nullptr ? common_json(nullptr) : message->to_json_oaicompat();
    return result;
}

} // namespace

extern "C" LOCALCORE_EXPORT uint32_t localcore_core_abi_version(void) {
    return LOCALCORE_CORE_ABI_VERSION;
}

extern "C" LOCALCORE_EXPORT const char * localcore_core_version(void) {
    return LOCALCORE_CORE_VERSION "/llama.cpp-" LOCALCORE_LLAMA_VERSION;
}

extern "C" LOCALCORE_EXPORT void * localcore_core_create(char ** error) {
    try {
        if (error != nullptr) *error = nullptr;
        return new Engine();
    } catch (const std::exception & failure) {
        set_string(error, failure.what());
        return nullptr;
    }
}

extern "C" LOCALCORE_EXPORT void localcore_core_destroy(void * instance) {
    delete static_cast<Engine *>(instance);
}

extern "C" LOCALCORE_EXPORT int localcore_core_load_model(
        void * instance, const char * request_json, char ** result_json, char ** error) {
    try {
        Engine & runtime = engine(instance);
        std::lock_guard<std::mutex> lock(runtime.operation);
        common_json request = common_json::parse(request_json == nullptr ? "" : request_json);
        runtime.unload();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = int_value(request, "gpuLayers", 0);
        std::string model_path = required(request, "modelPath").get<std::string>();
        runtime.model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (runtime.model == nullptr) throw std::runtime_error("llama.cpp 无法加载模型: " + model_path);
        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(int_value(request, "contextSize", 4096));
        runtime.batch_size = int_value(request, "batchSize", 512);
        context_params.n_batch = static_cast<uint32_t>(runtime.batch_size);
        context_params.n_ubatch = static_cast<uint32_t>(runtime.batch_size);
        context_params.n_threads = int_value(request, "threads", 4);
        context_params.n_threads_batch = context_params.n_threads;
        context_params.cb_eval_graph = PrefillProgress::Graph::begin;
        context_params.cb_eval = PrefillProgress::Graph::eval;
        context_params.cb_eval_user_data = &runtime.progress.llm;
        runtime.context = llama_init_from_model(runtime.model, context_params);
        if (runtime.context == nullptr) throw std::runtime_error("llama.cpp 无法创建推理上下文");
        std::string mmproj_path = string_value(request, "mmprojPath");
        if (!mmproj_path.empty()) {
            mtmd_context_params mtmd_params = mtmd_context_params_default();
            mtmd_params.use_gpu = false;
            mtmd_params.n_threads = context_params.n_threads;
            mtmd_params.warmup = false;
            mtmd_params.cb_eval_graph = PrefillProgress::Graph::begin;
            mtmd_params.cb_eval = PrefillProgress::Graph::eval;
            mtmd_params.cb_eval_user_data = &runtime.progress.vision;
            runtime.vision = mtmd_init_from_file(mmproj_path.c_str(), runtime.model, mtmd_params);
            if (runtime.vision == nullptr) throw std::runtime_error("MTMD 无法加载 MMPROJ: " + mmproj_path);
            if (!mtmd_support_vision(runtime.vision)) throw std::runtime_error("MMPROJ 不支持图片输入");
        }
        runtime.templates = common_chat_templates_init(
                runtime.model, string_value(request, "chatTemplate"));
        runtime.cancelled.store(false, std::memory_order_relaxed);
        common_json result = common_json::object({
                {"version", localcore_core_version()},
                {"abi", LOCALCORE_CORE_ABI_VERSION},
                {"vision", runtime.vision != nullptr},
        });
        set_string(result_json, result.dump());
        if (error != nullptr) *error = nullptr;
        return 0;
    } catch (const std::exception & failure) {
        try { engine(instance).unload(); } catch (...) {}
        set_string(error, failure.what());
        return 1;
    }
}

extern "C" LOCALCORE_EXPORT int localcore_core_unload_model(void * instance, char ** error) {
    try {
        Engine & runtime = engine(instance);
        std::lock_guard<std::mutex> lock(runtime.operation);
        runtime.unload();
        if (error != nullptr) *error = nullptr;
        return 0;
    } catch (const std::exception & failure) {
        set_string(error, failure.what());
        return 1;
    }
}

extern "C" LOCALCORE_EXPORT int localcore_core_infer(
        void * instance, const char * request_json, localcore_token_callback callback,
        void * user_data, char ** result_json, char ** error) {
    return localcore_core_infer2(instance, request_json, callback, user_data,
            nullptr, nullptr, result_json, error);
}

extern "C" LOCALCORE_EXPORT int localcore_core_infer2(
        void * instance, const char * request_json, localcore_token_callback token_callback,
        void * token_user_data, localcore_progress_callback progress_callback,
        void * progress_user_data, char ** result_json, char ** error) {
    try {
        Engine & runtime = engine(instance);
        std::lock_guard<std::mutex> lock(runtime.operation);
        if (runtime.model == nullptr || runtime.context == nullptr) throw std::runtime_error("尚未加载模型");
        common_json request = common_json::parse(request_json == nullptr ? "" : request_json);
        runtime.cancelled.store(false, std::memory_order_relaxed);
        llama_memory_clear(llama_get_memory(runtime.context), true);
        std::string kind = string_value(request, "type");
        common_chat_params chat;
        common_chat_params * chat_pointer = nullptr;
        std::string prompt;
        if (kind == "chat") {
            chat = format_chat(runtime, request);
            chat_pointer = &chat;
            prompt = chat.prompt;
        } else if (kind == "complete") {
            prompt = required(request, "prompt").get<std::string>();
        } else {
            throw std::invalid_argument("未知推理类型: " + kind);
        }
        std::vector<std::string> media_paths = string_array(request, "mediaPaths");
        runtime.progress.start(progress_callback, progress_user_data);
        struct ProgressScope {
            PrefillProgress & progress;
            ~ProgressScope() { progress.stop(); }
        } progress_scope{runtime.progress};
        int prompt_tokens = media_paths.empty()
                ? evaluate_text(runtime, prompt)
                : evaluate_media(runtime, prompt, media_paths);
        runtime.progress.verify_complete();
        runtime.progress.stop();
        common_params_sampling params = sampling_params(runtime, request, chat_pointer);
        int completion_tokens = 0;
        bool streamed = false;
        std::string text = generate(runtime, params,
                int_value(request, "max_tokens", 1024), string_array(request, "stop"), completion_tokens,
                token_callback, token_user_data, streamed);
        common_chat_msg message;
        common_chat_msg * message_pointer = nullptr;
        if (chat_pointer != nullptr) {
            common_chat_parser_params parser(*chat_pointer);
            if (!chat_pointer->parser.empty()) {
                parser.parser.load(chat_pointer->parser);
            }
            parser.reasoning_format = common_reasoning_format_from_name(
                    string_value(request, "reasoning_format", "none"));
            message = common_chat_parse(text, false, parser);
            message_pointer = &message;
        }
        const std::string & callback_text = message_pointer == nullptr ? text : message_pointer->content;
        // 已逐 token 推送过的不再补一次全文；老核心行为（单次全量回调）保持不变。
        if (!streamed && token_callback != nullptr && !callback_text.empty()
                && token_callback(callback_text.data(), callback_text.size(), token_user_data) == 0) {
            throw std::runtime_error("响应消费者拒绝生成文本");
        }
        set_string(result_json, make_result(prompt_tokens, completion_tokens, text, message_pointer).dump());
        if (error != nullptr) *error = nullptr;
        return 0;
    } catch (const std::exception & failure) {
        set_string(error, failure.what());
        return 1;
    }
}

extern "C" LOCALCORE_EXPORT void localcore_core_cancel(void * instance) {
    if (instance != nullptr) static_cast<Engine *>(instance)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" LOCALCORE_EXPORT void localcore_core_free_string(char * value) {
    std::free(value);
}
