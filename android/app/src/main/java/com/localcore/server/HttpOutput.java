package com.localcore.server;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class HttpOutput {
    private final OutputStream output;
    private boolean headersSent;

    HttpOutput(OutputStream output) {
        this.output = output;
    }

    void json(int status, JSONObject body) throws IOException {
        byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
        headers(status, "application/json; charset=utf-8", encoded.length);
        output.write(encoded);
        output.flush();
    }

    void startEvents() throws IOException {
        if (headersSent) throw new IllegalStateException("HTTP 响应头已发送");
        String value = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        headersSent = true;
    }

    synchronized void event(String data) throws IOException {
        if (!headersSent) throw new IllegalStateException("SSE 响应尚未开始");
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    boolean headersSent() {
        return headersSent;
    }

    static JSONObject errorBody(String type, String message) {
        JSONObject detail = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            detail.put("type", type);
            detail.put("message", message);
            result.put("error", detail);
            return result;
        } catch (JSONException error) {
            throw new IllegalStateException("无法编码错误响应", error);
        }
    }

    private void headers(int status, String contentType, int contentLength) throws IOException {
        if (headersSent) throw new IllegalStateException("HTTP 响应头已发送");
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 401 ? "Unauthorized" : status == 404 ? "Not Found"
                : status == 405 ? "Method Not Allowed" : status == 409 ? "Conflict"
                : status == 500 ? "Internal Server Error" : status == 501 ? "Not Implemented"
                : "Error";
        String value = String.format(Locale.ROOT,
                "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
                status, reason, contentType, contentLength);
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        headersSent = true;
    }
}

