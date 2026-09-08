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
        long t0 = System.currentTimeMillis();
        if (socket != null) {
            events.info("server", "start跳过: 已在监听 " + address);
            return;
        }
        events.info("server", "开始监听: 读取配置");
        JSONObject server = config.current().optJSONObject("server");
        String host = server.optString("host");
        int port = server.optInt("port");
        events.info("server", "监听目标 host=" + host + " port=" + port
                + " elapsedMs=" + (System.currentTimeMillis() - t0));
        long dns0 = System.currentTimeMillis();
        InetAddress resolved = InetAddress.getByName(host);
        events.info("server", "地址解析完成 resolved=" + resolved
                + " dnsMs=" + (System.currentTimeMillis() - dns0));
        ServerSocket candidate = new ServerSocket();
        candidate.setReuseAddress(true);
        long bind0 = System.currentTimeMillis();
        candidate.bind(new InetSocketAddress(resolved, port));
        long bindMs = System.currentTimeMillis() - bind0;
        socket = candidate;
        address = "http://" + host + ":" + port;
        acceptThread = new Thread(this::acceptLoop, "localcore-http-accept");
        acceptThread.start();
        events.info("server", "HTTP 服务已监听 " + address
                + " totalElapsedMs=" + (System.currentTimeMillis() - t0) + " bindMs=" + bindMs);
    }

    public synchronized void stop() {
        long t0 = System.currentTimeMillis();
        events.info("server", "停止监听开始 address=" + address);
        ServerSocket active = socket;
        socket = null;
        address = null;
        if (active != null) {
            try {
                active.close();
            } catch (IOException error) {
                events.error("server", "关闭 HTTP 监听失败", error);
            }
            events.info("server", "HTTP 服务已停止 elapsedMs=" + (System.currentTimeMillis() - t0));
        } else {
            events.info("server", "停止监听跳过: 本来就没在监听 elapsedMs=" + (System.currentTimeMillis() - t0));
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
            String requestId = "req-" + UUID.randomUUID().toString().substring(0, 8);
            long startedAt = System.currentTimeMillis();
            HttpRequest request = null;
            try {
                request = HttpRequest.read(input);
                if (request == null) return;
                // 全量请求日志：方法 + 完整目标（含查询串）+ 对端 + 全量头（鉴权头脱敏）+ 全量体。
                // 不截断：自用开发版，完整请求必须落盘，便于与前端日志逐字对照。
                events.info("request", "--> " + requestId + " " + request.method + " " + request.target
                        + " from " + peer + " headers=" + maskedHeaders(request) + " body=" + request.bodyText());
                authorize(request, requestId);
                route(request, output, requestId, startedAt);
            } catch (HttpProblem problem) {
                if (!output.headersSent()) output.json(problem.status, HttpOutput.errorBody(problem.type, problem.getMessage()));
                long elapsed = System.currentTimeMillis() - startedAt;
                String where = request == null ? peer : request.method + " " + request.target + " " + peer;
                events.error("request", "<-- " + requestId + " status=" + problem.status + " elapsedMs=" + elapsed
                        + " " + where + " error=" + problem.type + ":" + problem.getMessage(), problem);
            } catch (Exception error) {
                if (!output.headersSent()) output.json(500, HttpOutput.errorBody("server_error", error.getMessage()));
                long elapsed = System.currentTimeMillis() - startedAt;
                String where = request == null ? peer : request.method + " " + request.target + " " + peer;
                events.error("request", "<-- " + requestId + " status=500 elapsedMs=" + elapsed
                        + " " + where + " 请求处理失败", error);
            }
        } catch (IOException error) {
            events.error("request", "连接读写失败 " + peer, error);
        }
    }

    private void route(HttpRequest request, HttpOutput output, String requestId, long startedAt) throws IOException {
        String path = request.target.getPath();
        JSONObject routes = config.current().optJSONObject("protocol").optJSONObject("routes");
        if ("GET".equals(request.method) && routes.optString("health").equals(path)) {
            JSONObject body = health();
            output.json(200, body);
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " GET " + path + " body=" + body);
        } else if ("GET".equals(request.method) && routes.optString("models").equals(path)) {
            JSONObject body = models();
            output.json(200, body);
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " GET " + path + " body=" + body);
        } else if ("POST".equals(request.method) && routes.optString("chatCompletions").equals(path)) {
            chat(parseJson(request), output, requestId, startedAt);
        } else if ("POST".equals(request.method) && routes.optString("completions").equals(path)) {
            completion(parseJson(request), output, requestId, startedAt);
        } else if ("GET".equals(request.method) || "POST".equals(request.method)) {
            throw new HttpProblem(404, "not_found", "不存在的端点: " + path);
        } else {
            throw new HttpProblem(405, "method_not_allowed", "不支持的方法: " + request.method);
        }
    }

    private void chat(JSONObject request, HttpOutput output, String requestId, long startedAt) throws IOException {
        String modelId = requiredString(request, "model");
        JSONArray messages = request.optJSONArray("messages");
        if (messages == null) throw new HttpProblem(400, "invalid_request", "messages 必须是数组");
        put(request, "_requestId", requestId);
        boolean stream = request.optBoolean("stream", false);
        // 语义日志：模型 + 流式与否 + 全量 OpenAI 请求体，前端对照时只看这一行就知道输入了什么。
        events.info("request", requestId + " chat model=" + modelId + " stream=" + stream
                + " messages=" + messages + " body=" + request);
        String completionId = "chatcmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        if (stream) {
            output.startEvents();
            streamRole(output, completionId, created, modelId);
            RuntimeManager.Result result = runtime.chat(messages, request,
                    token -> streamToken(output, completionId, created, modelId, token));
            if (result.structured) streamMessage(output, completionId, created, modelId, result.message);
            streamFinish(output, completionId, created, modelId, result, finishReason(result.message));
            output.event("[DONE]");
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " chat model=" + modelId + " stream=true"
                    + " promptTokens=" + result.promptTokens + " completionTokens=" + result.completionTokens
                    + " ttftMs=" + result.ttftMs + " llmMs=" + result.llmMs
                    + " message=" + result.message + " text=" + result.text);
        } else {
            RuntimeManager.Result result = runtime.chat(messages, request, null);
            JSONObject body = chatResult(completionId, created, modelId, result);
            output.json(200, body);
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " chat model=" + modelId + " stream=false"
                    + " promptTokens=" + result.promptTokens + " completionTokens=" + result.completionTokens
                    + " ttftMs=" + result.ttftMs + " llmMs=" + result.llmMs + " body=" + body);
        }
    }

    private void completion(JSONObject request, HttpOutput output, String requestId, long startedAt) throws IOException {
        String modelId = requiredString(request, "model");
        String prompt = requiredString(request, "prompt");
        put(request, "_requestId", requestId);
        boolean stream = request.optBoolean("stream", false);
        events.info("request", requestId + " completion model=" + modelId + " stream=" + stream
                + " prompt=" + prompt + " body=" + request);
        String completionId = "cmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        if (stream) {
            output.startEvents();
            RuntimeManager.Result result = runtime.complete(prompt, request,
                    token -> {
                        output.event(completionChunk(completionId, created, modelId, token, null).toString());
                        return true;
                    });
            output.event(completionChunk(completionId, created, modelId, "", "stop").toString());
            output.event("[DONE]");
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " completion model=" + modelId + " stream=true"
                    + " promptTokens=" + result.promptTokens + " completionTokens=" + result.completionTokens
                    + " ttftMs=" + result.ttftMs + " llmMs=" + result.llmMs + " text=" + result.text);
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
            events.info("request", "<-- " + requestId + " status=200 elapsedMs="
                    + (System.currentTimeMillis() - startedAt) + " completion model=" + modelId + " stream=false"
                    + " promptTokens=" + result.promptTokens + " completionTokens=" + result.completionTokens
                    + " ttftMs=" + result.ttftMs + " llmMs=" + result.llmMs + " body=" + body);
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
            put(item, "ready", modelState.usable() && coreState.usable());
            data.put(item);
        }
        put(result, "data", data);
        return result;
    }

    private void authorize(HttpRequest request, String requestId) {
        JSONObject server = config.current().optJSONObject("server");
        String key = server == null ? "" : server.optString("apiKey");
        if (key == null || key.isEmpty()) {
            events.info("request", requestId + " auth=disabled（未配置 API Key，直接放行）");
            return;
        }
        String actual = request.headers.get("authorization");
        if (actual == null || actual.isEmpty()) {
            events.info("request", requestId + " auth=fail（缺少 Authorization 头）");
            throw new HttpProblem(401, "unauthorized", "Bearer API key 无效或缺失");
        }
        if (!("Bearer " + key).equals(actual)) {
            events.info("request", requestId + " auth=fail（Bearer 不匹配，期望长度=" + key.length()
                    + " 实际长度=" + actual.length() + "）");
            throw new HttpProblem(401, "unauthorized", "Bearer API key 无效或缺失");
        }
        events.info("request", requestId + " auth=ok（Bearer 校验通过）");
    }

    private static String maskedHeaders(HttpRequest request) {
        StringBuilder masked = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> header : request.headers.entrySet()) {
            if (!first) masked.append(", ");
            first = false;
            if ("authorization".equals(header.getKey())) {
                String value = header.getValue();
                if (value.regionMatches(true, 0, "Bearer ", 0, 7)) masked.append("authorization=Bearer ***");
                else masked.append("authorization=***");
            } else {
                masked.append(header.getKey()).append('=').append(header.getValue());
            }
        }
        return masked.append('}').toString();
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
        JSONObject message = result.message == null ? new JSONObject() : result.message;
        if (!message.has("role")) put(message, "role", "assistant");
        if (!message.has("content")) put(message, "content", JSONObject.NULL);
        JSONObject choice = new JSONObject();
        put(choice, "index", 0);
        put(choice, "message", message);
        put(choice, "finish_reason", finishReason(message));
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

    private static void streamMessage(HttpOutput output, String id, long created, String model,
                                      JSONObject message) throws IOException {
        JSONObject delta = new JSONObject();
        java.util.Iterator<String> keys = message.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"role".equals(key) && !"content".equals(key)) put(delta, key, message.opt(key));
        }
        if (delta.length() > 0) output.event(chatChunk(id, created, model, delta, null, null).toString());
    }

    private static void streamFinish(HttpOutput output, String id, long created, String model,
                                     RuntimeManager.Result result, String reason) throws IOException {
        output.event(chatChunk(id, created, model, new JSONObject(), reason, usage(result)).toString());
    }

    private static String finishReason(JSONObject message) {
        return message != null && message.optJSONArray("tool_calls") != null
                && message.optJSONArray("tool_calls").length() > 0 ? "tool_calls" : "stop";
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
