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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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

    public interface InstallListener {
        void onInstalled(File file) throws Exception;
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
        reconcile(true);
        config.addListener(ignored -> reconcile(false));
    }

    public synchronized List<ResourceState> states() {
        List<ResourceState> result = new ArrayList<>();
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            String id = descriptor.optString("id");
            result.add(present(descriptor, states.get(id)));
        }
        return result;
    }

    public synchronized ResourceState state(String id) {
        JSONObject descriptor = descriptor(id);
        return present(descriptor, states.get(id));
    }

    public synchronized ResourceState knownState(String id) {
        return states.get(id);
    }

    public File installedFile(String id) {
        JSONObject descriptor = descriptor(id);
        ResourceState state;
        synchronized (this) {
            state = states.get(id);
        }
        if (state == null || !state.usable()) {
            throw new IllegalStateException("资源没有可用的已激活版本: " + id);
        }
        return new File(state.path);
    }

    public void install(String id) {
        installDescriptor(descriptor(id), null);
    }

    public void installConfiguration(JSONObject descriptor, InstallListener listener) {
        installDescriptor(descriptor, listener);
    }

    private void installDescriptor(JSONObject descriptor, InstallListener listener) {
        String id = descriptor.optString("id");
        synchronized (this) {
            ResourceState existing = states.get(id);
            if (existing != null && (existing.status == ResourceState.Status.QUEUED
                    || existing.status == ResourceState.Status.DOWNLOADING)) {
                if (listener == null && descriptor.optString("version").equals(existing.targetVersion)) return;
                throw new IllegalStateException("资源任务已在执行: " + id);
            }
            String activeVersion = existing != null && existing.usable() ? existing.version : null;
            String activePath = existing != null && existing.usable() ? existing.path : null;
            update(new ResourceState(id, descriptor.optString("type"), activeVersion,
                    descriptor.optString("version"), ResourceState.Status.QUEUED, 0,
                    effective(descriptor).optLong("size"), activePath, null));
        }
        executor.execute(() -> downloadAndInstall(descriptor, listener));
    }

    public void installAllOutdated() {
        JSONArray resources = config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            ResourceState current = state(descriptor.optString("id"));
            if (!current.usable() || !descriptor.optString("version").equals(current.version)) {
                install(descriptor.optString("id"));
            }
        }
    }

    public void importResource(JSONObject descriptor, InputStream input) throws IOException {
        String id = descriptor.optString("id");
        ResourceState existing;
        synchronized (this) {
            existing = states.get(id);
            if (existing != null && (existing.status == ResourceState.Status.QUEUED
                    || existing.status == ResourceState.Status.DOWNLOADING)) {
                throw new IllegalStateException("资源任务已在执行: " + id);
            }
        }
        File partial = partialFile(id, descriptor.optString("version"));
        ensureDirectory(partial.getParentFile());
        long expected = effective(descriptor).optLong("size");
        update(new ResourceState(id, descriptor.optString("type"),
                existing != null && existing.usable() ? existing.version : null,
                descriptor.optString("version"), ResourceState.Status.DOWNLOADING, 0, expected,
                existing != null && existing.usable() ? existing.path : null, null));
        try (FileOutputStream file = new FileOutputStream(partial, false)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                file.write(buffer, 0, count);
            }
            file.getFD().sync();
        } catch (Exception error) {
            failed(descriptor, error);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("模型导入失败", error);
        }
        try {
            File installed = activate(descriptor, effective(descriptor), partial);
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    descriptor.optString("version"), ResourceState.Status.INSTALLED, expected, expected,
                    installed.getAbsolutePath(), null));
            events.info("resource", "本地文件已原子激活 " + id + "@" + descriptor.optString("version"));
        } catch (Exception error) {
            failed(descriptor, error);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("模型导入失败", error);
        }
    }

    public synchronized void delete(String id) throws IOException {
        JSONObject descriptor = descriptor(id);
        ResourceState state = states.get(id);
        if (state != null && state.status == ResourceState.Status.DOWNLOADING) {
            throw new IllegalStateException("资源正在写入，不能删除: " + id);
        }
        File resourceDirectory = new File(new File(root, descriptor.optString("type")), id);
        deleteTree(resourceDirectory);
        File[] partials = downloads.listFiles((directory, name) -> name.startsWith(id + "@") && name.endsWith(".part"));
        if (partials != null) for (File partial : partials) {
            if (!partial.delete()) throw new IOException("无法删除未完成下载: " + partial);
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

    private void downloadAndInstall(JSONObject descriptor, InstallListener listener) {
        String id = descriptor.optString("id");
        JSONObject source;
        File installed;
        try {
            source = effective(descriptor);
            File partial = partialFile(id, descriptor.optString("version"));
            long existing = partial.isFile() ? partial.length() : 0;
            long total = source.optLong("size");
            if (existing > 0 && total > 0 && (existing > total
                    || (existing == total && !matchesIntegrity(partial, source)))) {
                events.info("resource", "丢弃尺寸或摘要不匹配的断点文件 " + id);
                if (!partial.delete()) throw new IOException("无法删除损坏的断点文件: " + partial);
                existing = 0;
            }
            ResourceState active = activeState(id);
            update(taskState(descriptor, active, ResourceState.Status.DOWNLOADING, existing, total, null));
            if (total <= 0 || existing != total) {
                transfer(source.optString("url"), partial, existing, total, descriptor);
            }
            verifyIntegrity(partial, source);
            installed = activate(descriptor, source, partial);
            update(new ResourceState(id, descriptor.optString("type"), descriptor.optString("version"),
                    descriptor.optString("version"), ResourceState.Status.INSTALLED,
                    total, total, installed.getAbsolutePath(), null));
            events.info("resource", "资源已原子激活 " + id + "@" + descriptor.optString("version"));
        } catch (Exception error) {
            failed(descriptor, error);
            events.error("resource", "资源安装失败 " + id, error);
            return;
        }
        if (listener != null) {
            try {
                listener.onInstalled(installed);
            } catch (Exception error) {
                events.error("update", "资源安装后回调失败 " + id, error);
            }
        }
        if ("config".equals(descriptor.optString("type")) && descriptor.optBoolean("autoActivate")) {
            try (InputStream input = new FileInputStream(installed)) {
                config.activate(Jsons.readUtf8(input));
            } catch (Exception error) {
                events.error("update", "配置资源自动激活被拒绝，文件已安装且当前配置保持不变 " + id, error);
                return;
            }
            events.info("update", "配置资源已自动激活 " + id + "@" + descriptor.optString("version"));
        }
    }

    private void failed(JSONObject descriptor, Exception error) {
        ResourceState before;
        synchronized (this) { before = states.get(descriptor.optString("id")); }
        String activeVersion = before != null && before.usable() ? before.version : null;
        String activePath = before != null && before.usable() ? before.path : null;
        update(new ResourceState(descriptor.optString("id"), descriptor.optString("type"), activeVersion,
                descriptor.optString("version"), ResourceState.Status.FAILED,
                before == null ? 0 : before.downloaded, before == null ? 0 : before.total,
                activePath, error.getMessage()));
    }

    private ResourceState activeState(String id) {
        synchronized (this) {
            ResourceState value = states.get(id);
            return value != null && value.usable() ? value : null;
        }
    }

    private static ResourceState taskState(JSONObject descriptor, ResourceState active, ResourceState.Status status,
                                           long downloaded, long total, String error) {
        return new ResourceState(descriptor.optString("id"), descriptor.optString("type"),
                active == null ? null : active.version, descriptor.optString("version"), status,
                downloaded, total, active == null ? null : active.path, error);
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
                    update(taskState(descriptor, activeState(descriptor.optString("id")),
                            ResourceState.Status.DOWNLOADING, downloaded, expected, null));
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
            if (com.localcore.io.Archives.isZip(partial)) {
                File staging = new File(idDirectory, version + ".candidate");
                if (staging.exists()) deleteTree(staging);
                com.localcore.io.Archives.ensureDirectory(staging);
                com.localcore.io.Archives.unzip(partial, staging);
                AtomicFiles.move(staging, targetDirectory);
            } else if (com.localcore.io.Archives.isTar(partial)) {
                File staging = new File(idDirectory, version + ".candidate");
                if (staging.exists()) deleteTree(staging);
                com.localcore.io.Archives.ensureDirectory(staging);
                com.localcore.io.Archives.untar(partial, staging);
                AtomicFiles.move(staging, targetDirectory);
            } else {
                ensureDirectory(targetDirectory);
                AtomicFiles.move(partial, new File(targetDirectory, source.optString("entry")));
            }
            if (partial.isFile() && !partial.delete()) throw new IOException("无法删除已安装的核心包暂存文件");
            return new File(targetDirectory, source.optString("entry"));
        }

        ensureDirectory(targetDirectory);
        String extension = "model".equals(type) ? ".gguf" : "template".equals(type) ? ".jinja" : ".json";
        File target = new File(targetDirectory, id + extension);
        AtomicFiles.move(partial, target);
        return target;
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

    private synchronized void reconcile(boolean recoverInterruptedTasks) {
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, ResourceState> item : states.entrySet()) {
            ResourceState state = item.getValue();
            if (state.status == ResourceState.Status.INSTALLED
                    && (state.path == null || !new File(state.path).isFile())) {
                invalid.add(item.getKey());
            } else if (recoverInterruptedTasks && (state.status == ResourceState.Status.DOWNLOADING
                    || state.status == ResourceState.Status.QUEUED)) {
                JSONObject descriptor = findDescriptor(item.getKey());
                if (descriptor != null) {
                    File partial = partialFile(item.getKey(), state.targetVersion);
                    states.put(item.getKey(), new ResourceState(item.getKey(), descriptor.optString("type"),
                            state.usable() ? state.version : null, state.targetVersion, ResourceState.Status.FAILED,
                            partial.isFile() ? partial.length() : 0, effective(descriptor).optLong("size"),
                            state.usable() ? state.path : null, "进程在任务完成前终止，可重新执行以从断点续传"));
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
        if (variants == null) return descriptor;
        for (String abi : Build.SUPPORTED_ABIS) {
            JSONObject variant = variants.optJSONObject(abi);
            if (variant != null) return variant;
        }
        throw new IllegalStateException("核心 " + descriptor.optString("id") + " 不支持设备 ABI "
                + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private ResourceState missing(JSONObject descriptor) {
        return new ResourceState(descriptor.optString("id"), descriptor.optString("type"), null,
                descriptor.optString("version"), ResourceState.Status.MISSING, 0,
                effective(descriptor).optLong("size"), null, null);
    }

    private ResourceState present(JSONObject descriptor, ResourceState state) {
        if (state == null) return missing(descriptor);
        String wanted = descriptor.optString("version");
        if (state.usable() && !wanted.equals(state.version)
                && (state.status == ResourceState.Status.INSTALLED || state.status == ResourceState.Status.UPDATE_AVAILABLE)) {
            return new ResourceState(state.id, descriptor.optString("type"), state.version, wanted,
                    ResourceState.Status.UPDATE_AVAILABLE, 0, effective(descriptor).optLong("size"), state.path, null);
        }
        return state;
    }

    private File partialFile(String id, String version) {
        return new File(downloads, id + "@" + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".part");
    }

    private static boolean matchesIntegrity(File file, JSONObject source) throws IOException {
        try {
            verifyIntegrity(file, source);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static void verifyIntegrity(File file, JSONObject source) throws IOException {
        long expectedSize = source.optLong("size");
        if (expectedSize > 0 && file.length() != expectedSize) {
            throw new IOException("资源尺寸不一致: expected=" + expectedSize + ", actual=" + file.length());
        }
        String expectedHash = source.optString("sha256");
        if (expectedHash.isEmpty()) return;
        final MessageDigest digest;
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
        for (byte value : digest.digest()) {
            actual.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            actual.append(Character.forDigit(value & 0x0f, 16));
        }
        if (!expectedHash.equalsIgnoreCase(actual.toString())) {
            throw new IOException("资源 SHA-256 不一致: expected=" + expectedHash + ", actual=" + actual);
        }
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
