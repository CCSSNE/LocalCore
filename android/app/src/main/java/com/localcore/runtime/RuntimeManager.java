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
                int actualRuntimeApi = NativeBridge.runtimeApi(handle);
                int requiredRuntimeApi = model.optJSONObject("requirements").optInt("runtimeApi");
                if (actualRuntimeApi != requiredRuntimeApi) {
                    throw new IllegalStateException("动态核心 runtimeApi=" + actualRuntimeApi
                            + "，模型明确要求 runtimeApi=" + requiredRuntimeApi);
                }
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
            if (NativeBridge.runtimeApi(activeHandle) >= 2) {
                JSONObject effective = effectiveChatRequest(model, request);
                JSONObject plan = Jsons.parseObject(NativeBridge.prepareChat(activeHandle, effective.toString(), template), "聊天计划");
                String prompt = plan.optString("prompt");
                boolean structured = plan.optBoolean("parseToolCalls") || !"none".equals(plan.optString("reasoningFormat"));
                Result generated = generateLocked(activeHandle, model, prompt, effective,
                        structured ? null : consumer, plan);
                JSONObject message = Jsons.parseObject(NativeBridge.parseChatOutput(activeHandle,
                        plan.toString(), generated.text), "助手消息");
                return new Result(generated.promptTokens, generated.completionTokens, generated.text, message, structured);
            }
            List<String> roles = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                Object content = message == null ? null : message.opt("content");
                if (message == null || message.optString("role").isEmpty() || !(content instanceof String)) {
                    throw new IllegalArgumentException("runtimeApi=1 只支持字符串 role/content 消息");
                }
                roles.add(message.optString("role"));
                contents.add((String) content);
            }
            if (request.has("tools") || request.has("reasoning_format") || request.has("reasoning_effort")) {
                throw new IllegalArgumentException("工具调用和 thinking 要求 runtimeApi=2");
            }
            String prompt = NativeBridge.applyChatTemplate(activeHandle, roles.toArray(new String[0]),
                    contents.toArray(new String[0]), template);
            Result generated = generateLocked(activeHandle, model, prompt, request, consumer, null);
            JSONObject message = new JSONObject();
            put(message, "role", "assistant");
            put(message, "content", generated.text);
            return new Result(generated.promptTokens, generated.completionTokens, generated.text, message, false);
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
            return generateLocked(activeHandle, model, prompt, request, consumer, null);
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
                                   JSONObject request, TokenConsumer consumer, JSONObject chatPlan) {
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
        JSONArray mergedStops = new JSONArray();
        String[] configuredStops = stops(request.has("stop") ? request.opt("stop") : defaults.optJSONArray("stop"));
        for (String stop : configuredStops) mergedStops.put(stop);
        if (chatPlan != null) {
            JSONArray additional = chatPlan.optJSONArray("additionalStops");
            for (int i = 0; additional != null && i < additional.length(); i++) mergedStops.put(additional.optString(i));
        }
        String[] stopValues = stops(mergedStops);
        RuntimeState before = state();
        setState(new RuntimeState(RuntimeState.Phase.GENERATING, before.coreId, before.coreVersion,
                model.optString("id"), null));
        StringBuilder text = new StringBuilder();
        final IOException[] callbackError = new IOException[1];
        int generated = NativeBridge.generate(activeHandle, prompt, maxTokens, (float) temperature,
                (float) topP, topK, seed, stopValues, chatPlan == null ? null : chatPlan.toString(), token -> {
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
        if ("embedded".equals(binding.optString("mode"))) return null;
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
