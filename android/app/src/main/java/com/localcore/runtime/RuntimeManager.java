package com.localcore.runtime;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.ExternalFile;
import com.localcore.resource.ResourceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单一推理入口：解析已安装资源，把请求交给极薄 Loader 打开的 LocalCore ABI 核心。
 * llama.cpp、Jinja、采样、工具调用解析和 MTMD 均只存在于可更新的核心 SO 中。
 */
public final class RuntimeManager {
    public interface Listener {
        void onRuntimeState(RuntimeState state);
    }

    public interface TokenConsumer {
        boolean onToken(String token) throws IOException;
    }

    /** Receives core-parsed JSON deltas; plain consumers receive only content. */
    public interface EventConsumer extends TokenConsumer {}

    public interface StageListener {
        void onStage(String stage);
    }

    public interface ProgressListener {
        void onProgress(String phase, int done, int total);
    }

    public interface Progress2Listener {
        void onProgress(String phase, int doneTokens, int totalTokens, long elapsedMs);
    }

    public static final class Result {
        public final int promptTokens;
        public final int completionTokens;
        public final String text;
        public final JSONObject message;
        public final boolean structured;
        public final long ttftMs;
        public final long llmMs;
        public final JSONObject generation;
        public final String finishReason;

        public Result(int promptTokens, int completionTokens, String text, JSONObject message, boolean structured,
                      long ttftMs, long llmMs, JSONObject generation, String finishReason) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.text = text;
            this.message = message;
            this.structured = structured;
            this.ttftMs = ttftMs;
            this.llmMs = llmMs;
            this.generation = generation;
            this.finishReason = finishReason;
        }
    }

    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;
    private final Context context;
    private final java.util.Map<String, ParcelFileDescriptor> externalHandles = new java.util.HashMap<>();
    private final MediaResolver media;
    private final NativeRuntime nativeRuntime = new NativeRuntime();
    private final ReentrantLock inference = new ReentrantLock(true);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private RuntimeState state = RuntimeState.empty();
    private String loadedModelId;
    private String loadedModelName;
    private JSONObject loadedParameters;
    private JSONObject loadedColdConfig;

    public RuntimeManager(Context context, ConfigRepository config, ResourceManager resources, EventLog events) {
        this.context = context;
        this.config = config;
        this.resources = resources;
        this.events = events;
        this.media = new MediaResolver(context);
    }

    public synchronized RuntimeState state() {
        return state;
    }

    public void setMaxImagePixels(int pixels) {
        media.setMaxImagePixels(pixels);
    }

    public void loadModel(String modelId) {
        inference.lock();
        String openedExternal = null;
        try {
            closeExternalHandles();
            JSONObject model = findModel(modelId);
            String coreId = model.getString("core");
            File coreFile = resources.installedFile(coreId);
            String resourceId = model.getString("resource");
            ParcelFileDescriptor handle = openExternalModel(resourceId);
            if (handle != null) {
                synchronized (externalHandles) {
                    externalHandles.put(resourceId, handle);
                }
                openedExternal = resourceId;
            }
            String modelPath = modelPath(resourceId, handle);
            JSONObject coreDescriptor = findResource(coreId);
            JSONObject load = model.getJSONObject("load");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_LOADING, coreId,
                    coreDescriptor.optString("version"), modelId, null));
            nativeRuntime.openCore(coreFile.getAbsolutePath());
            JSONObject request = modelRequest(model, load, modelPath);
            JSONObject template = model.optJSONObject("template");
            if (template != null && "custom".equals(template.optString("mode"))) {
                String custom = template.optString("value", "");
                if (custom.isEmpty()) {
                    throw new IllegalArgumentException("自定义模板为空，拒绝加载模型 " + modelId);
                }
                request.put("chatTemplate", custom);
            }
            JSONObject response = new JSONObject(nativeRuntime.loadModel(request.toString()));
            loadedModelId = modelId;
            loadedModelName = model.optString("name");
            loadedColdConfig = new JSONObject(load.toString());
            loadedParameters = new JSONObject(request.toString());
            loadedParameters.remove("modelPath");
            loadedParameters.remove("mmprojPath");
            String version = response.getString("version");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, coreId, version, modelId, null));
            events.info("runtime", "模型已加载 " + modelId + "，核心 " + version
                    + "，vision=" + response.optBoolean("vision"));
        } catch (Exception error) {
            if (openedExternal != null) closeExternalHandle(openedExternal);
            loadedModelId = null;
            setState(new RuntimeState(RuntimeState.Phase.ERROR, null, null, modelId, error.getMessage()));
            events.error("runtime", "模型加载失败 " + modelId, error);
            throw asRuntime(error);
        } finally {
            inference.unlock();
        }
    }

    public String estimateMemory(String modelId, String loadJson) {
        inference.lock();
        try {
            JSONObject model = findModel(modelId);
            String corePath = resources.installedFile(model.getString("core")).getAbsolutePath();
            String resourceId = model.getString("resource");
            // The planning descriptor has its own lifetime; never close the loaded model's FD.
            try (ParcelFileDescriptor handle = openExternalModel(resourceId)) {
                JSONObject request = modelRequest(model, new JSONObject(loadJson), modelPath(resourceId, handle));
                String result = nativeRuntime.estimateMemory(corePath, request.toString());
                events.info("runtime", "模型内存估算 " + modelId + "，参数=" + loadJson + "，结果=" + result);
                return result;
            }
        } catch (Exception error) {
            events.error("runtime", "模型内存估算失败 " + modelId + "，参数=" + loadJson, error);
            throw asRuntime(error);
        } finally {
            inference.unlock();
        }
    }

    private ParcelFileDescriptor openExternalModel(String resourceId) throws Exception {
        String uri = findResource(resourceId).optString("externalUri");
        return uri.isEmpty() ? null : ExternalFile.openRegularFile(context.getContentResolver(), Uri.parse(uri));
    }

    private String modelPath(String resourceId, ParcelFileDescriptor handle) {
        return handle == null ? resources.installedFile(resourceId).getAbsolutePath() : ExternalFile.fdPath(handle);
    }

    private JSONObject modelRequest(JSONObject model, JSONObject load, String path) throws Exception {
        JSONObject request = new JSONObject();
        request.put("modelPath", path);
        String mmprojId = model.optString("mmproj");
        if (!mmprojId.isEmpty()) request.put("mmprojPath", resources.installedFile(mmprojId).getAbsolutePath());
        // Preserve numeric values for the shared core parameter parser; do not truncate drafts in Java.
        request.put("contextSize", load.get("contextSize"));
        request.put("batchSize", load.get("batchSize"));
        request.put("threads", load.get("threads"));
        return request;
    }

    public void unload() {
        inference.lock();
        try {
            nativeRuntime.unloadModel();
            closeExternalHandles();
            loadedModelId = null;
            setState(RuntimeState.empty());
            events.info("runtime", "模型已卸载");
        } finally {
            inference.unlock();
        }
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer) {
        return chat(messages, request, consumer, null);
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer, StageListener stages) {
        return chat(messages, request, consumer, stages, null);
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer, StageListener stages,
                       ProgressListener progress) {
        return chat(messages, request, consumer, stages, progress, null);
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer, StageListener stages,
                       ProgressListener progress, Progress2Listener progress2) {
        stage(stages, "媒体解析开始(" + messages.length() + "条消息)");
        try (MediaResolver.Prepared prepared = media.prepare(messages)) {
            stage(stages, "媒体解析完成(" + prepared.paths.length() + "个媒体文件)");
            JSONObject body = new JSONObject(request.toString());
            body.put("messages", prepared.messages);
            body.put("mediaPaths", prepared.paths);
            return runInference("chat", body, consumer, stages, progress, progress2);
        } catch (Exception error) {
            throw asRuntime(error);
        }
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer) {
        return complete(prompt, request, consumer, null);
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer, StageListener stages) {
        return complete(prompt, request, consumer, stages, null);
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer, StageListener stages,
                           ProgressListener progress) {
        return complete(prompt, request, consumer, stages, progress, null);
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer, StageListener stages,
                           ProgressListener progress, Progress2Listener progress2) {
        try {
            JSONObject body = new JSONObject(request.toString());
            body.put("prompt", prompt);
            return runInference("complete", body, consumer, stages, progress, progress2);
        } catch (Exception error) {
            throw asRuntime(error);
        }
    }

    public void cancel() {
        nativeRuntime.cancel();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private Result runInference(String kind, JSONObject body, TokenConsumer consumer, StageListener stages,
                                ProgressListener progress, Progress2Listener progress2) {
        long queuedAt = System.currentTimeMillis();
        String requestId = body.optString("_requestId", "local");
        String requestedModel = body.optString("model");
        events.info("runtime", requestId + " 排队 kind=" + kind + " requestedModel=" + requestedModel);
        inference.lock();
        final long startedAt = System.currentTimeMillis();
        final long[] firstTokenAt = {0};
        final TokenConsumer timed = token -> {
            if (firstTokenAt[0] == 0) {
                firstTokenAt[0] = System.currentTimeMillis();
                stage(stages, "首字到达");
            }
            if (consumer == null) return true;
            if (consumer instanceof EventConsumer) return consumer.onToken(token);
            try {
                String content = new JSONObject(token).optString("content", "");
                return content.isEmpty() || consumer.onToken(content);
            } catch (org.json.JSONException error) {
                throw new IllegalStateException("核心增量事件不是有效 JSON", error);
            }
        };
        final NativeRuntime.Progress2Consumer forwarding2 = progress2 == null ? null :
                (phase, doneTokens, totalTokens, elapsedMs) ->
                        progress2.onProgress(phase, doneTokens, totalTokens, elapsedMs);
        try {
            events.info("runtime", requestId + " 获得调度锁 queueMs="
                    + (startedAt - queuedAt) + " requestedModel=" + requestedModel + " loadedModel=" + loadedModelId);
            if (!requestedModel.isEmpty() && (!requestedModel.equals(loadedModelId)
                    || state().phase == RuntimeState.Phase.ERROR)) {
                findModel(requestedModel); // Unknown request IDs must not invalidate the loaded model.
                loadModel(requestedModel);
            }
            if (loadedModelId == null) throw new IllegalStateException("尚未加载模型");
            events.info("runtime", requestId + " 执行模型=" + loadedModelId);
            RuntimeState before = state();
            setState(new RuntimeState(RuntimeState.Phase.GENERATING,
                    before.coreId, before.coreVersion, loadedModelId, null));
            body.put("type", kind);
            applyModelDefaults(body);
            JSONObject hotParameters = new JSONObject(body.toString());
            for (String key : new String[]{"type", "messages", "prompt", "mediaPaths"}) {
                hotParameters.remove(key);
            }
            JSONObject generation = new JSONObject()
                    .put("modelId", loadedModelId)
                    .put("modelName", loadedModelName)
                    .put("cold", loadedColdConfig)
                    .put("loadRequest", loadedParameters)
                    .put("hot", hotParameters);
            stage(stages, "核心推理开始");
            JSONObject response = new JSONObject(nativeRuntime.infer4(body.toString(), timed, forwarding2));
            stage(stages, "核心推理结束");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY,
                    before.coreId, before.coreVersion, loadedModelId, null));
            long elapsed = System.currentTimeMillis() - startedAt;
            long ttft = firstTokenAt[0] == 0 ? elapsed : firstTokenAt[0] - startedAt;
            // 全量推理日志：与前端测试页逐字对照，核心输入 body 与全量输出 text 都落盘，不截断。
            events.info("runtime", "推理完成 kind=" + kind + " model=" + loadedModelId
                    + " promptTokens=" + response.optInt("promptTokens")
                    + " completionTokens=" + response.optInt("completionTokens")
                    + " finishReason=" + response.getString("finishReason") + " ttftMs=" + ttft + " llmMs=" + elapsed
                    + " 请求=" + body + " 输出=" + response.optString("text"));
            return new Result(response.getInt("promptTokens"), response.getInt("completionTokens"),
                    response.getString("text"), response.optJSONObject("message"),
                    response.optBoolean("structured"), ttft, elapsed, generation, response.getString("finishReason"));
        } catch (Exception error) {
            RuntimeState before = state();
            boolean requestFailure = error instanceof IllegalArgumentException || error instanceof IOException
                    || error instanceof java.util.concurrent.CancellationException;
            if (requestFailure && loadedModelId != null && before.phase == RuntimeState.Phase.GENERATING) {
                setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, before.coreId, before.coreVersion,
                        loadedModelId, null));
            } else if (!requestFailure) {
                setState(new RuntimeState(RuntimeState.Phase.ERROR, before.coreId, before.coreVersion,
                        before.modelId, error.getMessage()));
            }
            events.info("runtime", requestId + " 请求结束 requestFailure=" + requestFailure
                    + " modelState=" + state().phase + " errorClass=" + error.getClass().getName());
            events.error("runtime", "推理失败 kind=" + kind + " model=" + loadedModelId + " 请求=" + body, error);
            throw asRuntime(error);
        } finally {
            inference.unlock();
        }
    }

    private static void stage(StageListener stages, String text) {
        if (stages != null) stages.onStage(text);
    }

    private volatile JSONObject hotDefaults = new JSONObject();

    public void setHotDefaults(String json) throws org.json.JSONException {
        hotDefaults = new JSONObject(json == null ? "{}" : json);
    }

    // 全局热参数只做缺省：请求里已有的键不动，保证 API 显式传参永远优先。
    // 与模型解绑后不再读模型配置；存量 inference 块留着不动但也不再看。
    private void applyModelDefaults(JSONObject body) throws org.json.JSONException {
        JSONObject hot = hotDefaults;
        java.util.Iterator<String> keys = hot.keys();
        while (keys.hasNext()) {
            String configuredKey = keys.next();
            putDefault(body, requestKey(configuredKey), hot.opt(configuredKey));
        }
    }

    private static String requestKey(String key) {
        switch (key) {
            case "maxTokens": return "max_tokens";
            case "topP": return "top_p";
            case "topK": return "top_k";
            default: return key;
        }
    }

    private static void putDefault(JSONObject body, String key, Object value) throws org.json.JSONException {
        if (body.has(key) || value == null || JSONObject.NULL.equals(value)) return;
        body.put(key, value);
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

    private void closeExternalHandles() {
        synchronized (externalHandles) {
            for (ParcelFileDescriptor handle : externalHandles.values()) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                }
            }
            externalHandles.clear();
        }
    }

    private void closeExternalHandle(String resourceId) {
        synchronized (externalHandles) {
            ParcelFileDescriptor handle = externalHandles.remove(resourceId);
            if (handle == null) return;
            try {
                handle.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void setState(RuntimeState next) {
        synchronized (this) {
            state = next;
        }
        for (Listener listener : listeners) listener.onRuntimeState(next);
    }

    private static RuntimeException asRuntime(Exception error) {
        return error instanceof RuntimeException ? (RuntimeException) error
                : new IllegalStateException(error.getMessage(), error);
    }
}
