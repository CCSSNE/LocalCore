package com.localcore.config;

import android.content.Context;

import com.localcore.diagnostics.EventLog;
import com.localcore.io.AtomicFiles;
import com.localcore.io.Jsons;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConfigRepository {
    public interface Listener {
        void onConfigActivated(JSONObject config);
    }

    private final Context context;
    private final EventLog events;
    private final File activeFile;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private JSONObject active;

    public ConfigRepository(Context context, EventLog events) {
        this.context = context;
        this.events = events;
        File directory = new File(context.getFilesDir(), "configuration");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建配置目录: " + directory);
        }
        activeFile = new File(directory, "active.json");
        try {
            if (!activeFile.isFile()) {
                try (InputStream input = context.getAssets().open("default_config.json")) {
                    AtomicFiles.writeUtf8(activeFile,
                            Jsons.format(Jsons.parseObject(Jsons.readUtf8(input), "内置初始配置")));
                }
            }
            active = Jsons.readObject(activeFile);
            boolean migrated = false;
            int schemaVersion = active.optInt("schemaVersion");
            if (schemaVersion == 2) {
                active = migrateV2(active);
                events.info("config", "配置已从 schemaVersion=2 迁移到 3");
                schemaVersion = 3;
                migrated = true;
            }
            if (schemaVersion == 3) {
                active = migrateV3(active);
                events.info("config", "配置已从 schemaVersion=3 迁移到 4");
                schemaVersion = 4;
                migrated = true;
            }
            if (schemaVersion != 4) {
                throw new IllegalStateException("不支持的配置 schemaVersion=" + schemaVersion);
            }
            HotSettings.validateConfig(active);
            if (migrated) AtomicFiles.writeUtf8(activeFile, Jsons.format(active));
            events.info("config", "已加载配置 schemaVersion=" + active.optInt("schemaVersion"));
        } catch (Exception error) {
            events.error("config", "有效配置加载失败", error);
            throw new IllegalStateException("有效配置加载失败: " + error.getMessage(), error);
        }
    }

    public synchronized JSONObject current() {
        return Jsons.parseObject(active.toString(), "当前配置");
    }

    public synchronized String currentText() {
        return Jsons.format(active);
    }

    public void activate(String text) throws IOException {
        JSONObject candidate = Jsons.parseObject(text, "候选配置");
        HotSettings.validateConfig(candidate);
        synchronized (this) {
            AtomicFiles.writeUtf8(activeFile, Jsons.format(candidate));
            active = candidate;
        }
        events.info("config", "候选配置已原子激活");
        for (Listener listener : listeners) {
            listener.onConfigActivated(current());
        }
    }

    public String readImport(InputStream input) throws IOException {
        return Jsons.format(Jsons.parseObject(Jsons.readUtf8(input), "候选配置"));
    }

    public synchronized void exportTo(OutputStream output) throws IOException {
        output.write(currentText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.flush();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public File activeFile() {
        return activeFile;
    }

    private JSONObject migrateV2(JSONObject previous) throws Exception {
        JSONObject defaults;
        try (InputStream input = context.getAssets().open("default_config.json")) {
            defaults = Jsons.parseObject(Jsons.readUtf8(input), "内置配置");
        }
        JSONObject migrated = new JSONObject(previous.toString());
        migrated.put("schemaVersion", 3);
        migrated.remove("updates");
        migrated.put("coreUpdates", defaults.getJSONObject("coreUpdates"));
        migrated.put("management", defaults.getJSONObject("management"));
        org.json.JSONArray resources = migrated.getJSONArray("resources");
        org.json.JSONArray retained = new org.json.JSONArray();
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            if (!"core".equals(resource.optString("type"))) retained.put(resource);
        }
        migrated.put("resources", retained);
        org.json.JSONArray models = migrated.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            models.getJSONObject(i).put("core", "localcore.core");
        }
        return migrated;
    }

    private JSONObject migrateV3(JSONObject previous) throws Exception {
        JSONObject defaults;
        try (InputStream input = context.getAssets().open("default_config.json")) {
            defaults = Jsons.parseObject(Jsons.readUtf8(input), "内置配置");
        }
        JSONObject migrated = new JSONObject(previous.toString());
        migrated.put("schemaVersion", 4);
        migrated.put("modelDownloads", defaults.getJSONObject("modelDownloads"));
        return migrated;
    }
}
