package com.localcore.config;

import android.content.Context;

import com.localcore.diagnostics.EventLog;
import com.localcore.io.AtomicFiles;
import com.localcore.io.Jsons;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConfigRepository {
    public interface Listener {
        void onConfigActivated(JSONObject config);
    }

    private final Context context;
    private final EventLog events;
    private final ConfigValidator validator = new ConfigValidator();
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
                    JSONObject initial = Jsons.parseObject(Jsons.readUtf8(input), "内置初始配置");
                    validator.validate(initial);
                    AtomicFiles.writeUtf8(activeFile, Jsons.format(initial));
                }
            }
            active = Jsons.readObject(activeFile);
            validator.validate(active);
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

    public JSONObject validate(String text) {
        JSONObject candidate = Jsons.parseObject(text, "候选配置");
        validator.validate(candidate);
        return candidate;
    }

    public void activate(String text) throws IOException {
        JSONObject candidate = validate(text);
        synchronized (this) {
            AtomicFiles.writeUtf8(activeFile, Jsons.format(candidate));
            active = candidate;
        }
        events.info("config", "候选配置校验通过并已原子激活");
        for (Listener listener : listeners) {
            listener.onConfigActivated(current());
        }
    }

    public String readImport(InputStream input) throws IOException {
        String text = Jsons.readUtf8(input);
        return Jsons.format(validate(text));
    }

    public synchronized void exportTo(FileOutputStream output) throws IOException {
        output.write(currentText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.getFD().sync();
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
}
