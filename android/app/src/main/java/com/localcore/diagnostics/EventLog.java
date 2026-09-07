package com.localcore.diagnostics;

import android.content.Context;
import android.util.Log;

import com.localcore.io.Jsons;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventLog {
    public interface Listener {
        void onEvent(JSONObject event);
    }

    private static final String TAG = "LocalCore";
    private final File file;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public EventLog(Context context) {
        File directory = new File(context.getFilesDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建诊断目录: " + directory);
        }
        file = new File(directory, "events.jsonl");
    }

    public void info(String component, String message) {
        record("info", component, message, null);
    }

    public void error(String component, String message, Throwable error) {
        record("error", component, message, error);
    }

    public synchronized void record(String level, String component, String message, Throwable error) {
        JSONObject event = new JSONObject();
        try {
            event.put("time", System.currentTimeMillis());
            event.put("level", level);
            event.put("component", component);
            event.put("message", message);
            if (error != null) {
                event.put("errorType", error.getClass().getName());
                event.put("error", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line);
                output.getFD().sync();
            }
            if ("error".equals(level)) {
                Log.e(TAG, component + ": " + message, error);
            } else {
                Log.i(TAG, component + ": " + message);
            }
            for (Listener listener : listeners) {
                listener.onEvent(event);
            }
        } catch (JSONException | IOException writeError) {
            throw new IllegalStateException("无法记录诊断事件", writeError);
        }
    }

    public String readAll() throws IOException {
        if (!file.isFile()) {
            return "";
        }
        return Jsons.readUtf8(new java.io.FileInputStream(file));
    }

    public File file() {
        return file;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}

