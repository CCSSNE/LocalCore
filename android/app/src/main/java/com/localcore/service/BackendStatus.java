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

    @Override
    public String toString() {
        org.json.JSONObject value = new org.json.JSONObject();
        try {
            value.put("running", running);
            value.put("address", address == null ? org.json.JSONObject.NULL : address);
            value.put("error", error == null ? org.json.JSONObject.NULL : error);
            return value.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化后端状态", failure);
        }
    }
}
