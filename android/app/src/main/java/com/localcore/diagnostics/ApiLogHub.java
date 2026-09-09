package com.localcore.diagnostics;

/**
 * API 请求日志直达 JS 日志屏的轻量通道。
 * 与 EventLog（落盘 + logcat 的全量诊断）互补：token/进度高频，只走内存转发，不写文件，
 * 任何转发失败都直接吞掉，绝不能影响推理与 HTTP 响应主流程。
 */
public final class ApiLogHub {
    public interface Listener {
        void onApiToken(String requestId, String piece);
        void onApiStage(String requestId, String stage);
        void onApiProgress(String requestId, String phase, int done, int total, long elapsedMs);
    }

    private static volatile Listener listener;

    private ApiLogHub() {}

    public static void setListener(Listener value) {
        listener = value;
    }

    public static void clearListener(Listener value) {
        if (listener == value) listener = null;
    }

    public static void emitToken(String requestId, String piece) {
        Listener current = listener;
        if (current == null || piece == null || piece.isEmpty()) return;
        try {
            current.onApiToken(requestId, piece);
        } catch (Exception ignored) {
        }
    }

    public static void emitStage(String requestId, String stage) {
        Listener current = listener;
        if (current == null || stage == null || stage.isEmpty()) return;
        try {
            current.onApiStage(requestId, stage);
        } catch (Exception ignored) {
        }
    }

    public static void emitProgress(String requestId, String phase, int done, int total, long elapsedMs) {
        Listener current = listener;
        if (current == null || phase == null || phase.isEmpty()) return;
        try {
            current.onApiProgress(requestId, phase, done, total, elapsedMs);
        } catch (Exception ignored) {
        }
    }
}
