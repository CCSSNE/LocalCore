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
        events = new EventLog(app);
        config = new ConfigRepository(app, events);
        events.configure(config.current().optJSONObject("diagnostics"));
        config.addListener(value -> events.configure(value.optJSONObject("diagnostics")));
        resources = new ResourceManager(app, config, events);
        runtime = new RuntimeManager(config, resources, events);
        backend = new BackendStatusStore();
        updates = new UpdateManager(config, resources, events);
        exchange = new LocalExchange(app, config, resources, events);
    }
}
