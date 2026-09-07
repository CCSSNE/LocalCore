package com.localcore.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigValidator {
    public static final int SCHEMA_VERSION = 2;
    public static final int HOST_API = 2;
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public JSONObject normalizeAndValidate(JSONObject source) {
        JSONObject root = copy(source);
        if (root.optInt("schemaVersion", -1) == 1) root = migrateV1(root);
        validate(root);
        return root;
    }

    public void validate(JSONObject root) {
        exact(root, "$", set("schemaVersion", "hostApi", "server", "protocol", "updates",
                "diagnostics", "management", "resources", "models"));
        integer(root, "schemaVersion", "$", SCHEMA_VERSION, SCHEMA_VERSION);
        integer(root, "hostApi", "$", HOST_API, HOST_API);
        validateServer(object(root, "server", "$"));
        validateProtocol(object(root, "protocol", "$"));
        validateUpdates(object(root, "updates", "$"));
        validateDiagnostics(object(root, "diagnostics", "$"));
        validateManagement(root);

        JSONArray resourceArray = array(root, "resources", "$");
        Map<String, JSONObject> resources = new HashMap<>();
        for (int i = 0; i < resourceArray.length(); i++) {
            String path = "$.resources[" + i + "]";
            JSONObject resource = object(resourceArray, i, "$.resources");
            String id = validateResource(resource, path);
            if (resources.put(id, resource) != null) fail(path + ".id", "资源 id 重复: " + id);
        }
        JSONArray models = array(root, "models", "$");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < models.length(); i++) {
            validateModel(object(models, i, "$.models"), "$.models[" + i + "]", resources, ids);
        }
    }

    public void validateUpdateManifest(JSONObject manifest) {
        exact(manifest, "$manifest", set("manifestVersion", "configuration"));
        integer(manifest, "manifestVersion", "$manifest", 1, 1);
        JSONObject descriptor = object(manifest, "configuration", "$manifest");
        validateResource(descriptor, "$manifest.configuration");
        if (!"config".equals(descriptor.optString("type")) || !descriptor.optBoolean("autoActivate")) {
            fail("$manifest.configuration", "必须是 autoActivate=true 的 config 资源");
        }
    }

    private void validateServer(JSONObject value) {
        exact(value, "$.server", set("host", "port", "autoStart", "apiKey"));
        String host = string(value, "host", "$.server");
        if (!("127.0.0.1".equals(host) || "0.0.0.0".equals(host))) fail("$.server.host", "只允许 127.0.0.1 或 0.0.0.0");
        integer(value, "port", "$.server", 1, 65535);
        bool(value, "autoStart", "$.server");
        optionalString(value, "apiKey", "$.server");
    }

    private void validateProtocol(JSONObject value) {
        exact(value, "$.protocol", set("routes"));
        JSONObject routes = object(value, "routes", "$.protocol");
        exact(routes, "$.protocol.routes", set("health", "models", "chatCompletions", "completions"));
        Set<String> paths = new HashSet<>();
        for (String key : set("health", "models", "chatCompletions", "completions")) {
            String route = nonEmpty(routes, key, "$.protocol.routes");
            if (!route.startsWith("/") || route.contains("?") || route.contains("#") || !paths.add(route)) {
                fail("$.protocol.routes." + key, "必须是唯一的绝对 HTTP 路径");
            }
        }
    }

    private void validateUpdates(JSONObject value) {
        exact(value, "$.updates", set("enabled", "manifestUrl", "checkIntervalMinutes", "autoActivate", "autoInstall"));
        boolean enabled = bool(value, "enabled", "$.updates");
        String url = string(value, "manifestUrl", "$.updates");
        if (enabled && url.isEmpty()) fail("$.updates.manifestUrl", "启用更新时不能为空");
        if (!url.isEmpty()) url(url, "$.updates.manifestUrl");
        integer(value, "checkIntervalMinutes", "$.updates", 15, 525600);
        bool(value, "autoActivate", "$.updates");
        bool(value, "autoInstall", "$.updates");
    }

    private void validateDiagnostics(JSONObject value) {
        exact(value, "$.diagnostics", set("minimumLevel", "logcat", "maxFileBytes", "retainedFiles"));
        if (!set("debug", "info", "error").contains(string(value, "minimumLevel", "$.diagnostics"))) {
            fail("$.diagnostics.minimumLevel", "未知日志级别");
        }
        bool(value, "logcat", "$.diagnostics");
        number(value, "maxFileBytes", "$.diagnostics", 65536, Long.MAX_VALUE);
        integer(value, "retainedFiles", "$.diagnostics", 1, 100);
    }

    private void validateManagement(JSONObject root) {
        JSONObject value = object(root, "management", "$");
        exact(value, "$.management", set("sections"));
        JSONArray sections = array(value, "sections", "$.management");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < sections.length(); i++) {
            String path = "$.management.sections[" + i + "]";
            JSONObject section = object(sections, i, "$.management.sections");
            exact(section, path, set("id", "title", "fields"));
            String id = nonEmpty(section, "id", path);
            if (!ID.matcher(id).matches() || !ids.add(id)) fail(path + ".id", "分区 id 非法或重复");
            nonEmpty(section, "title", path);
            JSONArray fields = array(section, "fields", path);
            for (int j = 0; j < fields.length(); j++) {
                validateField(root, object(fields, j, path + ".fields"), path + ".fields[" + j + "]");
            }
        }
    }

    private void validateField(JSONObject root, JSONObject value, String path) {
        exact(value, path, set("path", "label", "control", "options"));
        String pointer = nonEmpty(value, "path", path);
        if (!pointer.startsWith("/") || pointer.endsWith("/") || pointer.contains("//")) fail(path + ".path", "必须是 JSON Pointer");
        nonEmpty(value, "label", path);
        String control = string(value, "control", path);
        if (!set("text", "number", "toggle", "select").contains(control)) fail(path + ".control", "未知控件类型");
        if ("select".equals(control)) {
            JSONArray options = array(value, "options", path);
            if (options.length() == 0) fail(path + ".options", "至少需要一个选项");
            for (int i = 0; i < options.length(); i++) {
                Object item = options.opt(i);
                if (!(item instanceof String) && !(item instanceof Number)) fail(path + ".options[" + i + "]", "只支持字符串或数字");
            }
            Object current = resolvePointer(root, pointer, path + ".path");
            boolean matched = false;
            for (int i = 0; i < options.length(); i++) {
                if (String.valueOf(options.opt(i)).equals(String.valueOf(current))) matched = true;
            }
            if (!matched) fail(path + ".path", "当前值不在选项中: " + current);
        } else {
            if (value.has("options")) fail(path + ".options", "只适用于 select");
            Object current = resolvePointer(root, pointer, path + ".path");
            if ("toggle".equals(control) && !(current instanceof Boolean)) fail(path + ".path", "toggle 控件必须指向布尔值");
            if ("number".equals(control) && !(current instanceof Number)) fail(path + ".path", "number 控件必须指向数字");
            if ("text".equals(control) && !(current instanceof String)) fail(path + ".path", "text 控件必须指向字符串");
        }
    }

    private static Object resolvePointer(JSONObject root, String pointer, String path) {
        Object cursor = root;
        for (String encoded : pointer.substring(1).split("/")) {
            String key = encoded.replace("~1", "/").replace("~0", "~");
            if (cursor instanceof JSONObject) {
                JSONObject container = (JSONObject) cursor;
                if (!container.has(key)) fail(path, "指向不存在的配置位置: " + pointer);
                cursor = container.opt(key);
            } else if (cursor instanceof JSONArray) {
                JSONArray container = (JSONArray) cursor;
                final int index;
                try { index = Integer.parseInt(key); }
                catch (NumberFormatException error) { fail(path, "数组下标非法: " + key); return null; }
                if (index < 0 || index >= container.length()) fail(path, "数组下标越界: " + pointer);
                cursor = container.opt(index);
            } else {
                fail(path, "穿过非容器值: " + pointer);
            }
        }
        return cursor;
    }

    private String validateResource(JSONObject value, String path) {
        exact(value, path, set("id", "type", "version", "url", "sha256", "size", "entry", "variants", "runtimeApi", "autoActivate"));
        String id = string(value, "id", path);
        if (!ID.matcher(id).matches()) fail(path + ".id", "格式非法");
        String type = string(value, "type", path);
        if (!set("core", "model", "template", "config").contains(type)) fail(path + ".type", "未知资源类型");
        nonEmpty(value, "version", path);
        if ("core".equals(type)) {
            if (value.has("url") || value.has("sha256") || value.has("size") || value.has("entry")) fail(path, "核心下载信息必须放入 variants");
            integer(value, "runtimeApi", path, 1, 2);
            if (value.has("autoActivate")) fail(path + ".autoActivate", "只适用于配置资源");
            validateVariants(object(value, "variants", path), path + ".variants");
        } else {
            if (value.has("variants") || value.has("runtimeApi")) fail(path, "variants/runtimeApi 只适用于核心");
            validateDownload(value, path, false);
            if ("config".equals(type)) bool(value, "autoActivate", path);
            else if (value.has("autoActivate")) fail(path + ".autoActivate", "只适用于配置资源");
        }
        return id;
    }

    private void validateVariants(JSONObject variants, String path) {
        if (variants.length() == 0) fail(path, "至少需要一个 ABI");
        Iterator<String> keys = variants.keys();
        while (keys.hasNext()) {
            String abi = keys.next();
            if (!("arm64-v8a".equals(abi) || "x86_64".equals(abi))) fail(path + "." + abi, "不支持的 ABI");
            JSONObject variant = variants.optJSONObject(abi);
            if (variant == null) fail(path + "." + abi, "必须是对象");
            exact(variant, path + "." + abi, set("url", "sha256", "size", "entry"));
            validateDownload(variant, path + "." + abi, true);
        }
    }

    private void validateDownload(JSONObject value, String path, boolean core) {
        url(nonEmpty(value, "url", path), path + ".url");
        if (!SHA256.matcher(string(value, "sha256", path)).matches()) fail(path + ".sha256", "必须是 64 位小写 SHA-256");
        number(value, "size", path, 1, Long.MAX_VALUE);
        String entry = optionalString(value, "entry", path);
        if (core && (entry == null || !entry.endsWith(".so") || entry.contains("..") || entry.startsWith("/"))) {
            fail(path + ".entry", "必须是包内相对 .so 路径");
        }
        if (!core && entry != null) fail(path + ".entry", "只适用于核心");
    }

    private void validateModel(JSONObject value, String path, Map<String, JSONObject> resources, Set<String> ids) {
        exact(value, path, set("id", "name", "resource", "core", "template", "load", "inference", "thinking",
                "toolCalling", "requirements", "capabilities"));
        String id = nonEmpty(value, "id", path);
        if (!ID.matcher(id).matches() || !ids.add(id)) fail(path + ".id", "非法或重复");
        nonEmpty(value, "name", path);
        reference(value, "resource", path, resources, "model");
        JSONObject core = reference(value, "core", path, resources, "core");
        validateTemplate(object(value, "template", path), path + ".template", resources);
        validateLoad(object(value, "load", path), path + ".load");
        validateInference(object(value, "inference", path), path + ".inference");
        validateThinking(object(value, "thinking", path), path + ".thinking");
        validateTools(object(value, "toolCalling", path), path + ".toolCalling");
        validateRequirements(object(value, "requirements", path), path + ".requirements", core);
        JSONArray capabilities = array(value, "capabilities", path);
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < capabilities.length(); i++) {
            Object item = capabilities.opt(i);
            if (!(item instanceof String) || !set("text", "streaming", "jinja", "thinking", "tools").contains(item) || !declared.add((String) item)) {
                fail(path + ".capabilities[" + i + "]", "能力未知或重复");
            }
        }
        if (value.optJSONObject("thinking").optBoolean("enabled") && !declared.contains("thinking")) fail(path + ".capabilities", "缺少 thinking");
        if (value.optJSONObject("toolCalling").optBoolean("enabled") && !declared.contains("tools")) fail(path + ".capabilities", "缺少 tools");
    }

    private void validateTemplate(JSONObject value, String path, Map<String, JSONObject> resources) {
        exact(value, path, set("mode", "resource"));
        String mode = string(value, "mode", path);
        if ("embedded".equals(mode)) {
            if (value.has("resource")) fail(path + ".resource", "内嵌模板不能指定资源");
        } else if ("resource".equals(mode)) reference(value, "resource", path, resources, "template");
        else fail(path + ".mode", "只支持 embedded 或 resource");
    }

    private void validateLoad(JSONObject value, String path) {
        exact(value, path, set("contextSize", "batchSize", "threads", "gpuLayers"));
        integer(value, "contextSize", path, 128, Integer.MAX_VALUE);
        integer(value, "batchSize", path, 1, Integer.MAX_VALUE);
        integer(value, "threads", path, 1, Integer.MAX_VALUE);
        integer(value, "gpuLayers", path, -1, Integer.MAX_VALUE);
    }

    private void validateInference(JSONObject value, String path) {
        exact(value, path, set("maxTokens", "temperature", "topP", "topK", "seed", "stop"));
        integer(value, "maxTokens", path, 1, Integer.MAX_VALUE);
        decimal(value, "temperature", path, 0, Double.MAX_VALUE);
        decimal(value, "topP", path, 0, 1);
        integer(value, "topK", path, 0, Integer.MAX_VALUE);
        number(value, "seed", path, -1, 0xffffffffL);
        JSONArray stops = array(value, "stop", path);
        for (int i = 0; i < stops.length(); i++) if (!(stops.opt(i) instanceof String) || ((String) stops.opt(i)).isEmpty()) fail(path + ".stop[" + i + "]", "必须是非空字符串");
    }

    private void validateThinking(JSONObject value, String path) {
        exact(value, path, set("enabled", "format", "budgetTokens"));
        bool(value, "enabled", path);
        if (!set("none", "auto", "deepseek", "deepseek-legacy").contains(string(value, "format", path))) fail(path + ".format", "未知格式");
        integer(value, "budgetTokens", path, -1, Integer.MAX_VALUE);
    }

    private void validateTools(JSONObject value, String path) {
        exact(value, path, set("enabled", "parallel", "choice"));
        bool(value, "enabled", path);
        bool(value, "parallel", path);
        if (!set("auto", "none", "required").contains(string(value, "choice", path))) fail(path + ".choice", "未知选择模式");
    }

    private void validateRequirements(JSONObject value, String path, JSONObject core) {
        exact(value, path, set("hostApiMin", "hostApiMax", "runtimeApi"));
        int min = integer(value, "hostApiMin", path, 1, HOST_API);
        int max = integer(value, "hostApiMax", path, min, HOST_API);
        if (HOST_API < min || HOST_API > max) fail(path, "宿主 hostApi=" + HOST_API + " 不在模型要求范围 " + min + ".." + max + " 内");
        int runtime = integer(value, "runtimeApi", path, 1, 2);
        if (runtime != core.optInt("runtimeApi")) fail(path + ".runtimeApi", "与绑定核心不一致");
    }

    private static JSONObject reference(JSONObject value, String key, String path, Map<String, JSONObject> resources, String type) {
        String id = nonEmpty(value, key, path);
        JSONObject target = resources.get(id);
        if (target == null || !type.equals(target.optString("type"))) fail(path + "." + key, target == null ? "引用不存在: " + id : "引用类型错误");
        return target;
    }

    private static JSONObject migrateV1(JSONObject old) {
        exact(old, "$", set("schemaVersion", "hostApi", "server", "resources", "models"));
        integer(old, "hostApi", "$", 1, 1);
        JSONObject out = copy(old);
        put(out, "schemaVersion", SCHEMA_VERSION);
        put(out, "hostApi", HOST_API);
        put(out, "protocol", defaultProtocol());
        put(out, "updates", defaultUpdates());
        put(out, "diagnostics", defaultDiagnostics());
        put(out, "management", defaultManagement());
        JSONArray resources = out.optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject item = resources.optJSONObject(i);
            if ("core".equals(item.optString("type"))) put(item, "runtimeApi", 1);
            if ("config".equals(item.optString("type"))) put(item, "autoActivate", false);
        }
        JSONArray models = out.optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            put(item, "thinking", json("enabled", false, "format", "none", "budgetTokens", -1));
            put(item, "toolCalling", json("enabled", false, "parallel", false, "choice", "none"));
            put(item, "requirements", json("hostApiMin", 1, "hostApiMax", HOST_API, "runtimeApi", 1));
            if (!item.has("capabilities")) put(item, "capabilities", new JSONArray().put("text").put("streaming").put("jinja"));
        }
        return out;
    }

    public static JSONObject defaultProtocol() {
        return json("routes", json("health", "/health", "models", "/v1/models", "chatCompletions", "/v1/chat/completions", "completions", "/v1/completions"));
    }

    public static JSONObject defaultUpdates() {
        return json("enabled", false, "manifestUrl", "", "checkIntervalMinutes", 360, "autoActivate", false, "autoInstall", false);
    }

    public static JSONObject defaultDiagnostics() {
        return json("minimumLevel", "info", "logcat", true, "maxFileBytes", 4194304, "retainedFiles", 3);
    }

    public static JSONObject defaultManagement() {
        JSONArray fields = new JSONArray()
                .put(json("path", "/server/host", "label", "监听地址", "control", "select", "options", new JSONArray().put("127.0.0.1").put("0.0.0.0")))
                .put(json("path", "/server/port", "label", "端口", "control", "number"))
                .put(json("path", "/server/autoStart", "label", "自动启动服务", "control", "toggle"))
                .put(json("path", "/server/apiKey", "label", "Bearer API Key", "control", "text"))
                .put(json("path", "/updates/enabled", "label", "自动检查更新", "control", "toggle"))
                .put(json("path", "/updates/manifestUrl", "label", "更新清单 URL", "control", "text"));
        return json("sections", new JSONArray().put(json("id", "service", "title", "服务与更新", "fields", fields)));
    }

    private static void url(String value, String path) {
        try {
            URI uri = new URI(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) fail(path, "只支持具有主机名的 http/https URL");
        } catch (Exception error) { fail(path, "URL 非法: " + error.getMessage()); }
    }

    private static JSONObject object(JSONObject value, String key, String path) {
        Object item = value.opt(key);
        if (!(item instanceof JSONObject)) fail(path + "." + key, "必须是对象");
        return (JSONObject) item;
    }

    private static JSONObject object(JSONArray value, int index, String path) {
        Object item = value.opt(index);
        if (!(item instanceof JSONObject)) fail(path + "[" + index + "]", "必须是对象");
        return (JSONObject) item;
    }

    private static JSONArray array(JSONObject value, String key, String path) {
        Object item = value.opt(key);
        if (!(item instanceof JSONArray)) fail(path + "." + key, "必须是数组");
        return (JSONArray) item;
    }

    private static String nonEmpty(JSONObject value, String key, String path) {
        String item = string(value, key, path);
        if (item.isEmpty()) fail(path + "." + key, "不能为空");
        return item;
    }

    private static String string(JSONObject value, String key, String path) {
        Object item = value.opt(key);
        if (!(item instanceof String)) fail(path + "." + key, "必须是字符串");
        return (String) item;
    }

    private static String optionalString(JSONObject value, String key, String path) {
        return value.has(key) ? string(value, key, path) : null;
    }

    private static boolean bool(JSONObject value, String key, String path) {
        if (!(value.opt(key) instanceof Boolean)) fail(path + "." + key, "必须是布尔值");
        return value.optBoolean(key);
    }

    private static int integer(JSONObject value, String key, String path, int min, int max) {
        Object item = value.opt(key);
        if (!(item instanceof Number) || ((Number) item).doubleValue() % 1 != 0) fail(path + "." + key, "必须是整数");
        long number = ((Number) item).longValue();
        if (number < min || number > max) fail(path + "." + key, "超出范围 " + min + ".." + max);
        return (int) number;
    }

    private static long number(JSONObject value, String key, String path, long min, long max) {
        Object item = value.opt(key);
        if (!(item instanceof Number) || ((Number) item).doubleValue() % 1 != 0) fail(path + "." + key, "必须是整数");
        long number = ((Number) item).longValue();
        if (number < min || number > max) fail(path + "." + key, "超出范围");
        return number;
    }

    private static void decimal(JSONObject value, String key, String path, double min, double max) {
        Object item = value.opt(key);
        if (!(item instanceof Number)) fail(path + "." + key, "必须是数字");
        double number = ((Number) item).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) fail(path + "." + key, "超出范围");
    }

    private static void exact(JSONObject value, String path, Set<String> allowed) {
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) fail(path + "." + key, "未知字段");
        }
    }

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (JSONException error) { throw new IllegalArgumentException("配置无法复制", error); }
    }

    private static JSONObject json(Object... pairs) {
        JSONObject out = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(out, String.valueOf(pairs[i]), pairs[i + 1]);
        return out;
    }

    private static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); }
        catch (JSONException error) { throw new IllegalStateException("无法写入字段 " + key, error); }
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        java.util.Collections.addAll(out, values);
        return out;
    }

    private static void fail(String path, String message) { throw new IllegalArgumentException(path + ": " + message); }
}
