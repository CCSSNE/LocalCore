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
}
