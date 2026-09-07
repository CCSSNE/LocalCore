package com.localcore.resource;

import org.json.JSONException;
import org.json.JSONObject;

public final class ResourceState {
    public enum Status { MISSING, QUEUED, DOWNLOADING, VERIFYING, INSTALLED, FAILED }

    public final String id;
    public final String type;
    public final String version;
    public final Status status;
    public final long downloaded;
    public final long total;
    public final String path;
    public final String error;

    public ResourceState(String id, String type, String version, Status status, long downloaded,
                         long total, String path, String error) {
        this.id = id;
        this.type = type;
        this.version = version;
        this.status = status;
        this.downloaded = downloaded;
        this.total = total;
        this.path = path;
        this.error = error;
    }

    public JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("id", id);
            value.put("type", type);
            value.put("version", version);
            value.put("status", status.name());
            value.put("downloaded", downloaded);
            value.put("total", total);
            value.put("path", path == null ? JSONObject.NULL : path);
            value.put("error", error == null ? JSONObject.NULL : error);
            return value;
        } catch (JSONException error) {
            throw new IllegalStateException("无法序列化资源状态", error);
        }
    }

    public static ResourceState fromJson(JSONObject value) {
        return new ResourceState(
                value.optString("id"),
                value.optString("type"),
                value.optString("version"),
                Status.valueOf(value.optString("status")),
                value.optLong("downloaded"),
                value.optLong("total"),
                value.isNull("path") ? null : value.optString("path"),
                value.isNull("error") ? null : value.optString("error"));
    }
}

