package com.localcore;

import android.app.Application;

import com.localcore.app.AppGraph;

public final class LocalCoreApplication extends Application {
    private AppGraph graph;

    @Override
    public void onCreate() {
        super.onCreate();
        graph = new AppGraph(this);
    }

    public AppGraph graph() {
        return graph;
    }
}

