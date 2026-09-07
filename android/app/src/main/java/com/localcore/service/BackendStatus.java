package com.localcore.service;

public final class BackendStatus {
    public final boolean running;
    public final String address;
    public final String error;

    public BackendStatus(boolean running, String address, String error) {
        this.running = running;
        this.address = address;
        this.error = error;
    }

    public static BackendStatus stopped() {
        return new BackendStatus(false, null, null);
    }
}

