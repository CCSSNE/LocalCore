package com.localcore.runtime;

public final class NativeBridge {
    public interface GenerationCallback {
        boolean onToken(byte[] token);
    }

    static {
        System.loadLibrary("localcore_host");
    }

    private NativeBridge() {
    }

    public static native long open(String soPath);

    public static native void close(long handle);

    public static native String version(long handle);

    public static native void loadModel(long handle, String modelPath, int contextSize,
            int batchSize, int threads, int gpuLayers);

    public static native void unloadModel(long handle);

    public static native String applyChatTemplate(long handle, String[] roles, String[] contents,
            String template);

    public static native int tokenCount(long handle, String text);

    public static native int generate(long handle, String prompt, int maxTokens, float temperature,
            float topP, int topK, long seed, String[] stops, String grammar,
            GenerationCallback callback);

    public static native void cancel(long handle);
}
