package com.localcore.runtime;

import android.content.Context;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
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

    public static final class Result {
        public final int promptTokens;
        public final int completionTokens;
        public final String text;
        public final JSONObject message;
        public final boolean structured;

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
    private final MediaResolver media;
    private final NativeRuntime nativeRuntime = new NativeRuntime();
    private final ReentrantLock inference = new ReentrantLock(true);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private RuntimeState state = RuntimeState.empty();
    private String loadedModelId;

    public RuntimeManager(Context context, ConfigRepository config, ResourceManager resources, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.events = events;
        this.media = new MediaResolver(context);
    }

    public synchronized RuntimeState state() {
        return state;
    }

    public void loadModel(String modelId) {
        inference.lock();
        try {
            JSONObject model = findModel(modelId);
            String coreId = model.getString("core");
            File coreFile = resources.installedFile(coreId);
            File modelFile = resources.installedFile(model.getString("resource"));
            JSONObject coreDescriptor = findResource(coreId);
            JSONObject load = model.getJSONObject("load");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_LOADING, coreId,
                    coreDescriptor.optString("version"), modelId, null));
            nativeRuntime.openCore(coreFile.getAbsolutePath());
            JSONObject request = new JSONObject();
            request.put("modelPath", modelFile.getAbsolutePath());
            String mmprojId = model.optString("mmproj");
            if (!mmprojId.isEmpty()) request.put("mmprojPath", resources.installedFile(mmprojId).getAbsolutePath());
            request.put("contextSize", load.getInt("contextSize"));
            request.put("batchSize", load.getInt("batchSize"));
            request.put("threads", load.getInt("threads"));
            request.put("gpuLayers", Math.max(0, load.optInt("gpuLayers", 0)));
            JSONObject template = model.optJSONObject("template");
            if (template != null && "custom".equals(template.optString("mode"))) {
                request.put("chatTemplate", template.getString("value"));
            }
            JSONObject response = new JSONObject(nativeRuntime.loadModel(request.toString()));
            loadedModelId = modelId;
            String version = response.getString("version");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, coreId, version, modelId, null));
            events.info("runtime", "模型已加载 " + modelId + "，核心 " + version
                    + "，vision=" + response.optBoolean("vision"));
        } catch (Exception error) {
            loadedModelId = null;
            setState(new RuntimeState(RuntimeState.Phase.ERROR, null, null, modelId, error.getMessage()));
            events.error("runtime", "模型加载失败 " + modelId, error);
            throw asRuntime(error);
        } finally {
            inference.unlock();
        }
    }

    public void unload() {
        inference.lock();
        try {
            nativeRuntime.unloadModel();
            loadedModelId = null;
            setState(RuntimeState.empty());
            events.info("runtime", "模型已卸载");
        } finally {
            inference.unlock();
        }
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer) {
        try (MediaResolver.Prepared prepared = media.prepare(messages)) {
            JSONObject body = new JSONObject(request.toString());
            body.put("messages", prepared.messages);
            body.put("mediaPaths", prepared.paths);
            return runInference("chat", body, consumer);
        } catch (Exception error) {
            throw asRuntime(error);
        }
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer) {
        try {
            JSONObject body = new JSONObject(request.toString());
            body.put("prompt", prompt);
            return runInference("complete", body, consumer);
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

    private Result runInference(String kind, JSONObject body, TokenConsumer consumer) {
        inference.lock();
        try {
            if (loadedModelId == null) throw new IllegalStateException("尚未加载模型");
            RuntimeState before = state();
            setState(new RuntimeState(RuntimeState.Phase.GENERATING,
                    before.coreId, before.coreVersion, loadedModelId, null));
            body.put("type", kind);
            JSONObject response = new JSONObject(nativeRuntime.infer(body.toString(), consumer));
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY,
                    before.coreId, before.coreVersion, loadedModelId, null));
            return new Result(response.getInt("promptTokens"), response.getInt("completionTokens"),
                    response.getString("text"), response.optJSONObject("message"),
                    response.optBoolean("structured"));
        } catch (Exception error) {
            RuntimeState before = state();
            setState(new RuntimeState(RuntimeState.Phase.ERROR, before.coreId, before.coreVersion,
                    before.modelId, error.getMessage()));
            events.error("runtime", "推理失败", error);
            throw asRuntime(error);
        } finally {
            inference.unlock();
        }
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
