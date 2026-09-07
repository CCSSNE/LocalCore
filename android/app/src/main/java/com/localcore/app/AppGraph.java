package com.localcore.app;

import android.content.Context;

import com.localcore.config.ConfigRepository;
import com.localcore.diagnostics.EventLog;
import com.localcore.resource.ResourceManager;
import com.localcore.runtime.RuntimeManager;
import com.localcore.service.BackendStatusStore;

public final class AppGraph {
    public final EventLog events;
    public final ConfigRepository config;
    public final ResourceManager resources;
    public final RuntimeManager runtime;
    public final BackendStatusStore backend;

    public AppGraph(Context context) {
        Context app = context.getApplicationContext();
        events = new EventLog(app);
        config = new ConfigRepository(app, events);
        resources = new ResourceManager(app, config, events);
        runtime = new RuntimeManager(config, resources, events);
        backend = new BackendStatusStore();
    }
}
