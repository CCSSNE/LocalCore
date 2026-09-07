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
    private volatile int minimumLevel = 1;
    private volatile boolean writeLogcat = true;
    private volatile long maxFileBytes = 4L * 1024 * 1024;
    private volatile int retainedFiles = 3;

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

    public void debug(String component, String message) {
        record("debug", component, message, null);
    }

    public void error(String component, String message, Throwable error) {
        record("error", component, message, error);
    }

    public synchronized void record(String level, String component, String message, Throwable error) {
        if (priority(level) < minimumLevel) return;
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
            rotateIfNeeded(line.length);
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line);
                output.getFD().sync();
            }
            if (writeLogcat) {
                if ("error".equals(level)) Log.e(TAG, component + ": " + message, error);
                else if ("debug".equals(level)) Log.d(TAG, component + ": " + message);
                else Log.i(TAG, component + ": " + message);
            }
            for (Listener listener : listeners) {
                listener.onEvent(event);
            }
        } catch (JSONException | IOException writeError) {
            Log.e(TAG, "诊断事件写入失败", writeError);
            throw new IllegalStateException("无法记录诊断事件", writeError);
        }
    }

    public String readAll() throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = retainedFiles; i >= 1; i--) {
            File archive = new File(file.getParentFile(), file.getName() + "." + i);
            if (archive.isFile()) result.append(read(archive));
        }
        if (file.isFile()) result.append(read(file));
        return result.toString();
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

    public synchronized void configure(JSONObject value) {
        String level = value.optString("minimumLevel");
        minimumLevel = "debug".equals(level) ? 0 : "info".equals(level) ? 1 : 2;
        writeLogcat = value.optBoolean("logcat");
        maxFileBytes = value.optLong("maxFileBytes");
        retainedFiles = value.optInt("retainedFiles");
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if (!file.isFile() || file.length() + incomingBytes <= maxFileBytes) return;
        File oldest = new File(file.getParentFile(), file.getName() + "." + retainedFiles);
        if (oldest.exists() && !oldest.delete()) throw new IOException("无法删除旧诊断日志: " + oldest);
        for (int i = retainedFiles - 1; i >= 1; i--) {
            File from = new File(file.getParentFile(), file.getName() + "." + i);
            File to = new File(file.getParentFile(), file.getName() + "." + (i + 1));
            if (from.exists() && !from.renameTo(to)) throw new IOException("无法轮换诊断日志: " + from);
        }
        File first = new File(file.getParentFile(), file.getName() + ".1");
        if (!file.renameTo(first)) throw new IOException("无法轮换当前诊断日志");
    }

    private static int priority(String level) {
        return "debug".equals(level) ? 0 : "info".equals(level) ? 1 : 2;
    }

    private static String read(File source) throws IOException {
        try (java.io.FileInputStream input = new java.io.FileInputStream(source)) {
            return Jsons.readUtf8(input);
        }
    }
}
