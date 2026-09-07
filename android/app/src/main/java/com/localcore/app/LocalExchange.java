package com.localcore.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.GgufMeta;
import com.localcore.resource.ResourceManager;
import com.localcore.resource.ResourceState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LocalExchange {
    public static final class ExportFile {
        public final String role;
        public final File file;
        public final String displayName;

        ExportFile(String role, File file, String displayName) {
            this.role = role;
            this.file = file;
            this.displayName = displayName;
        }
    }

    private static final String CORE_ID = "localcore.core";
    private final Context context;
    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;

    public LocalExchange(Context context, ConfigRepository config, ResourceManager resources, EventLog events) {
        this.context = context;
        this.config = config;
        this.resources = resources;
        this.events = events;
    }

    public String importModel(Uri uri) throws Exception {
        String name = displayName(uri);
        String base = stripSuffix(name, ".gguf");
        String resourceId = uniqueResourceId("local." + sanitize(base));
        String modelId = uniqueModelId("local." + sanitize(base));
        JSONObject descriptor = descriptor(resourceId, "model");
        try (InputStream input = open(uri)) {
            resources.importResource(descriptor, input);
        }
        JSONObject next = config.current();
        upsertResource(next, descriptor);
        long ggufContext;
        boolean contextFallback = false;
        try {
            ggufContext = GgufMeta.contextLength(resources.installedFile(resourceId));
        } catch (Exception error) {
            ggufContext = -1;
            events.error("resource", "读取模型上下文长度失败", error);
        }
        if (ggufContext <= 0) {
            ggufContext = 200000;
            contextFallback = true;
            events.error("resource", "模型未声明上下文长度(llama.context_length 缺失): " + name,
                    new IllegalStateException("llama.context_length 缺失"));
        }
        next.getJSONArray("models").put(modelEntry(base, modelId, resourceId, currentCoreId(), ggufContext));
        config.activate(next.toString());
        events.info("resource", "本地模型已导入并注册 " + modelId + " 上下文 " + ggufContext);
        JSONObject outcome = new JSONObject();
        outcome.put("modelId", modelId);
        outcome.put("contextSize", ggufContext);
        outcome.put("contextFallback", contextFallback);
        return outcome.toString();
    }

    public String downloadHfModel(String requestText) throws Exception {
        JSONObject request = new JSONObject(requestText);
        String repoId = requiredText(request, "repoId");
        String revision = requiredText(request, "revision");
        String fileName = requiredText(request, "fileName");
        String sourceId = request.optString("sourceId", activeModelDownloadSource());
        String resourceId = stableHfId("hf.model", repoId + "\n" + fileName);
        String modelId = stableHfId("hf", repoId + "\n" + fileName);
        JSONObject descriptor = hfDescriptor(request, resourceId, sourceId, repoId, revision, fileName);

        JSONObject next = config.current();
        upsertResource(next, descriptor);
        JSONObject model = findModelById(next, modelId);
        if (model == null) {
            String name = request.optString("modelName", stripSuffix(fileName, ".gguf"));
            long catalogContext = request.optLong("contextSize", 0);
            model = modelEntry(name, modelId, resourceId, currentCoreId(), catalogContext);
            next.getJSONArray("models").put(model);
        } else {
            model.put("resource", resourceId);
            model.put("core", currentCoreId());
        }
        JSONObject source = new JSONObject();
        source.put("kind", "huggingface");
        source.put("sourceId", sourceId);
        source.put("repo", repoId);
        source.put("revision", revision);
        source.put("fileName", fileName);
        source.put("contextPending", true);
        source.remove("registrationError");
        model.put("source", source);
        config.activate(next.toString());

        ResourceState state = resources.knownState(resourceId);
        if (state != null && state.usable() && revision.equals(state.version)) {
            finalizeHfModelContext(modelId, revision, resources.installedFile(resourceId));
        } else {
            resources.install(resourceId, file -> finalizeHfModelContext(modelId, revision, file));
        }
        events.info("resource", "HF 模型下载已登记 " + repoId + "/" + fileName + " -> " + modelId);
        return downloadResult(modelId, resourceId);
    }

    public String downloadHfProjection(String requestText, String modelId) throws Exception {
        JSONObject request = new JSONObject(requestText);
        String repoId = requiredText(request, "repoId");
        String revision = requiredText(request, "revision");
        String fileName = requiredText(request, "fileName");
        String sourceId = request.optString("sourceId", activeModelDownloadSource());
        String resourceId = stableHfId("hf.mmproj", repoId + "\n" + fileName);
        JSONObject descriptor = hfDescriptor(request, resourceId, sourceId, repoId, revision, fileName);

        JSONObject next = config.current();
        JSONObject model = modelById(next, modelId);
        upsertResource(next, descriptor);
        model.put("mmproj", resourceId);
        JSONArray capabilities = model.getJSONArray("capabilities");
        if (!contains(capabilities, "vision")) capabilities.put("vision");
        config.activate(next.toString());

        ResourceState state = resources.knownState(resourceId);
        if (state == null || !state.usable() || !revision.equals(state.version)) resources.install(resourceId);
        events.info("resource", "HF 投影下载已登记 " + repoId + "/" + fileName + " -> " + modelId);
        return downloadResult(modelId, resourceId);
    }

    public void setModelDownloadSource(String sourceId) throws Exception {
        JSONObject next = config.current();
        JSONObject downloads = next.getJSONObject("modelDownloads");
        findDownloadSource(downloads, sourceId);
        downloads.put("activeSource", sourceId);
        config.activate(next.toString());
        events.info("resource", "模型下载源已切换为 " + sourceId);
    }

    public void importMmproj(Uri uri, String modelId) throws Exception {
        String name = displayName(uri);
        String resourceId = uniqueResourceId("local.mmproj." + sanitize(stripSuffix(name, ".gguf")));
        JSONObject descriptor = descriptor(resourceId, "model");
        try (InputStream input = open(uri)) {
            resources.importResource(descriptor, input);
        }
        JSONObject next = config.current();
        upsertResource(next, descriptor);
        JSONObject model = modelById(next, modelId);
        model.put("mmproj", resourceId);
        JSONArray capabilities = model.getJSONArray("capabilities");
        boolean hasVision = false;
        for (int i = 0; i < capabilities.length(); i++) {
            if ("vision".equals(capabilities.optString(i))) hasVision = true;
        }
        if (!hasVision) capabilities.put("vision");
        config.activate(next.toString());
        events.info("resource", "多模态投影已配对 " + modelId + " <- " + resourceId);
    }

    public void deleteModel(String modelId) throws Exception {
        JSONObject next = config.current();
        JSONArray models = next.getJSONArray("models");
        JSONObject target = null;
        JSONArray keptModels = new JSONArray();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (modelId.equals(model.optString("id"))) target = model;
            else keptModels.put(model);
        }
        if (target == null) throw new IllegalArgumentException("配置中不存在模型: " + modelId);
        next.put("models", keptModels);
        // 已配对的投影与模型文件一起删除；仍被其余模型引用的资源保留，核心资源永不删除。
        Set<String> candidates = new HashSet<>();
        if (!target.optString("resource").isEmpty()) candidates.add(target.optString("resource"));
        if (!target.optString("mmproj").isEmpty()) candidates.add(target.optString("mmproj"));
        for (int i = 0; i < keptModels.length(); i++) {
            JSONObject model = keptModels.getJSONObject(i);
            candidates.remove(model.optString("resource"));
            candidates.remove(model.optString("mmproj"));
        }
        candidates.remove(CORE_ID);
        // ResourceManager.delete 要求描述符仍在配置中，所以先删文件再更新配置。
        for (String resourceId : candidates) {
            resources.delete(resourceId);
        }
        JSONArray allResources = next.getJSONArray("resources");
        JSONArray keptResources = new JSONArray();
        for (int i = 0; i < allResources.length(); i++) {
            JSONObject descriptor = allResources.getJSONObject(i);
            if (!candidates.contains(descriptor.optString("id"))) keptResources.put(descriptor);
        }
        next.put("resources", keptResources);
        config.activate(next.toString());
        events.info("resource", "模型已删除 " + modelId);
    }

    public void importCore(Uri uri) throws Exception {
        String name = displayName(uri);
        String base = stripSuffix(stripSuffix(stripSuffix(stripSuffix(name, ".tar.gz"), ".tgz"), ".zip"), ".so");
        String resourceId = CORE_ID;
        File temp = File.createTempFile("core-import-", ".tmp", context.getFilesDir());
        try {
            copy(uri, temp);
            JSONObject descriptor = descriptor(resourceId, "core");
            descriptor.put("version", "local-" + sanitize(base));
            descriptor.put("coreAbi", 1);
            descriptor.put("entry", detectCoreEntry(temp, name));
            try (InputStream input = new FileInputStream(temp)) {
                resources.importResource(descriptor, input);
            }
            JSONObject next = config.current();
            upsertResource(next, descriptor);
            JSONArray models = next.getJSONArray("models");
            for (int i = 0; i < models.length(); i++) models.getJSONObject(i).put("core", CORE_ID);
            config.activate(next.toString());
            events.info("resource", "本地核心已导入并注册 " + resourceId);
        } finally {
            temp.delete();
        }
    }

    public void exportCore(String coreId, Uri target) throws Exception {
        File entryFile = resources.installedFile(coreId);
        File directory = entryFile.getParentFile();
        OutputStream stream = context.getContentResolver().openOutputStream(target, "wt");
        if (stream == null) throw new IllegalStateException("系统未提供导出流");
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(stream))) {
            zipDirectory(directory, output);
        }
        events.info("resource", "核心已导出 " + coreId);
    }

    public List<ExportFile> modelExportFiles(String modelId) {
        JSONObject current = config.current();
        JSONObject model = modelById(current, modelId);
        List<ExportFile> result = new ArrayList<>();
        result.add(modelExportFile(current, model, "model"));
        String mmprojId = model.optString("mmproj");
        if (!mmprojId.isEmpty()) result.add(modelExportFile(current, model, "projection"));
        return result;
    }

    public ExportFile modelExportFile(String modelId, String role) {
        JSONObject current = config.current();
        return modelExportFile(current, modelById(current, modelId), role);
    }

    private ExportFile modelExportFile(JSONObject current, JSONObject model, String role) {
        String resourceId;
        String fallbackName;
        if ("model".equals(role)) {
            resourceId = model.optString("resource");
            fallbackName = model.optString("name", model.optString("id")) + ".gguf";
        } else if ("projection".equals(role)) {
            resourceId = model.optString("mmproj");
            if (resourceId.isEmpty()) {
                throw new IllegalArgumentException("模型没有配对 MMPROJ: " + model.optString("id"));
            }
            fallbackName = model.optString("name", model.optString("id")) + "-mmproj.gguf";
        } else {
            throw new IllegalArgumentException("未知模型导出角色: " + role);
        }
        JSONObject descriptor = resourceById(current, resourceId);
        String fallbackLeaf = leafName(fallbackName);
        if (fallbackLeaf.isEmpty()) fallbackLeaf = role + ".gguf";
        String displayName = leafName(descriptor.optString("fileName", fallbackLeaf));
        if (displayName.isEmpty()) displayName = fallbackLeaf;
        return new ExportFile(role, resources.installedFile(resourceId), displayName);
    }

    public String readTemplate(String modelId) throws Exception {
        JSONObject model = modelById(config.current(), modelId);
        File file = resources.installedFile(model.optString("resource"));
        return GgufMeta.chatTemplate(file);
    }

    public String modelTemplate(String modelId) throws Exception {
        JSONObject model = modelById(config.current(), modelId);
        JSONObject template = model.optJSONObject("template");
        if (template != null && "custom".equals(template.optString("mode"))) {
            return template.optString("value", "");
        }
        return readTemplate(modelId);
    }

    public void setModelTemplate(String modelId, String text) throws Exception {
        JSONObject next = config.current();
        JSONObject model = modelById(next, modelId);
        JSONObject template = new JSONObject();
        template.put("mode", "custom");
        template.put("value", text);
        model.put("template", template);
        config.activate(next.toString());
        events.info("resource", "模型模板已自定义 " + modelId);
    }

    public String modelSettings(String modelId) throws Exception {
        JSONObject model = modelById(config.current(), modelId);
        JSONObject result = new JSONObject();
        result.put("load", model.optJSONObject("load") == null ? new JSONObject() : model.getJSONObject("load"));
        result.put("inference",
                model.optJSONObject("inference") == null ? new JSONObject() : model.getJSONObject("inference"));
        return result.toString();
    }

    public void setModelSettings(String modelId, String loadJson, String inferenceJson) throws Exception {
        JSONObject load;
        JSONObject inference;
        try {
            load = new JSONObject(loadJson == null ? "{}" : loadJson);
            inference = new JSONObject(inferenceJson == null ? "{}" : inferenceJson);
        } catch (JSONException error) {
            throw new IllegalArgumentException("设置不是合法 JSON: " + error.getMessage());
        }
        JSONObject next = config.current();
        JSONObject model = modelById(next, modelId);
        model.put("load", checkedLoad(load));
        model.put("inference", checkedInference(inference));
        config.activate(next.toString());
        events.info("resource", "模型参数已更新 " + modelId + "，加载项下次加载生效");
    }

    private void finalizeHfModelContext(String modelId, String revision, File file) throws Exception {
        try {
            long contextSize = GgufMeta.contextLength(file);
            if (contextSize <= 0) {
                throw new IllegalStateException("GGUF 未提供有效上下文长度: " + file.getName());
            }
            JSONObject next = config.current();
            JSONObject model = modelById(next, modelId);
            JSONObject source = model.getJSONObject("source");
            if (!revision.equals(source.optString("revision"))) {
                throw new IllegalStateException("模型下载版本与当前配置不一致: downloaded=" + revision
                        + ", configured=" + source.optString("revision"));
            }
            if (!source.optBoolean("contextPending")) return;
            model.getJSONObject("load").put("contextSize", contextSize);
            source.put("contextPending", false);
            source.remove("registrationError");
            config.activate(next.toString());
            events.info("resource", "HF 模型上下文已从 GGUF 写入 " + modelId + " => " + contextSize);
        } catch (Exception error) {
            recordHfRegistrationError(modelId, revision, error);
            throw error;
        }
    }

    private void recordHfRegistrationError(String modelId, String revision, Exception error) {
        try {
            JSONObject next = config.current();
            JSONObject model = findModelById(next, modelId);
            if (model == null) return;
            JSONObject source = model.optJSONObject("source");
            if (source == null || !revision.equals(source.optString("revision"))) return;
            source.put("registrationError", error.getMessage());
            config.activate(next.toString());
        } catch (Exception persistenceError) {
            events.error("resource", "记录 HF 模型注册失败原因时出错 " + modelId, persistenceError);
        }
    }

    private JSONObject hfDescriptor(JSONObject request, String resourceId, String sourceId,
                                    String repoId, String revision, String fileName) throws Exception {
        JSONObject configuredSource = findDownloadSource(
                config.current().getJSONObject("modelDownloads"), sourceId);
        String endpoint = requiredText(configuredSource, "endpoint").replaceAll("/+$", "");
        JSONObject descriptor = descriptor(resourceId, "model");
        descriptor.put("version", revision);
        descriptor.put("url", endpoint + "/" + encodePath(repoId) + "/resolve/"
                + Uri.encode(revision) + "/" + encodePath(fileName));
        descriptor.put("size", request.optLong("size", 0));
        String sha256 = request.optString("sha256", "");
        if (!sha256.isEmpty()) descriptor.put("sha256", sha256);
        descriptor.put("fileName", fileName);
        descriptor.put("origin", "huggingface");
        descriptor.put("sourceId", sourceId);
        descriptor.put("repo", repoId);
        descriptor.put("revision", revision);
        return descriptor;
    }

    private String activeModelDownloadSource() throws JSONException {
        return config.current().getJSONObject("modelDownloads").getString("activeSource");
    }

    private static JSONObject findDownloadSource(JSONObject downloads, String sourceId) {
        JSONArray sources = downloads.optJSONArray("sources");
        if (sources == null) throw new IllegalArgumentException("modelDownloads.sources 不存在");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source != null && sourceId.equals(source.optString("id"))) return source;
        }
        throw new IllegalArgumentException("配置中不存在模型下载源: " + sourceId);
    }

    private static String encodePath(String value) {
        String[] parts = value.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) throw new IllegalArgumentException("路径包含空段: " + value);
            if (result.length() > 0) result.append('/');
            result.append(Uri.encode(part));
        }
        return result.toString();
    }

    private static String stableHfId(String prefix, String identity) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("系统不支持 SHA-256", error);
        }
        byte[] hash = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder suffix = new StringBuilder(24);
        for (int i = 0; i < 12; i++) {
            suffix.append(Character.forDigit((hash[i] >>> 4) & 0x0f, 16));
            suffix.append(Character.forDigit(hash[i] & 0x0f, 16));
        }
        return prefix + "." + suffix;
    }

    private static String requiredText(JSONObject source, String key) {
        String value = source.optString(key, "");
        if (value.isEmpty()) throw new IllegalArgumentException(key + " 不能为空");
        return value;
    }

    private static boolean contains(JSONArray values, String wanted) {
        for (int i = 0; i < values.length(); i++) if (wanted.equals(values.optString(i))) return true;
        return false;
    }

    private static String downloadResult(String modelId, String resourceId) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("modelId", modelId);
        result.put("resourceId", resourceId);
        return result.toString();
    }

    private static JSONObject checkedLoad(JSONObject load) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("contextSize", requireInt(load, "contextSize"));
        result.put("batchSize", requireInt(load, "batchSize"));
        result.put("threads", requireInt(load, "threads"));
        result.put("gpuLayers", requireInt(load, "gpuLayers"));
        return result;
    }

    private static JSONObject checkedInference(JSONObject inference) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("maxTokens", requireInt(inference, "maxTokens"));
        result.put("temperature", requireNumber(inference, "temperature"));
        result.put("topP", requireNumber(inference, "topP"));
        result.put("topK", requireInt(inference, "topK"));
        result.put("seed", requireInt(inference, "seed"));
        JSONArray stop = inference.optJSONArray("stop");
        JSONArray checked = new JSONArray();
        if (stop != null) {
            for (int i = 0; i < stop.length(); i++) {
                Object item = stop.opt(i);
                if (!(item instanceof String)) throw new IllegalArgumentException("stop 必须是字符串数组");
                checked.put(item);
            }
        }
        result.put("stop", checked);
        return result;
    }

    private static int requireInt(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是数字");
        double asDouble = ((Number) value).doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        return (int) asDouble;
    }

    private static double requireNumber(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是数字");
        double asDouble = ((Number) value).doubleValue();
        if (!Double.isFinite(asDouble)) throw new IllegalArgumentException(key + " 必须是有限数字");
        return asDouble;
    }

    public String currentCoreId() {
        return CORE_ID;
    }

    private String detectCoreEntry(File file, String name) throws IOException {
        if (!com.localcore.io.Archives.isZip(file) && !com.localcore.io.Archives.isTar(file)) {
            return sanitize(name);
        }
        java.util.List<String> entries = com.localcore.io.Archives.isZip(file)
                ? com.localcore.io.Archives.zipEntries(file)
                : com.localcore.io.Archives.tarEntries(file);
        return pickCoreEntry(entries, name);
    }

    private static String pickCoreEntry(java.util.List<String> entries, String name) {
        java.util.List<String> soEntries = new java.util.ArrayList<>();
        for (String entry : entries) {
            if (entry.endsWith(".so")) soEntries.add(entry);
        }
        String abi = null;
        java.util.List<String> candidates = null;
        for (String supported : android.os.Build.SUPPORTED_ABIS) {
            java.util.List<String> matching = new java.util.ArrayList<>();
            String marker = "jniLibs/" + supported + "/";
            for (String entry : soEntries) {
                if (entry.contains(marker)) matching.add(entry);
            }
            if (!matching.isEmpty()) {
                abi = supported;
                candidates = matching;
                break;
            }
        }
        if (candidates == null) {
            throw new IllegalArgumentException("核心包内没有 jniLibs/<abi>/*.so: " + name);
        }
        for (String preferred : preferredLibraries(abi)) {
            for (String entry : candidates) {
                if (entry.endsWith(preferred)) return entry;
            }
        }
        return candidates.get(0);
    }

    private static java.util.List<String> preferredLibraries(String abi) {
        java.util.List<String> result = new java.util.ArrayList<>();
        result.add("liblocalcore_core.so");
        return result;
    }

    private static void zipDirectory(File directory, ZipOutputStream output) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                zipDirectory(child, output);
                continue;
            }
            output.putNextEntry(new ZipEntry(child.getName()));
            try (FileInputStream input = new FileInputStream(child)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            output.closeEntry();
        }
    }

    private JSONObject descriptor(String id, String type) throws JSONException {
        JSONObject descriptor = new JSONObject();
        descriptor.put("id", id);
        descriptor.put("type", type);
        descriptor.put("version", "local");
        return descriptor;
    }

    private JSONObject modelEntry(String name, String modelId, String resourceId, String coreId,
                                  long contextSize) throws JSONException {
        JSONObject model = new JSONObject();
        model.put("id", modelId);
        model.put("name", name);
        model.put("resource", resourceId);
        model.put("core", coreId == null ? "" : coreId);
        model.put("template", new JSONObject().put("mode", "embedded"));
        model.put("load", json("contextSize", contextSize, "batchSize", 512, "threads", 4, "gpuLayers", -1));
        model.put("inference", json("maxTokens", 131072, "temperature", 0.7, "topP", 0.95, "topK", 40, "seed", -1,
                "stop", new JSONArray()));
        model.put("thinking", json("enabled", false, "format", "none", "budgetTokens", -1));
        model.put("toolCalling", json("enabled", false, "parallel", false, "choice", "none"));
        model.put("capabilities", new JSONArray().put("text").put("streaming"));
        return model;
    }

    private static void upsertResource(JSONObject target, JSONObject descriptor) throws JSONException {
        JSONArray resources = target.getJSONArray("resources");
        JSONArray merged = new JSONArray();
        for (int i = 0; i < resources.length(); i++) {
            JSONObject current = resources.getJSONObject(i);
            if (!descriptor.getString("id").equals(current.optString("id"))) merged.put(current);
        }
        merged.put(descriptor);
        target.put("resources", merged);
    }

    private static JSONObject modelById(JSONObject config, String modelId) {
        JSONObject model = findModelById(config, modelId);
        if (model != null) return model;
        throw new IllegalArgumentException("配置中不存在模型: " + modelId);
    }

    private static JSONObject findModelById(JSONObject config, String modelId) {
        JSONArray models = config.optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && modelId.equals(model.optString("id"))) return model;
        }
        return null;
    }

    private static JSONObject resourceById(JSONObject config, String resourceId) {
        JSONArray resources = config.optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource != null && resourceId.equals(resource.optString("id"))) return resource;
        }
        throw new IllegalArgumentException("配置中不存在资源: " + resourceId);
    }

    private static String leafName(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private String uniqueResourceId(String base) {
        JSONArray resources = config.current().optJSONArray("resources");
        Set<String> used = new HashSet<>();
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            if (descriptor != null) used.add(descriptor.optString("id"));
        }
        return unique(base, used);
    }

    private String uniqueModelId(String base) {
        JSONArray models = config.current().optJSONArray("models");
        Set<String> used = new HashSet<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null) used.add(model.optString("id"));
        }
        return unique(base, used);
    }

    private static String unique(String base, Set<String> used) {
        if (!used.contains(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + "." + i;
            if (!used.contains(candidate)) return candidate;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\\\/\\p{Cntrl}]", "_");
    }

    private static String stripSuffix(String name, String suffix) {
        return name.regionMatches(true, name.length() - suffix.length(), suffix, 0, suffix.length())
                ? name.substring(0, name.length() - suffix.length())
                : name;
    }

    private String displayName(Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.moveToFirst()) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            } finally {
                cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "unnamed" : segment;
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalStateException("系统未提供输入流");
        return input;
    }

    private void copy(Uri uri, File target) throws IOException {
        try (InputStream input = open(uri); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static JSONObject json(Object... pairs) throws JSONException {
        JSONObject value = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
}
