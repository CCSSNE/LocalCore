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

import java.io.BufferedInputStream;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class LocalExchange {
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
        next.getJSONArray("resources").put(descriptor);
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
        next.getJSONArray("resources").put(descriptor);
        modelById(next, modelId).put("mmproj", resourceId);
        config.activate(next.toString());
        events.info("resource", "多模态投影已配对 " + modelId + " <- " + resourceId);
    }

    public void importCore(Uri uri) throws Exception {
        String name = displayName(uri);
        String base = stripSuffix(stripSuffix(name, ".zip"), ".so");
        String resourceId = uniqueResourceId("local.core." + sanitize(base));
        File temp = File.createTempFile("core-import-", ".tmp", context.getFilesDir());
        try {
            copy(uri, temp);
            JSONObject descriptor = descriptor(resourceId, "core");
            descriptor.put("entry", detectCoreEntry(temp, name));
            try (InputStream input = new FileInputStream(temp)) {
                resources.importResource(descriptor, input);
            }
            JSONObject next = config.current();
            next.getJSONArray("resources").put(descriptor);
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
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            if ("core".equals(descriptor.optString("type"))) return descriptor.optString("id");
        }
        return null;
    }

    private String detectCoreEntry(File file, String name) throws IOException {
        if (!isZip(file)) return sanitize(name);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".so")) return entry.getName();
            }
        }
        throw new IOException("核心包内没有 .so 文件: " + name);
    }

    private static boolean isZip(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        }
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
