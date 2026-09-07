package com.localcore.update;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.Jsons;
import com.localcore.resource.ResourceManager;
import com.localcore.resource.ResourceState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 从仓库 Release 的稳定清单发现核心，合并核心描述符而不覆盖用户模型配置。 */
public final class UpdateManager {
    public enum Phase { DISABLED, IDLE, CHECKING, DOWNLOADING, FAILED }

    public static final class State {
        public final Phase phase;
        public final String version;
        public final String error;

        State(Phase phase, String version, String error) {
            this.phase = phase;
            this.version = version;
            this.error = error;
        }

        @Override
        public String toString() {
            JSONObject value = new JSONObject();
            try {
                value.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
                value.put("version", version == null ? JSONObject.NULL : version);
                value.put("error", error == null ? JSONObject.NULL : error);
                return value.toString();
            } catch (Exception failure) {
                throw new IllegalStateException("无法序列化更新状态", failure);
            }
        }
    }

    public interface Listener { void onUpdateState(State state); }

    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile State state = new State(Phase.IDLE, null, null);

    public UpdateManager(ConfigRepository config, ResourceManager resources, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.events = events;
        resources.addListener(this::onResourceState);
    }

    public State state() { return state; }
    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void checkNow() {
        if (!checking.compareAndSet(false, true)) throw new IllegalStateException("核心更新检查已在执行");
        executor.execute(() -> {
            try { discover(); }
            catch (Exception error) {
                setState(new State(Phase.FAILED, state.version, error.getMessage()));
                events.error("update", "核心更新失败", error);
            } finally { checking.set(false); }
        });
    }

    private void discover() throws Exception {
        JSONObject policy = config.current().getJSONObject("coreUpdates");
        String manifestUrl = policy.getString("manifestUrl");
        if (manifestUrl.isEmpty()) throw new IllegalStateException("核心更新清单 URL 为空");
        setState(new State(Phase.CHECKING, null, null));
        JSONObject manifest = Jsons.parseObject(fetch(manifestUrl), "核心更新清单");
        if (manifest.getInt("schemaVersion") != 1) {
            throw new IllegalStateException("不支持的核心更新清单版本: " + manifest.optInt("schemaVersion"));
        }
        JSONObject descriptor = new JSONObject(manifest.getJSONObject("core").toString());
        if (!"localcore.core".equals(descriptor.getString("id"))
                || !"core".equals(descriptor.getString("type"))) {
            throw new IllegalStateException("核心更新清单的资源身份无效");
        }
        mergeDescriptor(descriptor);
        ResourceState installed = resources.knownState(descriptor.getString("id"));
        if (installed != null && installed.usable()
                && descriptor.getString("version").equals(installed.version)) {
            setState(new State(Phase.IDLE, installed.version, null));
            events.info("update", "动态核心已是最新版本 " + installed.version);
            return;
        }
        setState(new State(Phase.DOWNLOADING, descriptor.getString("version"), null));
        resources.install(descriptor.getString("id"));
    }

    private void mergeDescriptor(JSONObject descriptor) throws Exception {
        JSONObject next = config.current();
        JSONArray current = next.getJSONArray("resources");
        JSONArray merged = new JSONArray();
        boolean changed = false;
        boolean found = false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.getJSONObject(i);
            if (descriptor.getString("id").equals(item.optString("id"))) {
                found = true;
                merged.put(descriptor);
                if (!item.toString().equals(descriptor.toString())) changed = true;
            } else {
                merged.put(item);
            }
        }
        if (!found) {
            merged.put(descriptor);
            changed = true;
        }
        next.put("resources", merged);
        JSONArray models = next.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if (!descriptor.getString("id").equals(model.optString("core"))) {
                model.put("core", descriptor.getString("id"));
                changed = true;
            }
        }
        if (changed) config.activate(next.toString());
    }

    private void onResourceState(ResourceState resource) {
        if (!"localcore.core".equals(resource.id)) return;
        if (resource.status == ResourceState.Status.INSTALLED) {
            setState(new State(Phase.IDLE, resource.version, null));
            events.info("update", "动态核心安装完成 " + resource.version);
        } else if (resource.status == ResourceState.Status.FAILED) {
            setState(new State(Phase.FAILED, resource.targetVersion, resource.error));
        }
    }

    private static String fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("核心清单 HTTP 状态 " + status);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream())) {
                return Jsons.readUtf8(input);
            }
        } finally { connection.disconnect(); }
    }

    private void setState(State next) {
        state = next;
        for (Listener listener : listeners) listener.onUpdateState(next);
    }
}
