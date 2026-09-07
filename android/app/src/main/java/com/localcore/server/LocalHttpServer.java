package com.localcore.server;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.resource.ResourceManager;
import com.localcore.resource.ResourceState;
import com.localcore.runtime.RuntimeManager;
import com.localcore.runtime.RuntimeState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalHttpServer {
    private final ConfigRepository config;
    private final ResourceManager resources;
    private final RuntimeManager runtime;
    private final EventLog events;
    private final ExecutorService connections = Executors.newCachedThreadPool();
    private ServerSocket socket;
    private Thread acceptThread;
    private String address;

    public LocalHttpServer(ConfigRepository config, ResourceManager resources,
                           RuntimeManager runtime, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.runtime = runtime;
        this.events = events;
    }

    public synchronized void start() throws IOException {
        if (socket != null) return;
        JSONObject server = config.current().optJSONObject("server");
        String host = server.optString("host");
        int port = server.optInt("port");
        ServerSocket candidate = new ServerSocket();
        candidate.setReuseAddress(true);
        candidate.bind(new InetSocketAddress(InetAddress.getByName(host), port));
        socket = candidate;
        address = "http://" + host + ":" + port;
        acceptThread = new Thread(this::acceptLoop, "localcore-http-accept");
        acceptThread.start();
        events.info("server", "HTTP 服务已监听 " + address);
    }

    public synchronized void stop() {
        ServerSocket active = socket;
        socket = null;
        address = null;
        if (active != null) {
            try {
                active.close();
            } catch (IOException error) {
                events.error("server", "关闭 HTTP 监听失败", error);
            }
            events.info("server", "HTTP 服务已停止");
        }
    }

    public synchronized boolean isRunning() {
        return socket != null;
    }

    public synchronized String address() {
        return address;
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket active;
            synchronized (this) { active = socket; }
            if (active == null) return;
            try {
                Socket connection = active.accept();
                connections.execute(() -> serve(connection));
            } catch (SocketException error) {
                synchronized (this) {
                    if (socket == null) return;
                }
                events.error("server", "HTTP accept 失败", error);
            } catch (IOException error) {
                events.error("server", "HTTP accept 失败", error);
            }
        }
    }

    private void serve(Socket connection) {
        String peer = String.valueOf(connection.getRemoteSocketAddress());
        try (Socket closeable = connection;
             BufferedInputStream input = new BufferedInputStream(closeable.getInputStream());
             BufferedOutputStream rawOutput = new BufferedOutputStream(closeable.getOutputStream())) {
            closeable.setTcpNoDelay(true);
            HttpOutput output = new HttpOutput(rawOutput);
            try {
                HttpRequest request = HttpRequest.read(input);
                if (request == null) return;
                authorize(request);
                route(request, output);
                events.info("request", request.method + " " + request.target.getPath() + " " + peer);
            } catch (HttpProblem problem) {
                if (!output.headersSent()) output.json(problem.status, HttpOutput.errorBody(problem.type, problem.getMessage()));
                events.error("request", problem.getMessage() + " " + peer, problem);
            } catch (Exception error) {
                if (!output.headersSent()) output.json(500, HttpOutput.errorBody("server_error", error.getMessage()));
                events.error("request", "请求处理失败 " + peer, error);
            }
        } catch (IOException error) {
            events.error("request", "连接读写失败 " + peer, error);
        }
    }

    private void route(HttpRequest request, HttpOutput output) throws IOException {
        String path = request.target.getPath();
        if ("GET".equals(request.method) && "/health".equals(path)) {
            output.json(200, health());
        } else if ("GET".equals(request.method) && "/v1/models".equals(path)) {
            output.json(200, models());
        } else if ("POST".equals(request.method) && "/v1/chat/completions".equals(path)) {
            chat(parseJson(request), output);
        } else if ("POST".equals(request.method) && "/v1/completions".equals(path)) {
            completion(parseJson(request), output);
        } else if ("GET".equals(request.method) || "POST".equals(request.method)) {
            throw new HttpProblem(404, "not_found", "不存在的端点: " + path);
        } else {
            throw new HttpProblem(405, "method_not_allowed", "不支持的方法: " + request.method);
        }
    }

    private void chat(JSONObject request, HttpOutput output) throws IOException {
        String modelId = requiredString(request, "model");
        JSONArray messages = request.optJSONArray("messages");
        if (messages == null) throw new HttpProblem(400, "invalid_request", "messages 必须是数组");
        ensureModel(modelId);
        boolean stream = request.optBoolean("stream", false);
        String completionId = "chatcmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        if (stream) {
            output.startEvents();
            streamRole(output, completionId, created, modelId);
            RuntimeManager.Result result = runtime.chat(messages, request,
                    token -> streamToken(output, completionId, created, modelId, token));
            streamFinish(output, completionId, created, modelId, result);
            output.event("[DONE]");
        } else {
            RuntimeManager.Result result = runtime.chat(messages, request, null);
            output.json(200, chatResult(completionId, created, modelId, result));
        }
    }

    private void completion(JSONObject request, HttpOutput output) throws IOException {
        String modelId = requiredString(request, "model");
        String prompt = requiredString(request, "prompt");
        ensureModel(modelId);
        boolean stream = request.optBoolean("stream", false);
        String completionId = "cmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        if (stream) {
            output.startEvents();
            RuntimeManager.Result result = runtime.complete(prompt, request,
                    token -> output.event(completionChunk(completionId, created, modelId, token, null).toString()));
            output.event(completionChunk(completionId, created, modelId, "", "stop").toString());
            output.event("[DONE]");
        } else {
            RuntimeManager.Result result = runtime.complete(prompt, request, null);
            JSONObject body = base(completionId, "text_completion", created, modelId);
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            put(choice, "index", 0);
            put(choice, "text", result.text);
            put(choice, "finish_reason", "stop");
            choices.put(choice);
            put(body, "choices", choices);
            put(body, "usage", usage(result));
            output.json(200, body);
        }
    }

    private void ensureModel(String id) {
        RuntimeState state = runtime.state();
        if (id.equals(state.modelId) && (state.phase == RuntimeState.Phase.MODEL_READY
                || state.phase == RuntimeState.Phase.GENERATING)) return;
        try {
            runtime.loadModel(id);
        } catch (IllegalStateException error) {
            throw new HttpProblem(409, "model_unavailable", error.getMessage());
        } catch (IllegalArgumentException error) {
            throw new HttpProblem(400, "invalid_model", error.getMessage());
        }
    }

    private JSONObject health() {
        RuntimeState runtimeState = runtime.state();
        JSONObject result = new JSONObject();
        put(result, "status", "ok");
        put(result, "server", address());
        put(result, "runtime", runtimeState.phase.name().toLowerCase(Locale.ROOT));
        put(result, "model", runtimeState.modelId == null ? JSONObject.NULL : runtimeState.modelId);
        put(result, "error", runtimeState.error == null ? JSONObject.NULL : runtimeState.error);
        return result;
    }

    private JSONObject models() {
        JSONObject result = new JSONObject();
        put(result, "object", "list");
        JSONArray data = new JSONArray();
        JSONArray configured = config.current().optJSONArray("models");
        for (int i = 0; i < configured.length(); i++) {
            JSONObject model = configured.optJSONObject(i);
            JSONObject item = new JSONObject();
            put(item, "id", model.optString("id"));
            put(item, "object", "model");
            put(item, "owned_by", "localcore");
            put(item, "name", model.optString("name"));
            ResourceState modelState = resources.state(model.optString("resource"));
            ResourceState coreState = resources.state(model.optString("core"));
            put(item, "ready", modelState.status == ResourceState.Status.INSTALLED
                    && coreState.status == ResourceState.Status.INSTALLED);
            data.put(item);
        }
        put(result, "data", data);
        return result;
    }

    private void authorize(HttpRequest request) {
        String key = config.current().optJSONObject("server").optString("apiKey");
        if (key.isEmpty()) return;
        String actual = request.headers.get("authorization");
        if (!("Bearer " + key).equals(actual)) {
            throw new HttpProblem(401, "unauthorized", "Bearer API key 无效或缺失");
        }
    }

    private static JSONObject parseJson(HttpRequest request) {
        String contentType = request.headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new HttpProblem(400, "invalid_request", "Content-Type 必须是 application/json");
        }
        try {
            return new JSONObject(request.bodyText());
        } catch (JSONException error) {
            throw new HttpProblem(400, "invalid_json", error.getMessage());
        }
    }

    private static String requiredString(JSONObject value, String key) {
        Object item = value.opt(key);
        if (!(item instanceof String) || ((String) item).isEmpty()) {
            throw new HttpProblem(400, "invalid_request", key + " 必须是非空字符串");
        }
        return (String) item;
    }

    private static JSONObject chatResult(String id, long created, String model, RuntimeManager.Result result) {
        JSONObject body = base(id, "chat.completion", created, model);
        JSONObject message = new JSONObject();
        put(message, "role", "assistant");
        put(message, "content", result.text);
        JSONObject choice = new JSONObject();
        put(choice, "index", 0);
        put(choice, "message", message);
        put(choice, "finish_reason", "stop");
        JSONArray choices = new JSONArray();
        choices.put(choice);
        put(body, "choices", choices);
        put(body, "usage", usage(result));
        return body;
    }

    private static void streamRole(HttpOutput output, String id, long created, String model) throws IOException {
        JSONObject delta = new JSONObject();
        put(delta, "role", "assistant");
        output.event(chatChunk(id, created, model, delta, null, null).toString());
    }

    private static boolean streamToken(HttpOutput output, String id, long created, String model, String token)
            throws IOException {
        JSONObject delta = new JSONObject();
        put(delta, "content", token);
        output.event(chatChunk(id, created, model, delta, null, null).toString());
        return true;
    }

    private static void streamFinish(HttpOutput output, String id, long created, String model,
                                     RuntimeManager.Result result) throws IOException {
        output.event(chatChunk(id, created, model, new JSONObject(), "stop", usage(result)).toString());
    }

    private static JSONObject chatChunk(String id, long created, String model, JSONObject delta,
                                        String finish, JSONObject usage) {
        JSONObject body = base(id, "chat.completion.chunk", created, model);
        JSONObject choice = new JSONObject();
        put(choice, "index", 0);
        put(choice, "delta", delta);
        put(choice, "finish_reason", finish == null ? JSONObject.NULL : finish);
        JSONArray choices = new JSONArray();
        choices.put(choice);
        put(body, "choices", choices);
        if (usage != null) put(body, "usage", usage);
        return body;
    }

    private static JSONObject completionChunk(String id, long created, String model, String text, String finish) {
        JSONObject body = base(id, "text_completion", created, model);
        JSONObject choice = new JSONObject();
        put(choice, "index", 0);
        put(choice, "text", text);
        put(choice, "finish_reason", finish == null ? JSONObject.NULL : finish);
        JSONArray choices = new JSONArray();
        choices.put(choice);
        put(body, "choices", choices);
        return body;
    }

    private static JSONObject base(String id, String object, long created, String model) {
        JSONObject value = new JSONObject();
        put(value, "id", id);
        put(value, "object", object);
        put(value, "created", created);
        put(value, "model", model);
        return value;
    }

    private static JSONObject usage(RuntimeManager.Result result) {
        JSONObject value = new JSONObject();
        put(value, "prompt_tokens", result.promptTokens);
        put(value, "completion_tokens", result.completionTokens);
        put(value, "total_tokens", result.promptTokens + result.completionTokens);
        return value;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IllegalStateException("无法编码 JSON 字段 " + key, error);
        }
    }
}

