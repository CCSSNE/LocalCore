package com.localcore.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Shared validation for the user-owned extra inference settings object. */
public final class HotSettings {
    public static final String CONFIG_KEY = "hotSettings";
    private static final Set<String> DEFAULT_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "max_tokens", "maxTokens", "temperature", "top_p", "topP", "top_k", "topK", "seed", "stop")));

    private HotSettings() {
    }

    public static JSONObject parse(String text) {
        if (text == null) throw new IllegalArgumentException("额外热设置不能为空");
        try {
            JSONObject value = new JSONObject(text);
            validate(value);
            return value;
        } catch (JSONException error) {
            throw new IllegalArgumentException("额外热设置不是合法 JSON: " + error.getMessage(), error);
        }
    }

    public static void validateConfig(JSONObject config) {
        if (!config.has(CONFIG_KEY)) return;
        Object value = config.opt(CONFIG_KEY);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException("配置 hotSettings 必须是 JSON 对象");
        }
        validate((JSONObject) value);
    }

    public static void validate(JSONObject value) {
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (DEFAULT_KEYS.contains(key)) {
                throw new IllegalArgumentException("额外热设置不能重复定义默认参数: " + key);
            }
        }
    }

    public static JSONObject fromConfig(JSONObject config) {
        if (!config.has(CONFIG_KEY)) return new JSONObject();
        Object value = config.opt(CONFIG_KEY);
        if (!(value instanceof JSONObject)) {
            throw new IllegalStateException("配置 hotSettings 必须是 JSON 对象");
        }
        return (JSONObject) value;
    }
}
