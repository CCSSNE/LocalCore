#include "localcore_core_api.h"
#include "prefill_progress.h"

#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "llama-ext.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"
#include "common/unicode.h"
#include <chrono>

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

// Loading and planning must use the same parameters; only allocation mode differs.
llama_context_params context_parameters(const common_json & request, PrefillProgress & progress) {
    llama_context_params params = llama_context_default_params();
    auto integer = [&](const char * key, int64_t minimum) {
        const auto & value = required(request, key);
        if (!value.is_number_integer()) throw std::invalid_argument(std::string(key) + " 必须是整数");
        const int64_t number = value.get<int64_t>();
        if (number < minimum || number > INT32_MAX) {
            throw std::invalid_argument(std::string(key) + " 超出当前核心整型参数范围: "
                    + std::to_string(minimum) + ".." + std::to_string(INT32_MAX));
        }
        return static_cast<int32_t>(number);
    };
    params.n_ctx = static_cast<uint32_t>(integer("contextSize", 0));
    params.n_batch = static_cast<uint32_t>(integer("batchSize", 1));
    params.n_ubatch = params.n_batch;
    params.n_threads = integer("threads", 1);
    params.n_threads_batch = params.n_threads;
    params.cb_eval_graph = PrefillProgress::Graph::begin;
    params.cb_eval = PrefillProgress::Graph::eval;
    params.cb_eval_user_data = &progress.llm;
    params.abort_callback = PrefillProgress::should_abort;
    params.abort_callback_data = &progress;
    return params;
}

mtmd_context_params vision_parameters(int threads, PrefillProgress & progress) {
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = threads;
    params.warmup = false;
    params.cb_eval_graph = PrefillProgress::Graph::begin;
    params.cb_eval = PrefillProgress::Graph::eval;
    params.cb_eval_user_data = &progress.vision;
    return params;
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
            runtime.progress.check_cancelled();
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

struct RequestCancelled : std::runtime_error {
    using std::runtime_error::runtime_error;
};

// One parser for partial events and the final message. No template parsing in the APK.
struct OutputStream {
    common_chat_parser_params parser;
    common_chat_msg message;
    std::vector<std::string> tool_ids;
    localcore_token_callback callback;
    void * user_data;
    bool chat;
    bool events;
    size_t consumed = 0;

    OutputStream(const common_chat_params * params, localcore_token_callback cb, void * data, bool json_events)
        : callback(cb), user_data(data), chat(params != nullptr), events(json_events) {
        if (params != nullptr) {
            parser = common_chat_parser_params(*params);
            if (!params->parser.empty()) parser.parser.load(params->parser);
        }
    }

    void send(const std::string & value) {
        if (callback != nullptr && !value.empty() && callback(value.data(), value.size(), user_data) == 0) {
            throw RequestCancelled("Response consumer disconnected or cancelled");
        }
    }

    void update(const std::string & text, bool partial) {
        common_chat_msg next;
        if (chat) {
            next = common_chat_parse(text, partial, parser);
            next.set_tool_call_ids(tool_ids, [] {
                static std::atomic<uint64_t> serial{0};
                return "call_" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count())
                        + "_" + std::to_string(serial.fetch_add(1));
            });
        } else {
            next.content = text;
        }
        next.role = "assistant";
        for (const auto & diff : common_chat_msg_diff::compute_diffs(message, next)) {
            if (!events) {
                send(diff.content_delta);
                continue;
            }
            common_json delta = common_json::object();
            if (!diff.content_delta.empty()) delta["content"] = diff.content_delta;
            if (!diff.reasoning_content_delta.empty()) delta["reasoning_content"] = diff.reasoning_content_delta;
            if (diff.tool_call_index != std::string::npos) {
                size_t index = diff.tool_call_index;
                const auto & tool = next.tool_calls.at(index);
                common_json function = common_json::object();
                size_t previous_name = index < message.tool_calls.size() ? message.tool_calls[index].name.size() : 0;
                if (tool.name.size() > previous_name) function["name"] = tool.name.substr(previous_name);
                if (!diff.tool_call_delta.arguments.empty()) function["arguments"] = diff.tool_call_delta.arguments;
                common_json call = {{"index", index}, {"function", function}};
                if (index >= message.tool_calls.size()) {
                    call["id"] = tool.id;
                    call["type"] = "function";
                }
                delta["tool_calls"] = common_json::array({call});
            }
            if (!delta.empty()) send(delta.dump());
        }
        message = std::move(next);
        consumed = text.size();
    }
};

// Hold only an unfinished UTF-8 codepoint and a possible stop-string prefix.
size_t safe_output_end(const std::string & text, const std::vector<std::string> & stops, bool final) {
    size_t end = text.size();
    if (!final) {
        for (const auto & stop : stops) {
            for (size_t n = 1; n < stop.size() && n <= text.size(); ++n) {
                if (text.compare(text.size() - n, n, stop, 0, n) == 0) end = std::min(end, text.size() - n);
            }
        }
    }
    size_t offset = 0;
    std::string_view prefix(text.data(), end);
    while (offset < end) {
        auto codepoint = common_parse_utf8_codepoint(prefix, offset);
        if (codepoint.status == utf8_parse_result::INVALID) throw std::runtime_error("Invalid UTF-8 in core output");
        if (codepoint.status == utf8_parse_result::INCOMPLETE) {
            if (final) throw std::runtime_error("Generation ended with an incomplete UTF-8 character");
            return offset;
        }
        offset += codepoint.bytes_consumed;
    }
    return end;
}

std::string generate(Engine & runtime, common_params_sampling & params,
                     int32_t max_tokens, const std::vector<std::string> & stops,
                     int & completion_tokens, OutputStream & stream, std::string & finish_reason) {
    owned<common_sampler, common_sampler_free> sampler(
            common_sampler_init(runtime.model, params), common_sampler_free);
    if (!sampler) throw std::runtime_error("Sampler initialization failed");
    const llama_vocab * vocab = llama_model_get_vocab(runtime.model);
    std::string output;
    completion_tokens = 0;
    finish_reason = "length";
    for (int32_t i = 0; max_tokens < 0 || i < max_tokens; i++) {
        if (runtime.cancelled.load(std::memory_order_relaxed)) throw RequestCancelled("Inference cancelled");
        llama_token token = common_sampler_sample(sampler.get(), runtime.context, -1);
        common_sampler_accept(sampler.get(), token, true);
        if (llama_vocab_is_eog(vocab, token)) { finish_reason = "stop"; break; }
        output += common_token_to_piece(vocab, token, true);
        completion_tokens++;
        size_t stop_at = std::string::npos;
        for (const auto & stop : stops) {
            if (!stop.empty()) stop_at = std::min(stop_at, output.find(stop));
        }
        if (stop_at != std::string::npos) {
            output.resize(stop_at);
            finish_reason = "stop";
            break;
        }
        size_t end = safe_output_end(output, stops, false);
        if (end > stream.consumed) stream.update(output.substr(0, end), true);
        if (max_tokens >= 0 && i + 1 == max_tokens) break;
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(runtime.context, batch) != 0) throw std::runtime_error("llama_decode failed during generation");
    }
    safe_output_end(output, stops, true);
    runtime.progress.check_cancelled();
    stream.update(output, false);
    if (finish_reason == "stop" && !stream.message.tool_calls.empty()) finish_reason = "tool_calls";
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
        runtime.cancelled.store(false, std::memory_order_relaxed);
        common_json request = common_json::parse(request_json == nullptr ? "" : request_json);
        runtime.unload();
        llama_model_params model_params = llama_model_default_params();
        std::string model_path = required(request, "modelPath").get<std::string>();
        runtime.model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (runtime.model == nullptr) throw std::runtime_error("llama.cpp 无法加载模型: " + model_path);
        llama_context_params context_params = context_parameters(request, runtime.progress);
        runtime.batch_size = static_cast<int32_t>(context_params.n_batch);
        runtime.context = llama_init_from_model(runtime.model, context_params);
        if (runtime.context == nullptr) throw std::runtime_error("llama.cpp 无法创建推理上下文");
        std::string mmproj_path = string_value(request, "mmprojPath");
        if (!mmproj_path.empty()) {
            mtmd_context_params mtmd_params = vision_parameters(context_params.n_threads, runtime.progress);
            runtime.vision = mtmd_init_from_file(mmproj_path.c_str(), runtime.model, mtmd_params);
            if (runtime.vision == nullptr) throw std::runtime_error("MTMD 无法加载 MMPROJ: " + mmproj_path);
            if (!mtmd_support_vision(runtime.vision)) throw std::runtime_error("MMPROJ 不支持图片输入");
        }
        runtime.templates = common_chat_templates_init(
                runtime.model, string_value(request, "chatTemplate"));
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

extern "C" LOCALCORE_EXPORT int localcore_core_estimate_memory(
        void * instance, const char * request_json, char ** result_json, char ** error) {
    if (result_json != nullptr) *result_json = nullptr;
    if (error != nullptr) *error = nullptr;
    try {
        Engine & runtime = engine(instance);
        std::lock_guard<std::mutex> lock(runtime.operation);
        const common_json request = common_json::parse(request_json == nullptr ? "" : request_json);
        // A separate progress object keeps graph reservation away from the active model's state.
        std::atomic_bool cancelled{false};
        PrefillProgress progress{cancelled};
        const auto context_params = context_parameters(request, progress);
        auto model_params = llama_model_default_params();
        model_params.no_alloc = true;
        model_params.load_mode = LLAMA_LOAD_MODE_NONE;
        const std::string path = required(request, "modelPath").get<std::string>();
        owned<llama_model, llama_model_free> model(
                llama_model_load_from_file(path.c_str(), model_params), llama_model_free);
        if (!model) throw std::runtime_error("内存估算无法读取模型结构: " + path);
        owned<llama_context, llama_free> context(llama_init_from_model(model.get(), context_params), llama_free);
        if (!context) throw std::runtime_error("内存估算无法规划上下文和计算图");
        uint64_t weights = 0, cache = 0, compute = 0, mmproj = 0;
        for (const auto & entry : llama_get_memory_breakdown(context.get())) {
            weights += entry.second.model;
            cache += entry.second.context;
            compute += entry.second.compute;
        }
        const std::string mmproj_path = string_value(request, "mmprojPath");
        if (!mmproj_path.empty()) {
            const auto memory = mtmd_get_memory_usage(mmproj_path.c_str(),
                    vision_parameters(context_params.n_threads, progress));
            // A projector without any reported allocations is not a valid estimate.
            if (memory.empty()) throw std::runtime_error("内存估算无法规划 MMPROJ: " + mmproj_path);
            for (const auto & entry : memory) mmproj += entry.second;
        }
        common_json result = common_json::object({
                {"version", localcore_core_version()},
                {"modelBytes", weights}, {"contextBytes", cache}, {"computeBytes", compute},
                {"mmprojBytes", mmproj}, {"totalBytes", weights + cache + compute + mmproj},
                {"contextSize", llama_n_ctx(context.get())},
                {"batchSize", llama_n_batch(context.get())},
                {"microBatchSize", llama_n_ubatch(context.get())},
                {"hasMmproj", !mmproj_path.empty()},
        });
        set_string(result_json, result.dump());
        return 0;
    } catch (const std::exception & failure) {
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

static int infer_impl(void * instance, const char * request_json, localcore_token_callback token_callback,
        void * token_user_data, localcore_progress_callback progress_callback,
        void * progress_user_data, localcore_progress_callback2 progress_callback2,
        void * progress_user_data2, char ** result_json, char ** error, bool json_events = false);

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
    return infer_impl(instance, request_json, token_callback, token_user_data,
            progress_callback, progress_user_data, nullptr, nullptr, result_json, error);
}

extern "C" LOCALCORE_EXPORT int localcore_core_infer3(
        void * instance, const char * request_json, localcore_token_callback token_callback,
        void * token_user_data, localcore_progress_callback2 progress_callback,
        void * progress_user_data, char ** result_json, char ** error) {
    return infer_impl(instance, request_json, token_callback, token_user_data,
            nullptr, nullptr, progress_callback, progress_user_data, result_json, error);
}

extern "C" LOCALCORE_EXPORT int localcore_core_infer4(
        void * instance, const char * request_json, localcore_token_callback event_callback,
        void * event_user_data, localcore_progress_callback2 progress_callback,
        void * progress_user_data, char ** result_json, char ** error) {
    return infer_impl(instance, request_json, event_callback, event_user_data,
            nullptr, nullptr, progress_callback, progress_user_data, result_json, error, true);
}

static int infer_impl(void * instance, const char * request_json, localcore_token_callback token_callback,
        void * token_user_data, localcore_progress_callback progress_callback,
        void * progress_user_data, localcore_progress_callback2 progress_callback2,
        void * progress_user_data2, char ** result_json, char ** error, bool json_events) {
    bool computing = false;
    bool model_ready = false;
    try {
        Engine & runtime = engine(instance);
        std::lock_guard<std::mutex> lock(runtime.operation);
        if (runtime.model == nullptr || runtime.context == nullptr) throw std::runtime_error("尚未加载模型");
        model_ready = true;
        runtime.cancelled.store(false, std::memory_order_relaxed);
        runtime.progress.start(progress_callback, progress_user_data, progress_callback2, progress_user_data2);
        struct ProgressScope {
            PrefillProgress & progress;
            ~ProgressScope() { progress.stop(); }
        } progress_scope{runtime.progress};
        // Reconcile a Java interrupt that raced with the per-request reset before
        // parsing templates or starting any compute, even if no token is emitted.
        runtime.progress.preparing("request_prepare");
        common_json request = common_json::parse(request_json == nullptr ? "" : request_json);
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
        common_params_sampling params = sampling_params(runtime, request, chat_pointer);
        int32_t max_tokens = int_value(request, "max_tokens", 1024);
        auto stops = string_array(request, "stop");
        OutputStream stream(chat_pointer, token_callback, token_user_data, json_events);
        stream.parser.reasoning_format = common_reasoning_format_from_name(string_value(request, "reasoning_format", "none"));
        computing = true;
        int prompt_tokens = media_paths.empty()
                ? evaluate_text(runtime, prompt)
                : evaluate_media(runtime, prompt, media_paths);
        runtime.progress.verify_complete();
        runtime.progress.stop();
        int completion_tokens = 0;
        std::string finish_reason;
        std::string text = generate(runtime, params, max_tokens, stops, completion_tokens, stream, finish_reason);
        auto result = make_result(prompt_tokens, completion_tokens, text, chat_pointer == nullptr ? nullptr : &stream.message);
        result["finishReason"] = finish_reason;
        std::fprintf(stderr, "LocalCore request=%s finish=%s promptTokens=%d completionTokens=%d\n",
                string_value(request, "_requestId", "local").c_str(), finish_reason.c_str(), prompt_tokens, completion_tokens);
        set_string(result_json, result.dump());
        if (error != nullptr) *error = nullptr;
        return 0;
    } catch (const std::exception & failure) {
        set_string(error, failure.what());
        bool cancelled = dynamic_cast<const RequestCancelled *>(&failure) != nullptr
                || (instance != nullptr && static_cast<Engine *>(instance)->cancelled.load());
        bool invalid = model_ready && !computing;
        int code = cancelled ? 3 : invalid ? 2 : 1;
        std::fprintf(stderr, "LocalCore infer failure code=%d computing=%d error=%s\n", code, computing, failure.what());
        return code;
    }
}

extern "C" LOCALCORE_EXPORT void localcore_core_cancel(void * instance) {
    if (instance != nullptr) static_cast<Engine *>(instance)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" LOCALCORE_EXPORT void localcore_core_free_string(char * value) {
    std::free(value);
}
