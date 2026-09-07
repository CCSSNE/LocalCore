package com.localcore.resource;

import android.content.Context;
import android.os.Build;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.AtomicFiles;
import com.localcore.io.Jsons;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ResourceManager {
    public interface Listener {
        void onResourceState(ResourceState state);
    }

    private final ConfigRepository config;
    private final EventLog events;
    private final File root;
    private final File downloads;
    private final File stateFile;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, ResourceState> states = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ResourceManager(Context context, ConfigRepository config, EventLog events) {
        this.config = config;
        this.events = events;
        root = new File(context.getFilesDir(), "resources");
        downloads = new File(context.getFilesDir(), "downloads");
        stateFile = new File(root, "state.json");
        ensureDirectory(root);
        ensureDirectory(downloads);
        loadStates();
        reconcile();
    }

    public synchronized List<ResourceState> states() {
        List<ResourceState> result = new ArrayList<>();
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            String id = descriptor.optString("id");
            ResourceState state = states.get(id);
            if (state == null) state = missing(descriptor);
            result.add(state);
        }
        return result;
    }

    public synchronized ResourceState state(String id) {
        JSONObject descriptor = descriptor(id);
        ResourceState state = states.get(id);
        return state == null ? missing(descriptor) : state;
    }

    public File installedFile(String id) {
        JSONObject descriptor = descriptor(id);
        ResourceState state;
        synchronized (this) {
            state = states.get(id);
        }
        String expectedVersion = descriptor.optString("version");
        if (state == null || state.status != ResourceState.Status.INSTALLED
                || !expectedVersion.equals(state.version) || state.path == null) {
            throw new IllegalStateException("资源尚未安装当前版本: " + id + "@" + expectedVersion);
        }
        File file = new File(state.path);
        if (!file.isFile()) {
            throw new IllegalStateException("资源状态指向的文件不存在: " + file);
        }
        return file;
    }

    public void install(String id) {
        JSONObject descriptor = descriptor(id);
        synchronized (this) {
            ResourceState existing = states.get(id);
            if (existing != null && (existing.status == ResourceState.Status.QUEUED
                    || existing.status == ResourceState.Status.DOWNLOADING
                    || existing.status == ResourceState.Status.VERIFYING)) {
                throw new IllegalStateException("资源任务已在执行: " + id);
            }
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    ResourceState.Status.QUEUED, 0, effective(descriptor).optLong("size"), null, null));
        }
        executor.execute(() -> downloadAndInstall(descriptor));
    }

    public void installAllOutdated() {
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            ResourceState current = state(descriptor.optString("id"));
            if (current.status != ResourceState.Status.INSTALLED
                    || !descriptor.optString("version").equals(current.version)) {
                install(descriptor.optString("id"));
            }
        }
    }

    public synchronized void delete(String id) throws IOException {
        JSONObject descriptor = descriptor(id);
        ResourceState state = states.get(id);
        if (state != null && (state.status == ResourceState.Status.DOWNLOADING
                || state.status == ResourceState.Status.VERIFYING)) {
            throw new IllegalStateException("资源正在写入，不能删除: " + id);
        }
        File resourceDirectory = new File(new File(root, descriptor.optString("type")), id);
        deleteTree(resourceDirectory);
        File partial = partialFile(id);
        if (partial.exists() && !partial.delete()) {
            throw new IOException("无法删除未完成下载: " + partial);
        }
        states.remove(id);
        persistStates();
        ResourceState missing = missing(descriptor);
        notifyListeners(missing);
        events.info("resource", "已删除资源 " + id);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void downloadAndInstall(JSONObject descriptor) {
        String id = descriptor.optString("id");
        JSONObject source;
        try {
            source = effective(descriptor);
            File partial = partialFile(id);
            long existing = partial.isFile() ? partial.length() : 0;
            long total = source.optLong("size");
            if (existing > total) {
                throw new IOException("部分文件大于声明大小: " + existing + " > " + total);
            }
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    ResourceState.Status.DOWNLOADING, existing, total, null, null));
            transfer(source.optString("url"), partial, existing, total, descriptor);
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    ResourceState.Status.VERIFYING, partial.length(), total, null, null));
            verify(partial, total, source.optString("sha256"));
            File installed = activate(descriptor, source, partial);
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    ResourceState.Status.INSTALLED, total, total, installed.getAbsolutePath(), null));
            events.info("resource", "资源已校验并原子激活 " + id + "@" + descriptor.optString("version"));
        } catch (Exception error) {
            ResourceState before;
            synchronized (this) { before = states.get(id); }
            long downloaded = before == null ? 0 : before.downloaded;
            long total = before == null ? 0 : before.total;
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    ResourceState.Status.FAILED, downloaded, total, null, error.getMessage()));
            events.error("resource", "资源安装失败 " + id, error);
        }
    }

    private void transfer(String address, File partial, long existing, long expected,
                          JSONObject descriptor) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        int status = connection.getResponseCode();
        boolean append;
        if (existing > 0 && status == HttpURLConnection.HTTP_PARTIAL) {
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange == null || !contentRange.startsWith("bytes " + existing + "-")) {
                throw new IOException("服务器返回的 Content-Range 与本地断点不一致: " + contentRange);
            }
            append = true;
        } else if (status == HttpURLConnection.HTTP_OK) {
            if (existing > 0) events.info("resource", "服务器忽略 Range，重新下载 " + descriptor.optString("id"));
            append = false;
            existing = 0;
        } else {
            throw new IOException("下载 HTTP 状态 " + status + ": " + address);
        }
        File parent = partial.getParentFile();
        ensureDirectory(parent);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream fileOutput = new FileOutputStream(partial, append);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            long downloaded = existing;
            long lastReport = 0;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                downloaded += count;
                long now = System.currentTimeMillis();
                if (now - lastReport >= 500) {
                    update(new ResourceState(descriptor.optString("id"), descriptor.optString("type"),
                            descriptor.optString("version"), ResourceState.Status.DOWNLOADING,
                            downloaded, expected, null, null));
                    lastReport = now;
                }
            }
            output.flush();
            fileOutput.getFD().sync();
        } finally {
            connection.disconnect();
        }
    }

    private File activate(JSONObject descriptor, JSONObject source, File partial) throws IOException {
        String type = descriptor.optString("type");
        String id = descriptor.optString("id");
        String version = descriptor.optString("version");
        File typeDirectory = new File(root, type);
        File idDirectory = new File(typeDirectory, id);
        File targetDirectory = new File(idDirectory, version);
        ensureDirectory(idDirectory);
        if (targetDirectory.exists()) deleteTree(targetDirectory);

        if ("core".equals(type)) {
            File staging = new File(idDirectory, version + ".candidate");
            if (staging.exists()) deleteTree(staging);
            ensureDirectory(staging);
            unzip(partial, staging);
            File entry = safeChild(staging, source.optString("entry"));
            if (!entry.isFile()) throw new IOException("核心包缺少入口: " + source.optString("entry"));
            AtomicFiles.move(staging, targetDirectory);
            if (!partial.delete()) throw new IOException("无法删除已安装的核心包暂存文件");
            return safeChild(targetDirectory, source.optString("entry"));
        }

        ensureDirectory(targetDirectory);
        String extension = "model".equals(type) ? ".gguf" : "template".equals(type) ? ".jinja" : ".json";
        File target = new File(targetDirectory, id + extension);
        AtomicFiles.move(partial, target);
        return target;
    }

    private void unzip(File archive, File target) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                File output = safeChild(target, entry.getName());
                if (entry.isDirectory()) {
                    ensureDirectory(output);
                } else {
                    ensureDirectory(output.getParentFile());
                    try (FileOutputStream file = new FileOutputStream(output)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) file.write(buffer, 0, count);
                        file.getFD().sync();
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static File safeChild(File root, String relative) throws IOException {
        File child = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!child.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("资源包包含越界路径: " + relative);
        }
        return child;
    }

    private static void verify(File file, long expectedSize, String expectedDigest) throws IOException {
        if (file.length() != expectedSize) {
            throw new IOException("文件大小校验失败，声明 " + expectedSize + "，实际 " + file.length());
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("系统不支持 SHA-256", error);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value));
        if (!expectedDigest.equals(actual.toString())) {
            throw new IOException("SHA-256 校验失败，声明 " + expectedDigest + "，实际 " + actual);
        }
    }

    private synchronized void update(ResourceState state) {
        states.put(state.id, state);
        try {
            persistStates();
        } catch (IOException error) {
            throw new IllegalStateException("资源状态持久化失败", error);
        }
        notifyListeners(state);
    }

    private void notifyListeners(ResourceState state) {
        for (Listener listener : listeners) listener.onResourceState(state);
    }

    private synchronized void persistStates() throws IOException {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, ResourceState> item : states.entrySet()) {
                root.put(item.getKey(), item.getValue().toJson());
            }
        } catch (JSONException error) {
            throw new IOException("无法序列化资源状态", error);
        }
        AtomicFiles.writeUtf8(stateFile, Jsons.format(root));
    }

    private void loadStates() {
        if (!stateFile.isFile()) return;
        try {
            JSONObject root = Jsons.readObject(stateFile);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                states.put(id, ResourceState.fromJson(root.optJSONObject(id)));
            }
        } catch (Exception error) {
            events.error("resource", "资源状态文件无效", error);
            throw new IllegalStateException("资源状态文件无效: " + error.getMessage(), error);
        }
    }

    private synchronized void reconcile() {
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, ResourceState> item : states.entrySet()) {
            ResourceState state = item.getValue();
            if (state.status == ResourceState.Status.INSTALLED
                    && (state.path == null || !new File(state.path).isFile())) {
                invalid.add(item.getKey());
            } else if (state.status == ResourceState.Status.DOWNLOADING
                    || state.status == ResourceState.Status.QUEUED
                    || state.status == ResourceState.Status.VERIFYING) {
                JSONObject descriptor = findDescriptor(item.getKey());
                if (descriptor != null) {
                    File partial = partialFile(item.getKey());
                    states.put(item.getKey(), new ResourceState(item.getKey(), descriptor.optString("type"),
                            descriptor.optString("version"), ResourceState.Status.FAILED,
                            partial.isFile() ? partial.length() : 0, effective(descriptor).optLong("size"),
                            null, "进程在任务完成前终止，可重新执行以从断点续传"));
                }
            }
        }
        for (String id : invalid) states.remove(id);
        try {
            persistStates();
        } catch (IOException error) {
            throw new IllegalStateException("资源状态协调失败", error);
        }
    }

    private JSONObject descriptor(String id) {
        JSONObject descriptor = findDescriptor(id);
        if (descriptor == null) throw new IllegalArgumentException("配置中不存在资源: " + id);
        return descriptor;
    }

    private JSONObject findDescriptor(String id) {
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            if (id.equals(descriptor.optString("id"))) return descriptor;
        }
        return null;
    }

    private static JSONObject effective(JSONObject descriptor) {
        if (!"core".equals(descriptor.optString("type"))) return descriptor;
        JSONObject variants = descriptor.optJSONObject("variants");
        for (String abi : Build.SUPPORTED_ABIS) {
            JSONObject variant = variants.optJSONObject(abi);
            if (variant != null) return variant;
        }
        throw new IllegalStateException("核心 " + descriptor.optString("id") + " 不支持设备 ABI "
                + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private ResourceState missing(JSONObject descriptor) {
        return new ResourceState(descriptor.optString("id"), descriptor.optString("type"),
                descriptor.optString("version"), ResourceState.Status.MISSING, 0,
                effective(descriptor).optLong("size"), null, null);
    }

    private File partialFile(String id) {
        return new File(downloads, id + ".part");
    }

    private static void ensureDirectory(File directory) {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("无法创建目录: " + directory);
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!target.delete()) throw new IOException("无法删除: " + target);
    }
}

