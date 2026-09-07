package com.localcore;

import android.app.Application;

import com.localcore.app.AppGraph;
import com.localcore.service.BackendService;

public final class LocalCoreApplication extends Application {
    private AppGraph graph;

    @Override
    public void onCreate() {
        super.onCreate();
        graph = new AppGraph(this);
        if (graph.config.current().optJSONObject("server").optBoolean("autoStart")) {
            BackendService.command(this, BackendService.ACTION_START);
        }
    }

    public AppGraph graph() {
        return graph;
    }
}
