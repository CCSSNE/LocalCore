package com.localcore.runtime;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.Jsons;
import com.localcore.resource.ResourceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

public final class RuntimeManager {
    public interface Listener {
        void onRuntimeState(RuntimeState state);
    }

    public interface TokenConsumer {
        boolean onToken(String token) throws IOException;
    }

    public static final class Result {
        public final int promptTokens;
        public final int completionTokens;
        public final String text;
        public final JSONObject message;
        public final boolean structured;

        public Result(int promptTokens, int completionTokens, String text) {
            this(promptTokens, completionTokens, text, null, false);
        }

        public Result(int promptTokens, int completionTokens, String text, JSONObject message, boolean structured) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.text = text;
            this.message = message;
            this.structured = structured;
        }
    }

    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;
    private final ReentrantLock inference = new ReentrantLock(true);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long handle;
    private JSONObject loadedModel;
    private RuntimeState state = RuntimeState.empty();

    public RuntimeManager(ConfigRepository config, ResourceManager resources, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.events = events;
        this.config.addListener(ignored -> invalidateForConfigActivation());
    }

    public synchronized RuntimeState state() {
        return state;
    }

    public void loadModel(String modelId) {
        if (!inference.tryLock()) {
            throw new IllegalStateException("当前仍有推理请求，不能切换模型");
        }
        try {
            JSONObject model = findModel(modelId);
            String coreId = model.optString("core");
            File core = resources.installedFile(coreId);
            File modelFile = resources.installedFile(model.optString("resource"));
            JSONObject coreDescriptor = findResource(coreId);
            setState(new RuntimeState(RuntimeState.Phase.MODEL_LOADING, coreId,
                    coreDescriptor.optString("version"), modelId, null));
            synchronized (this) {
                closeLocked();
                handle = NativeBridge.open(core.getAbsolutePath());
                JSONObject load = model.optJSONObject("load");
                NativeBridge.loadModel(handle, modelFile.getAbsolutePath(),
                        load.optInt("contextSize"), load.optInt("batchSize"),
                        load.optInt("threads"), load.optInt("gpuLayers"));
                loadedModel = model;
                setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, coreId,
                        NativeBridge.version(handle), modelId, null));
            }
            events.info("runtime", "模型已加载 " + modelId + "，核心 " + NativeBridge.version(handle));
        } catch (RuntimeException error) {
            synchronized (this) { closeLocked(); }
            setState(new RuntimeState(RuntimeState.Phase.ERROR, null, null, modelId, error.getMessage()));
            events.error("runtime", "模型加载失败 " + modelId, error);
            throw error;
        } finally {
            inference.unlock();
        }
    }

    public void unload() {
        if (!inference.tryLock()) {
            throw new IllegalStateException("当前仍有推理请求，不能卸载模型");
        }
        try {
            synchronized (this) { closeLocked(); }
            setState(RuntimeState.empty());
            events.info("runtime", "模型和动态核心已卸载");
        } finally {
            inference.unlock();
        }
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer) {
        inference.lock();
        try {
            final long activeHandle;
            final JSONObject model;
            synchronized (this) {
                if (handle == 0 || loadedModel == null) {
                    throw new IllegalStateException("尚未加载模型");
                }
                activeHandle = handle;
                model = loadedModel;
            }
            if (messages.length() == 0) throw new IllegalArgumentException("messages 不能为空");
            String template = template(model);
            JSONObject effective = effectiveChatRequest(model, request);
            JSONArray tools = effective.optJSONArray("tools");
            boolean parseToolCalls = tools != null && tools.length() > 0;
            String grammar = effective.optString("grammar", "");
            if (parseToolCalls && !grammar.isEmpty()) {
                throw new IllegalArgumentException("工具调用不能与自定义 grammar 同时使用");
            }
            List<String> roles = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                Object content = message == null ? null : message.opt("content");
                if (message == null || message.optString("role").isEmpty() || content == null) {
                    throw new IllegalArgumentException("消息 role/content 不能为空");
                }
                roles.add(message.optString("role"));
                contents.add(content instanceof String ? (String) content : content.toString());
            }
            if (parseToolCalls) {
                String toolHint = "可用工具(JSON Schema):\n" + tools
                        + "\n需要调用工具时，只使用 <tool_call>{\"name\": \"工具名\", \"arguments\": {...}}</tool_call> 格式输出。";
                boolean merged = false;
                for (int i = 0; i < roles.size(); i++) {
                    if ("system".equals(roles.get(i))) {
                        contents.set(i, contents.get(i) + "\n\n" + toolHint);
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    roles.add(0, "system");
                    contents.add(0, toolHint);
                }
            }
            String prompt = NativeBridge.applyChatTemplate(activeHandle, roles.toArray(new String[0]),
                    contents.toArray(new String[0]), template);
            Result generated = generateLocked(activeHandle, model, prompt, effective, consumer, grammar);
            JSONObject message = parseAssistantOutput(generated.text, effective, parseToolCalls);
            return new Result(generated.promptTokens, generated.completionTokens, generated.text,
                    message, parseToolCalls || !"none".equals(effective.optString("reasoning_format", "none")));
        } catch (RuntimeException error) {
            RuntimeState before = state();
            setState(new RuntimeState(RuntimeState.Phase.ERROR, before.coreId, before.coreVersion,
                    before.modelId, error.getMessage()));
            events.error("runtime", "推理失败", error);
            throw error;
        } finally {
            inference.unlock();
        }
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer) {
        if (prompt == null) throw new IllegalArgumentException("prompt 不能为空");
        inference.lock();
        try {
            final long activeHandle;
            final JSONObject model;
            synchronized (this) {
                if (handle == 0 || loadedModel == null) throw new IllegalStateException("尚未加载模型");
                activeHandle = handle;
                model = loadedModel;
            }
            return generateLocked(activeHandle, model, prompt, request, consumer,
                    request.optString("grammar", ""));
        } catch (RuntimeException error) {
            RuntimeState before = state();
            setState(new RuntimeState(RuntimeState.Phase.ERROR, before.coreId, before.coreVersion,
                    before.modelId, error.getMessage()));
            events.error("runtime", "推理失败", error);
            throw error;
        } finally {
            inference.unlock();
        }
    }

    public synchronized void cancel() {
        if (handle != 0) NativeBridge.cancel(handle);
    }

    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    private Result generateLocked(long activeHandle, JSONObject model, String prompt,
                                   JSONObject request, TokenConsumer consumer, String grammar) {
        int promptTokens = NativeBridge.tokenCount(activeHandle, prompt);
        JSONObject defaults = model.optJSONObject("inference");
        int maxTokens = request.has("max_tokens") ? request.optInt("max_tokens", -1) : defaults.optInt("maxTokens");
        double temperature = request.has("temperature") ? request.optDouble("temperature", -1) : defaults.optDouble("temperature");
        double topP = request.has("top_p") ? request.optDouble("top_p", -1) : defaults.optDouble("topP");
        int topK = request.has("top_k") ? request.optInt("top_k", -1) : defaults.optInt("topK");
        long seed = request.has("seed") ? request.optLong("seed", Long.MIN_VALUE) : defaults.optLong("seed");
        if (maxTokens < 1 || temperature < 0 || topP < 0 || topP > 1 || topK < 0
                || seed < -1 || seed > 0xffffffffL) {
            throw new IllegalArgumentException("请求中的推理参数超出有效范围");
        }
        String[] stopValues = stops(request.has("stop") ? request.opt("stop") : defaults.optJSONArray("stop"));
        RuntimeState before = state();
        setState(new RuntimeState(RuntimeState.Phase.GENERATING, before.coreId, before.coreVersion,
                model.optString("id"), null));
        StringBuilder text = new StringBuilder();
        final IOException[] callbackError = new IOException[1];
        int generated = NativeBridge.generate(activeHandle, prompt, maxTokens, (float) temperature,
                (float) topP, topK, seed, stopValues, grammar, token -> {
                    String decoded = new String(token, java.nio.charset.StandardCharsets.UTF_8);
                    text.append(decoded);
                    try {
                        return consumer == null || consumer.onToken(decoded);
                    } catch (IOException error) {
                        callbackError[0] = error;
                        return false;
                    }
                });
        if (callbackError[0] != null) throw new IllegalStateException("流式响应写入失败", callbackError[0]);
        setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, before.coreId, before.coreVersion,
                model.optString("id"), null));
        return new Result(promptTokens, generated, text.toString());
    }

    private JSONObject parseAssistantOutput(String text, JSONObject request, boolean parseToolCalls) {
        String reasoningFormat = request.optString("reasoning_format", "none");
        String content = text;
        String reasoning = null;
        int start = text.indexOf("<think>");
        if (!"none".equals(reasoningFormat) && start >= 0) {
            int end = text.indexOf("</think>");
            if (end >= 0) {
                reasoning = text.substring(start + "<think>".length(), end).trim();
                content = text.substring(end + "</think>".length()).stripLeading();
            } else {
                reasoning = text.substring(start + "<think>".length()).trim();
                content = "";
            }
        }
        JSONObject message = new JSONObject();
        put(message, "role", "assistant");
        put(message, "content", content);
        if (reasoning != null && !reasoning.isEmpty()) put(message, "reasoning_content", reasoning);
        if (!parseToolCalls) return message;
        JSONArray toolCalls = new JSONArray();
        int index = 0;
        StringBuilder cleaned = new StringBuilder();
        int cursor = 0;
        while (true) {
            int open = content.indexOf("<tool_call>", cursor);
            if (open < 0) break;
            int close = content.indexOf("</tool_call>", open);
            if (close < 0) break;
            cleaned.append(content, cursor, open);
            String body = content.substring(open + "<tool_call>".length(), close).trim();
            cursor = close + "</tool_call>".length();
            for (Object item : toolCallItems(body)) {
                index++;
                JSONObject call = new JSONObject();
                put(call, "id", "call_localcore_" + index);
                put(call, "type", "function");
                put(call, "function", item);
                toolCalls.put(call);
            }
        }
        cleaned.append(content.substring(cursor));
        if (index > 0) {
            put(message, "content", cleaned.toString().strip());
            put(message, "tool_calls", toolCalls);
        }
        return message;
    }

    private static List<JSONObject> toolCallItems(String body) {
        Object parsed = Jsons.parseObjectOrArray(body, "工具调用输出");
        List<JSONObject> result = new ArrayList<>();
        if (parsed instanceof JSONArray) {
            JSONArray array = (JSONArray) parsed;
            for (int i = 0; i < array.length(); i++) result.add(functionPayload(array.optJSONObject(i)));
        } else if (parsed instanceof JSONObject) {
            result.add(functionPayload((JSONObject) parsed));
        }
        return result;
    }

    private static JSONObject functionPayload(JSONObject call) {
        if (call == null) throw new IllegalArgumentException("工具调用必须是 JSON 对象");
        if (call.has("function") && call.optJSONObject("function") != null) {
            return call.optJSONObject("function");
        }
        JSONObject function = new JSONObject();
        Object arguments = call.has("arguments") ? call.opt("arguments") : call.optJSONObject("parameters");
        try {
            function.put("name", call.optString("name"));
            function.put("arguments", arguments instanceof String ? arguments : String.valueOf(arguments));
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("工具调用字段写入失败", error);
        }
        return function;
    }

    private JSONObject effectiveChatRequest(JSONObject model, JSONObject request) {
        JSONObject effective = Jsons.parseObject(request.toString(), "聊天请求");
        JSONObject tools = model.optJSONObject("toolCalling");
        JSONArray requestedTools = effective.optJSONArray("tools");
        if (requestedTools != null && requestedTools.length() > 0 && !tools.optBoolean("enabled")) {
            throw new IllegalArgumentException("模型配置未启用工具调用");
        }
        if (requestedTools != null && requestedTools.length() > 0) {
            if (!effective.has("tool_choice")) put(effective, "tool_choice", tools.optString("choice"));
            if (!effective.has("parallel_tool_calls")) put(effective, "parallel_tool_calls", tools.optBoolean("parallel"));
        }
        JSONObject thinking = model.optJSONObject("thinking");
        if (!effective.has("reasoning_format")) put(effective, "reasoning_format", thinking.optString("format"));
        if (!effective.has("enable_thinking")) put(effective, "enable_thinking", thinking.optBoolean("enabled"));
        if (!effective.has("reasoning_budget_tokens")) put(effective, "reasoning_budget_tokens", thinking.optInt("budgetTokens"));
        return effective;
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (org.json.JSONException error) { throw new IllegalStateException("无法写入请求字段 " + key, error); }
    }

    private String template(JSONObject model) {
        JSONObject binding = model.optJSONObject("template");
        String content = binding.optString("content");
        if (!content.isEmpty()) return content;
        if (!"resource".equals(binding.optString("mode"))) return null;
        File file = resources.installedFile(binding.optString("resource"));
        try (FileInputStream input = new FileInputStream(file)) {
            return Jsons.readUtf8(input);
        } catch (IOException error) {
            throw new IllegalStateException("模板读取失败: " + file, error);
        }
    }

    private static String[] stops(Object value) {
        if (value == null || value == JSONObject.NULL) return new String[0];
        if (value instanceof String) {
            if (((String) value).isEmpty()) throw new IllegalArgumentException("stop 不能为空字符串");
            return new String[]{(String) value};
        }
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("stop 必须是字符串或字符串数组");
        JSONArray array = (JSONArray) value;
        String[] result = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String) || ((String) item).isEmpty()) {
                throw new IllegalArgumentException("stop[" + i + "] 必须是非空字符串");
            }
            result[i] = (String) item;
        }
        return result;
    }

    private JSONObject findModel(String id) {
        JSONArray models = config.current().optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (id.equals(model.optString("id"))) return model;
        }
        throw new IllegalArgumentException("配置中不存在模型: " + id);
    }

    private JSONObject findResource(String id) {
        JSONArray values = config.current().optJSONArray("resources");
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (id.equals(value.optString("id"))) return value;
        }
        throw new IllegalArgumentException("配置中不存在资源: " + id);
    }

    private synchronized void closeLocked() {
        if (handle != 0) {
            NativeBridge.unloadModel(handle);
            NativeBridge.close(handle);
            handle = 0;
        }
        loadedModel = null;
    }

    private void setState(RuntimeState next) {
        synchronized (this) { state = next; }
        for (Listener listener : listeners) listener.onRuntimeState(next);
    }

    private void invalidateForConfigActivation() {
        inference.lock();
        try {
            boolean hadRuntime;
            synchronized (this) {
                hadRuntime = handle != 0;
                closeLocked();
            }
            setState(RuntimeState.empty());
            if (hadRuntime) events.info("runtime", "配置已激活，旧模型与核心已卸载");
        } finally {
            inference.unlock();
        }
    }
}
