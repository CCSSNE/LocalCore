package com.localcore.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigValidator {
    public static final int SCHEMA_VERSION = 1;
    public static final int HOST_API = 1;
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public void validate(JSONObject root) {
        exactKeys(root, "$", set("schemaVersion", "hostApi", "server", "resources", "models"));
        requireInt(root, "schemaVersion", "$", SCHEMA_VERSION, SCHEMA_VERSION);
        requireInt(root, "hostApi", "$", HOST_API, HOST_API);
        validateServer(requireObject(root, "server", "$"));

        JSONArray resources = requireArray(root, "resources", "$" );
        Map<String, String> resourceTypes = new HashMap<>();
        for (int index = 0; index < resources.length(); index++) {
            JSONObject resource = requireObject(resources, index, "$.resources");
            String itemPath = "$.resources[" + index + "]";
            String id = validateResource(resource, itemPath);
            String old = resourceTypes.put(id, requireString(resource, "type", itemPath));
            if (old != null) {
                fail("$.resources[" + index + "].id", "资源 id 重复: " + id);
            }
        }

        JSONArray models = requireArray(root, "models", "$" );
        Set<String> modelIds = new HashSet<>();
        for (int index = 0; index < models.length(); index++) {
            validateModel(requireObject(models, index, "$.models"), "$.models[" + index + "]",
                    resourceTypes, modelIds);
        }
    }

    private void validateServer(JSONObject server) {
        exactKeys(server, "$.server", set("host", "port", "autoStart", "apiKey"));
        String host = requireString(server, "host", "$.server");
        if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host))) {
            fail("$.server.host", "只允许 127.0.0.1 或 0.0.0.0");
        }
        requireInt(server, "port", "$.server", 1, 65535);
        requireBoolean(server, "autoStart", "$.server");
        optionalString(server, "apiKey", "$.server");
    }

    private String validateResource(JSONObject resource, String path) {
        exactKeys(resource, path, set("id", "type", "version", "url", "sha256", "size", "entry", "variants"));
        String id = requireString(resource, "id", path);
        if (!ID.matcher(id).matches()) {
            fail(path + ".id", "必须匹配 " + ID.pattern());
        }
        String type = requireString(resource, "type", path);
        if (!set("core", "model", "template", "config").contains(type)) {
            fail(path + ".type", "未知资源类型: " + type);
        }
        requireNonEmpty(resource, "version", path);
        if ("core".equals(type)) {
            if (resource.has("url") || resource.has("sha256") || resource.has("size") || resource.has("entry")) {
                fail(path, "核心下载信息必须按 ABI 放入 variants");
            }
            validateVariants(requireObject(resource, "variants", path), path + ".variants");
        } else {
            if (resource.has("variants")) {
                fail(path + ".variants", "variants 只适用于核心资源");
            }
            validateDownload(resource, path, false);
        }
        return id;
    }

    private void validateVariants(JSONObject variants, String path) {
        if (variants.length() == 0) fail(path, "至少需要一个 ABI 变体");
        Iterator<String> keys = variants.keys();
        while (keys.hasNext()) {
            String abi = keys.next();
            if (!("arm64-v8a".equals(abi) || "x86_64".equals(abi))) {
                fail(path + "." + abi, "不支持的 ABI");
            }
            Object value = variants.opt(abi);
            if (!(value instanceof JSONObject)) fail(path + "." + abi, "必须是对象");
            JSONObject variant = (JSONObject) value;
            exactKeys(variant, path + "." + abi, set("url", "sha256", "size", "entry"));
            validateDownload(variant, path + "." + abi, true);
        }
    }

    private void validateDownload(JSONObject resource, String path, boolean core) {
        String url = requireNonEmpty(resource, "url", path);
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!("https".equals(scheme) || "http".equals(scheme))) {
                fail(path + ".url", "只支持 https 或 http URL");
            }
        } catch (URISyntaxException error) {
            fail(path + ".url", "URL 非法: " + error.getMessage());
        }
        String digest = requireString(resource, "sha256", path);
        if (!SHA256.matcher(digest).matches()) {
            fail(path + ".sha256", "必须是 64 位小写 SHA-256");
        }
        requireLong(resource, "size", path, 1, Long.MAX_VALUE);
        String entry = optionalString(resource, "entry", path);
        if (core) {
            if (entry == null || !entry.endsWith(".so") || entry.contains("..") || entry.startsWith("/")) {
                fail(path + ".entry", "核心入口必须是包内相对 .so 路径");
            }
        } else if (entry != null) {
            fail(path + ".entry", "entry 只适用于核心资源");
        }
    }

    private void validateModel(JSONObject model, String path, Map<String, String> types, Set<String> ids) {
        exactKeys(model, path, set("id", "name", "resource", "core", "template", "load", "inference", "capabilities"));
        String id = requireNonEmpty(model, "id", path);
        if (!ID.matcher(id).matches() || !ids.add(id)) {
            fail(path + ".id", "模型 id 非法或重复: " + id);
        }
        requireNonEmpty(model, "name", path);
        requireReference(model, "resource", path, types, "model");
        requireReference(model, "core", path, types, "core");
        validateTemplate(requireObject(model, "template", path), path + ".template", types);
        validateLoad(requireObject(model, "load", path), path + ".load");
        validateInference(requireObject(model, "inference", path), path + ".inference");
        if (model.has("capabilities")) {
            JSONArray capabilities = requireArray(model, "capabilities", path);
            for (int i = 0; i < capabilities.length(); i++) {
                if (!(capabilities.opt(i) instanceof String) || ((String) capabilities.opt(i)).isEmpty()) {
                    fail(path + ".capabilities[" + i + "]", "必须是非空字符串");
                }
            }
        }
    }

    private void validateTemplate(JSONObject template, String path, Map<String, String> types) {
        exactKeys(template, path, set("mode", "resource"));
        String mode = requireString(template, "mode", path);
        if ("embedded".equals(mode)) {
            if (template.has("resource")) {
                fail(path + ".resource", "使用内嵌模板时不能指定资源");
            }
        } else if ("resource".equals(mode)) {
            requireReference(template, "resource", path, types, "template");
        } else {
            fail(path + ".mode", "只支持 embedded 或 resource");
        }
    }

    private void validateLoad(JSONObject load, String path) {
        exactKeys(load, path, set("contextSize", "batchSize", "threads", "gpuLayers"));
        requireInt(load, "contextSize", path, 128, Integer.MAX_VALUE);
        requireInt(load, "batchSize", path, 1, Integer.MAX_VALUE);
        requireInt(load, "threads", path, 1, Integer.MAX_VALUE);
        requireInt(load, "gpuLayers", path, -1, Integer.MAX_VALUE);
    }

    private void validateInference(JSONObject inference, String path) {
        exactKeys(inference, path, set("maxTokens", "temperature", "topP", "topK", "seed", "stop"));
        requireInt(inference, "maxTokens", path, 1, Integer.MAX_VALUE);
        requireDouble(inference, "temperature", path, 0, Double.MAX_VALUE);
        requireDouble(inference, "topP", path, 0, 1);
        requireInt(inference, "topK", path, 0, Integer.MAX_VALUE);
        requireLong(inference, "seed", path, -1, 0xffffffffL);
        JSONArray stop = requireArray(inference, "stop", path);
        for (int i = 0; i < stop.length(); i++) {
            if (!(stop.opt(i) instanceof String) || ((String) stop.opt(i)).isEmpty()) {
                fail(path + ".stop[" + i + "]", "停止词必须是非空字符串");
            }
        }
    }

    private static void requireReference(JSONObject object, String key, String path,
                                         Map<String, String> types, String expected) {
        String id = requireNonEmpty(object, key, path);
        String actual = types.get(id);
        if (!expected.equals(actual)) {
            fail(path + "." + key, actual == null ? "引用不存在: " + id
                    : "引用类型应为 " + expected + "，实际为 " + actual);
        }
    }

    private static JSONObject requireObject(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof JSONObject)) fail(path + "." + key, "必须是对象");
        return (JSONObject) value;
    }

    private static JSONObject requireObject(JSONArray array, int index, String path) {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) fail(path + "[" + index + "]", "必须是对象");
        return (JSONObject) value;
    }

    private static JSONArray requireArray(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) fail(path + "." + key, "必须是数组");
        return (JSONArray) value;
    }

    private static String requireNonEmpty(JSONObject object, String key, String path) {
        String value = requireString(object, key, path);
        if (value.isEmpty()) fail(path + "." + key, "不能为空");
        return value;
    }

    private static String requireString(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof String)) fail(path + "." + key, "必须是字符串");
        return (String) value;
    }

    private static String optionalString(JSONObject object, String key, String path) {
        if (!object.has(key)) return null;
        return requireString(object, key, path);
    }

    private static void requireBoolean(JSONObject object, String key, String path) {
        if (!(object.opt(key) instanceof Boolean)) fail(path + "." + key, "必须是布尔值");
    }

    private static int requireInt(JSONObject object, String key, String path, int min, int max) {
        Object value = object.opt(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() % 1 != 0) {
            fail(path + "." + key, "必须是整数");
        }
        long number = ((Number) value).longValue();
        if (number < min || number > max) fail(path + "." + key, "超出范围 " + min + ".." + max);
        return (int) number;
    }

    private static long requireLong(JSONObject object, String key, String path, long min, long max) {
        Object value = object.opt(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() % 1 != 0) {
            fail(path + "." + key, "必须是整数");
        }
        long number = ((Number) value).longValue();
        if (number < min || number > max) fail(path + "." + key, "超出范围");
        return number;
    }

    private static void requireDouble(JSONObject object, String key, String path, double min, double max) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) fail(path + "." + key, "必须是数字");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) fail(path + "." + key, "超出范围");
    }

    private static void exactKeys(JSONObject object, String path, Set<String> allowed) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) fail(path + "." + key, "未知字段");
        }
        for (String required : allowed) {
            // Required fields are checked by typed accessors; optional fields remain optional.
        }
    }

    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>();
        java.util.Collections.addAll(result, values);
        return result;
    }

    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }
}
