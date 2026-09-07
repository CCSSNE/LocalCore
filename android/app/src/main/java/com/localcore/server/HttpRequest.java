package com.localcore.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class HttpRequest {
    final String method;
    final URI target;
    final Map<String, String> headers;
    final byte[] body;

    private HttpRequest(String method, URI target, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.target = target;
        this.headers = headers;
        this.body = body;
    }

    static HttpRequest read(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new HttpProblem(400, "invalid_request", "HTTP 请求行无效");
        }
        URI target;
        try {
            target = URI.create(parts[1]);
        } catch (IllegalArgumentException error) {
            throw new HttpProblem(400, "invalid_request", "请求目标无效");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) throw new HttpProblem(400, "invalid_request", "HTTP 请求头无效");
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (headers.put(name, value) != null) {
                throw new HttpProblem(400, "invalid_request", "不支持重复请求头: " + name);
            }
        }
        if (line == null) throw new EOFException("HTTP 请求头未完整结束");
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null) {
            throw new HttpProblem(501, "unsupported_transfer_encoding", "请求体暂不支持 Transfer-Encoding");
        }
        long contentLength = 0;
        String contentLengthHeader = headers.get("content-length");
        if (contentLengthHeader != null) {
            try {
                contentLength = Long.parseLong(contentLengthHeader);
            } catch (NumberFormatException error) {
                throw new HttpProblem(400, "invalid_request", "Content-Length 无效");
            }
            if (contentLength < 0 || contentLength > Integer.MAX_VALUE) {
                throw new HttpProblem(400, "invalid_request", "Content-Length 超出 Android 单个字节数组范围");
            }
        }
        byte[] body = new byte[(int) contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count < 0) throw new EOFException("HTTP 请求体提前结束");
            offset += count;
        }
        return new HttpRequest(parts[0], target, headers, body);
    }

    String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int current;
        boolean sawCarriageReturn = false;
        while ((current = input.read()) != -1) {
            if (sawCarriageReturn) {
                if (current != '\n') throw new HttpProblem(400, "invalid_request", "HTTP 行必须使用 CRLF");
                return output.toString(StandardCharsets.US_ASCII.name());
            }
            if (current == '\r') {
                sawCarriageReturn = true;
            } else {
                output.write(current);
            }
        }
        if (output.size() == 0 && !sawCarriageReturn) return null;
        throw new EOFException("HTTP 行提前结束");
    }
}

