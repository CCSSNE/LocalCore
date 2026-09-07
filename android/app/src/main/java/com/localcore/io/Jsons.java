package com.localcore.io;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Jsons {
    private Jsons() {}

    public static JSONObject parseObject(String text, String source) {
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            throw new IllegalArgumentException(source + " 不是有效 JSON: " + error.getMessage(), error);
        }
    }

    public static Object parseObjectOrArray(String text, String source) {
        try {
            return new org.json.JSONTokener(text).nextValue();
        } catch (JSONException error) {
            throw new IllegalArgumentException(source + " 不是有效 JSON: " + error.getMessage(), error);
        }
    }

    public static JSONObject readObject(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return parseObject(readUtf8(input), file.getAbsolutePath());
        }
    }

    public static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    public static String format(JSONObject value) {
        try {
            return value.toString(2) + "\n";
        } catch (JSONException error) {
            throw new IllegalStateException("无法格式化 JSON", error);
        }
    }
}

