package com.localcore.runtime;

public final class NativeRuntime implements AutoCloseable {
    static {
        System.loadLibrary("localcore_loader");
    }

    private volatile long handle;
    private String corePath;

    public synchronized void openCore(String path) {
        if (path.equals(corePath) && handle != 0) return;
        close();
        long opened = nativeOpenCore(path);
        if (opened == 0) throw new IllegalStateException("动态核心打开后未返回句柄");
        handle = opened;
        corePath = path;
    }

    public synchronized String loadModel(String requestJson) {
        return nativeLoadModel(requireHandle(), requestJson);
    }

    public interface ProgressConsumer {
        void onProgress(String phase, int done, int total);
    }

    public synchronized String infer(String requestJson, RuntimeManager.TokenConsumer consumer) {
        return nativeInfer(requireHandle(), requestJson, consumer);
    }

    public synchronized String infer2(String requestJson, RuntimeManager.TokenConsumer tokenConsumer,
                                      ProgressConsumer progressConsumer) {
        return nativeInfer2(requireHandle(), requestJson, tokenConsumer, progressConsumer);
    }

    public synchronized void unloadModel() {
        if (handle != 0) nativeUnloadModel(handle);
    }

    public void cancel() {
        long current = handle;
        if (current != 0) nativeCancel(current);
    }

    @Override
    public synchronized void close() {
        long current = handle;
        handle = 0;
        corePath = null;
        if (current != 0) nativeCloseCore(current);
    }

    private long requireHandle() {
        long current = handle;
        if (current == 0) throw new IllegalStateException("动态核心尚未打开");
        return current;
    }

    private static native long nativeOpenCore(String path);
    private static native String nativeLoadModel(long handle, String requestJson);
    private static native String nativeInfer(long handle, String requestJson, RuntimeManager.TokenConsumer consumer);
    private static native String nativeInfer2(long handle, String requestJson,
            RuntimeManager.TokenConsumer tokenConsumer, ProgressConsumer progressConsumer);
    private static native void nativeUnloadModel(long handle);
    private static native void nativeCancel(long handle);
    private static native void nativeCloseCore(long handle);
}
