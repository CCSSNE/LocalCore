package com.localcore.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BackendStatusStore {
    public interface Listener {
        void onBackendStatus(BackendStatus status);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private BackendStatus current = BackendStatus.stopped();

    public synchronized BackendStatus current() {
        return current;
    }

    public void update(BackendStatus status) {
        synchronized (this) { current = status; }
        for (Listener listener : listeners) listener.onBackendStatus(status);
    }

    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
}

