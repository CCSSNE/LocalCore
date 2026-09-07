package com.localcore.update;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.Jsons;
import com.localcore.resource.ResourceManager;
import com.localcore.resource.ResourceState;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateManager {
    public enum Phase { DISABLED, IDLE, CHECKING, DOWNLOADING, READY, ACTIVATING, FAILED }

    public static final class State {
        public final Phase phase;
        public final String version;
        public final String error;

        State(Phase phase, String version, String error) {
            this.phase = phase;
            this.version = version;
            this.error = error;
        }
    }

    public interface Listener { void onUpdateState(State state); }

    private final ConfigRepository config;
    private final ResourceManager resources;
    private final EventLog events;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean checking = new AtomicBoolean();
    private ScheduledFuture<?> scheduled;
    private volatile State state = new State(Phase.IDLE, null, null);
    private volatile File candidate;
    private volatile JSONObject candidateDescriptor;

    public UpdateManager(ConfigRepository config, ResourceManager resources, EventLog events) {
        this.config = config;
        this.resources = resources;
        this.events = events;
        config.addListener(ignored -> schedule());
        resources.addListener(this::onResourceState);
        schedule();
    }

    public State state() { return state; }
    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void checkNow() {
        if (!checking.compareAndSet(false, true)) throw new IllegalStateException("更新检查已在执行");
        executor.execute(() -> {
            try { discover(); }
            catch (Exception error) {
                setState(new State(Phase.FAILED, state.version, error.getMessage()));
                events.error("update", "更新检查失败", error);
            } finally { checking.set(false); }
        });
    }

    public void activateCandidate() {
        executor.execute(() -> {
            try { activate(); }
            catch (Exception error) {
                setState(new State(Phase.FAILED, state.version, error.getMessage()));
                events.error("update", "候选配置激活失败", error);
            }
        });
    }

    private synchronized void schedule() {
        if (scheduled != null) scheduled.cancel(false);
        JSONObject policy = config.current().optJSONObject("updates");
        if (!policy.optBoolean("enabled")) {
            setState(new State(Phase.DISABLED, null, null));
            return;
        }
        long interval = policy.optLong("checkIntervalMinutes");
        scheduled = executor.scheduleWithFixedDelay(this::scheduledCheck, 0, interval, TimeUnit.MINUTES);
        setState(new State(Phase.IDLE, null, null));
    }

    private void scheduledCheck() {
        if (!checking.compareAndSet(false, true)) return;
        try { discover(); }
        catch (Exception error) {
            setState(new State(Phase.FAILED, state.version, error.getMessage()));
            events.error("update", "定时更新检查失败", error);
        } finally { checking.set(false); }
    }

    private void onResourceState(ResourceState resource) {
        JSONObject descriptor = candidateDescriptor;
        if (descriptor == null || !resource.id.equals(descriptor.optString("id"))) return;
        State current = state;
        if (resource.status == ResourceState.Status.FAILED && current.phase == Phase.DOWNLOADING) {
            setState(new State(Phase.FAILED, current.version, resource.error == null
                    ? "候选配置下载或校验失败，旧版本继续生效" : resource.error));
            events.error("update", "候选配置安装失败，旧版本继续生效: " + resource.id, null);
        }
    }

    private void discover() throws Exception {
        JSONObject policy = config.current().optJSONObject("updates");
        String manifestUrl = policy.optString("manifestUrl");
        if (manifestUrl.isEmpty()) throw new IllegalStateException("更新清单 URL 为空");
        setState(new State(Phase.CHECKING, null, null));
        JSONObject manifest = config.validateUpdateManifest(fetch(manifestUrl));
        JSONObject descriptor = new JSONObject(manifest.optJSONObject("configuration").toString());
        String id = descriptor.optString("id");
        String version = descriptor.optString("version");
        ResourceState installed = resources.knownState(id);
        if (installed != null && installed.usable() && version.equals(installed.version)) {
            setState(new State(Phase.IDLE, version, null));
            events.info("update", "当前配置已是清单版本 " + version);
            return;
        }
        descriptor.put("autoActivate", false);
        candidateDescriptor = descriptor;
        setState(new State(Phase.DOWNLOADING, version, null));
        resources.installConfiguration(descriptor, file -> {
            candidate = file;
            setState(new State(Phase.READY, version, null));
            events.info("update", "候选配置已下载并通过完整性校验 " + version);
            if (config.current().optJSONObject("updates").optBoolean("autoActivate")) activate();
        });
    }

    private void activate() throws IOException {
        File file = candidate;
        JSONObject descriptor = candidateDescriptor;
        if (file == null || descriptor == null || !file.isFile()) throw new IllegalStateException("没有可激活的候选配置");
        setState(new State(Phase.ACTIVATING, descriptor.optString("version"), null));
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            config.activate(Jsons.readUtf8(input));
        }
        if (config.current().optJSONObject("updates").optBoolean("autoInstall")) resources.installAllOutdated();
        candidate = null;
        candidateDescriptor = null;
        setState(new State(Phase.IDLE, descriptor.optString("version"), null));
        events.info("update", "候选配置已原子激活 " + descriptor.optString("version"));
    }

    private static String fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("更新清单 HTTP 状态 " + status);
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
