package com.localcore.runtime;

public final class NativeBridge {
    public interface GenerationCallback {
        boolean onToken(byte[] utf8);
    }

    static {
        System.loadLibrary("localcore_host");
    }

    private NativeBridge() {}

    public static native long open(String libraryPath);
    public static native void close(long handle);
    public static native String version(long handle);
    public static native int runtimeApi(long handle);
    public static native void loadModel(long handle, String modelPath, int contextSize,
                                        int batchSize, int threads, int gpuLayers);
    public static native void unloadModel(long handle);
    public static native String applyChatTemplate(long handle, String[] roles, String[] contents,
                                                   String template);
    public static native String prepareChat(long handle, String requestJson, String template);
    public static native String parseChatOutput(long handle, String planJson, String generated);
    public static native int tokenCount(long handle, String text);
    public static native int generate(long handle, String prompt, int maxTokens, float temperature,
                                      float topP, int topK, long seed, String[] stop,
                                      String chatPlanJson, GenerationCallback callback);
    public static native void cancel(long handle);
}
