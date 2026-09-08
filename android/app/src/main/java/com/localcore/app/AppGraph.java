package com.localcore.app;

import android.content.Context;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.resource.ResourceManager;
import com.localcore.runtime.RuntimeManager;
import com.localcore.service.BackendStatusStore;
import com.localcore.update.UpdateManager;

public final class AppGraph {
    public final EventLog events;
    public final ConfigRepository config;
    public final ResourceManager resources;
    public final RuntimeManager runtime;
    public final BackendStatusStore backend;
    public final UpdateManager updates;
    public final LocalExchange exchange;

    public AppGraph(Context context) {
        Context app = context.getApplicationContext();
        long t0 = System.currentTimeMillis();
        // 首个分阶段日志必须在 EventLog 就绪后立刻打出：后端首点卡在哪一步，靠这串 elapsed 定位。
        events = new EventLog(app);
        events.info("graph", "AppGraph初始化开始");
        config = new ConfigRepository(app, events);
        events.info("graph", "config就绪 elapsedMs=" + (System.currentTimeMillis() - t0));
        events.configure(config.current().optJSONObject("diagnostics"));
        config.addListener(value -> events.configure(value.optJSONObject("diagnostics")));
        resources = new ResourceManager(app, config, events);
        events.info("graph", "resources就绪 elapsedMs=" + (System.currentTimeMillis() - t0));
        runtime = new RuntimeManager(app, config, resources, events);
        events.info("graph", "runtime就绪 elapsedMs=" + (System.currentTimeMillis() - t0));
        backend = new BackendStatusStore();
        updates = new UpdateManager(config, resources, events);
        events.info("graph", "updates就绪 elapsedMs=" + (System.currentTimeMillis() - t0));
        exchange = new LocalExchange(app, config, resources, events);
        events.info("graph", "AppGraph初始化完成 totalElapsedMs=" + (System.currentTimeMillis() - t0));
    }
}
