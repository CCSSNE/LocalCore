package com.localcore.runtime;

public final class RuntimeState {
    public enum Phase { EMPTY, CORE_READY, MODEL_LOADING, MODEL_READY, GENERATING, ERROR }

    public final Phase phase;
    public final String coreId;
    public final String coreVersion;
    public final String modelId;
    public final String error;

    public RuntimeState(Phase phase, String coreId, String coreVersion, String modelId, String error) {
        this.phase = phase;
        this.coreId = coreId;
        this.coreVersion = coreVersion;
        this.modelId = modelId;
        this.error = error;
    }

    public static RuntimeState empty() {
        return new RuntimeState(Phase.EMPTY, null, null, null, null);
    }

    @Override
    public String toString() {
        org.json.JSONObject value = new org.json.JSONObject();
        try {
            value.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
            value.put("coreId", coreId == null ? org.json.JSONObject.NULL : coreId);
            value.put("coreVersion", coreVersion == null ? org.json.JSONObject.NULL : coreVersion);
            value.put("modelId", modelId == null ? org.json.JSONObject.NULL : modelId);
            value.put("error", error == null ? org.json.JSONObject.NULL : error);
            return value.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("无法序列化运行时状态", failure);
        }
    }
}
