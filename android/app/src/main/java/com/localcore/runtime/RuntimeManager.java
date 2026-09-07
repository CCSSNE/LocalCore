package com.localcore.runtime;

import android.os.Handler;
import android.os.Looper;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.resource.ResourceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 推理桥：把模型加载与推理请求转发给 llama.rn 的 JS 层执行。
 * 全能力（Jinja 模板、工具调用、thinking）由 llama.rn JS 接口层提供。
 * Java 侧只做请求编排与结果回收，不做任何推理逻辑。
 */
public final class RuntimeManager {
    public interface Listener {
        void onRuntimeState(RuntimeState state);
    }

    public interface TokenConsumer {
        boolean onToken(String token) throws IOException;
    }

    public interface Sink {
        void emit(String name, String payload);
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

    public static volatile Sink sink;

    private static final Map<Integer, Reply> replies = new ConcurrentHashMap<>();
    private static final AtomicInteger requestIds = new AtomicInteger();

    private static final class Reply {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject payload;
        volatile String error;
    }

    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;
    private final ReentrantLock inference = new ReentrantLock(true);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RuntimeState state = RuntimeState.empty();
    private String loadedModelId;

    public RuntimeManager(ConfigRepository config, ResourceManager resources, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.events = events;
    }

    public synchronized RuntimeState state() {
        return state;
    }

    public void loadModel(String modelId) {
        inference.lock();
        try {
            JSONObject model = findModel(modelId);
            String coreId = model.optString("core");
            File coreFile = resources.installedFile(coreId);
            File modelFile = resources.installedFile(model.optString("resource"));
            JSONObject coreDescriptor = findResource(coreId);
            JSONObject load = model.optJSONObject("load");
            setState(new RuntimeState(RuntimeState.Phase.MODEL_LOADING, coreId,
                    coreDescriptor.optString("version"), modelId, null));
            JSONObject request = base("loadModel");
            put(request, "corePath", coreFile.getParentFile().getAbsolutePath());
            put(request, "coreEntry", coreFile.getAbsolutePath());
            put(request, "modelPath", modelFile.getAbsolutePath());
            put(request, "contextSize", load.optInt("contextSize"));
            put(request, "batchSize", load.optInt("batchSize"));
            put(request, "threads", load.optInt("threads"));
            put(request, "gpuLayers", load.optInt("gpuLayers"));
            JSONObject response = requestReply(request);
            loadedModelId = modelId;
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY, coreId,
                    response.optString("version"), modelId, null));
            events.info("runtime", "模型已加载 " + modelId + "，核心 " + response.optString("version"));
        } catch (RuntimeException error) {
            loadedModelId = null;
            setState(new RuntimeState(RuntimeState.Phase.ERROR, null, null, modelId, error.getMessage()));
            events.error("runtime", "模型加载失败 " + modelId, error);
            throw error;
        } finally {
            inference.unlock();
        }
    }

    public void unload() {
        inference.lock();
        try {
            JSONObject request = base("unload");
            requestReply(request);
            loadedModelId = null;
            setState(RuntimeState.empty());
            events.info("runtime", "模型和动态核心已卸载");
        } finally {
            inference.unlock();
        }
    }

    public Result chat(JSONArray messages, JSONObject request, TokenConsumer consumer) {
        JSONObject body = new JSONObject();
        put(body, "messages", messages);
        merge(body, request);
        return runInference("chat", body, consumer);
    }

    public Result complete(String prompt, JSONObject request, TokenConsumer consumer) {
        JSONObject body = new JSONObject();
        put(body, "prompt", prompt);
        merge(body, request);
        return runInference("complete", body, consumer);
    }

    public synchronized void cancel() {
        if (loadedModelId == null) return;
        Sink current = sink;
        if (current != null) {
            try {
                current.emit("LocalCoreRuntime", base("cancel").toString());
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static void onReply(int requestId, String payload) {
        Reply reply = replies.get(requestId);
        if (reply == null) return;
        try {
            reply.payload = new JSONObject(payload);
            reply.error = reply.payload.optString("error", null);
        } catch (Exception error) {
            reply.error = "JS 桥响应解析失败: " + error.getMessage();
        }
        reply.latch.countDown();
    }

    private Result runInference(String kind, JSONObject body, TokenConsumer consumer) {
        inference.lock();
        try {
            if (loadedModelId == null) throw new IllegalStateException("尚未加载模型");
            setState(new RuntimeState(RuntimeState.Phase.GENERATING,
                    state.coreId, state.coreVersion, loadedModelId, null));
            JSONObject request = base(kind);
            merge(request, body);
            JSONObject response = requestReply(request);
            String text = response.optString("text");
            if (consumer != null && !text.isEmpty()) consumer.onToken(text);
            setState(new RuntimeState(RuntimeState.Phase.MODEL_READY,
                    state.coreId, state.coreVersion, loadedModelId, null));
            return new Result(response.optInt("promptTokens"), response.optInt("completionTokens"),
                    text, response.optJSONObject("message"), response.has("message"));
        } catch (IOException error) {
            throw new IllegalStateException("流式响应写入失败", error);
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

    private JSONObject requestReply(JSONObject request) {
        Sink current = sink;
        if (current == null) {
            throw new IllegalStateException("React Native 运行时尚未就绪，无法执行推理请求");
        }
        int requestId = request.optInt("requestId");
        Reply reply = new Reply();
        replies.put(requestId, reply);
        mainHandler.execute(() -> {
            try {
                current.emit("LocalCoreRuntime", request.toString());
            } catch (RuntimeException error) {
                reply.error = "JS 桥事件发送失败: " + error.getMessage();
                reply.latch.countDown();
            }
        });
        try {
            if (!reply.latch.await(30, TimeUnit.MINUTES)) {
                throw new IllegalStateException("推理请求超时未返回");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("推理请求被中断", error);
        } finally {
            replies.remove(requestId);
        }
        if (reply.error != null) throw new IllegalStateException(reply.error);
        return reply.payload;
    }

    private JSONObject base(String type) {
        JSONObject request = new JSONObject();
        put(request, "requestId", requestIds.incrementAndGet());
        put(request, "type", type);
        return request;
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

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException("无法写入桥请求字段 " + key, error);
        }
    }

    private static void merge(JSONObject target, JSONObject source) {
        if (source == null) return;
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            put(target, key, source.opt(key));
        }
    }
}
