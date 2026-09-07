package com.localcore.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.GgufMeta;
import com.localcore.resource.ResourceManager;

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
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LocalExchange {
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
        next.getJSONArray("models").put(modelEntry(base, modelId, resourceId, currentCoreId()));
        config.activate(next.toString());
        events.info("resource", "本地模型已导入并注册 " + modelId);
        return modelId;
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

    public String readTemplate(String modelId) throws Exception {
        JSONObject model = modelById(config.current(), modelId);
        File file = resources.installedFile(model.optString("resource"));
        return GgufMeta.chatTemplate(file);
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

    private JSONObject modelEntry(String name, String modelId, String resourceId, String coreId) throws JSONException {
        JSONObject model = new JSONObject();
        model.put("id", modelId);
        model.put("name", name);
        model.put("resource", resourceId);
        model.put("core", coreId == null ? "" : coreId);
        model.put("template", new JSONObject().put("mode", "embedded"));
        model.put("load", json("contextSize", 4096, "batchSize", 512, "threads", 4, "gpuLayers", -1));
        model.put("inference", json("maxTokens", 1024, "temperature", 0.7, "topP", 0.95, "topK", 40, "seed", -1,
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
        JSONArray models = config.optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && modelId.equals(model.optString("id"))) return model;
        }
        throw new IllegalArgumentException("配置中不存在模型: " + modelId);
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
