package com.localcore.runtime;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

final class MediaResolver {
    static final class Prepared implements AutoCloseable {
        final JSONArray messages;
        final JSONArray paths;

        Prepared(JSONArray messages, JSONArray paths) {
            this.messages = messages;
            this.paths = paths;
        }

        @Override
        public void close() {
            for (int i = 0; i < paths.length(); i++) {
                File file = new File(paths.optString(i));
                if (file.isFile() && !file.delete()) file.deleteOnExit();
            }
        }
    }

    private static final String MARKER = "<__media__>";
    private final File cache;

    MediaResolver(Context context) {
        cache = new File(context.getCacheDir(), "request-media");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IllegalStateException("无法创建请求媒体目录: " + cache);
        }
    }

    Prepared prepare(JSONArray input) throws IOException {
        JSONArray messages = new JSONArray(input.toString());
        JSONArray paths = new JSONArray();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                Object content = message.opt("content");
                if (!(content instanceof JSONArray)) continue;
                JSONArray parts = (JSONArray) content;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.getJSONObject(j);
                    if (!"image_url".equals(part.optString("type"))) continue;
                    Object image = part.get("image_url");
                    String address = image instanceof JSONObject
                            ? ((JSONObject) image).getString("url") : String.valueOf(image);
                    File file = File.createTempFile("media-", ".bin", cache);
                    paths.put(file.getAbsolutePath());
                    write(address, file);
                    parts.put(j, new JSONObject().put("type", "media_marker").put("text", MARKER));
                }
            }
            return new Prepared(messages, paths);
        } catch (Exception error) {
            for (int i = 0; i < paths.length(); i++) new File(paths.optString(i)).delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("解析多模态请求失败: " + error.getMessage(), error);
        }
    }

    private static void write(String address, File target) throws IOException {
        try (InputStream input = open(address); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static InputStream open(String address) throws IOException {
        if (address.startsWith("data:")) {
            int comma = address.indexOf(',');
            if (comma < 0) throw new IOException("data URL 缺少数据分隔符");
            String metadata = address.substring(0, comma);
            if (!metadata.endsWith(";base64")) throw new IOException("图片 data URL 必须使用 base64");
            return new java.io.ByteArrayInputStream(Base64.decode(address.substring(comma + 1), Base64.DEFAULT));
        }
        URLConnection connection = new URL(address).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        return new BufferedInputStream(connection.getInputStream());
    }
}
