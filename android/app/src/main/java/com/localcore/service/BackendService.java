package com.localcore.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.localcore.MainApplication;
import com.localcore.MainActivity;
import com.localcore.app.AppGraph;
import com.localcore.server.LocalHttpServer;
import com.localcore.config.ConfigRepository;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackendService extends Service {
    public static final String ACTION_START = "com.localcore.action.START";
    public static final String ACTION_STOP = "com.localcore.action.STOP";
    public static final String ACTION_RESTART = "com.localcore.action.RESTART";
    private static final String CHANNEL = "localcore_backend";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "LocalCoreBackend";

    private volatile AppGraph graph;
    private volatile LocalHttpServer server;
    private volatile boolean requested;
    private volatile boolean configListenerRegistered;
    private int lastStartId;
    private final ExecutorService starter = Executors.newSingleThreadExecutor();
    private final ConfigRepository.Listener configListener = this::onConfigChanged;

    public static void command(Context context, String action) {
        Log.i(TAG, "command action=" + action);
        Intent intent = new Intent(context, BackendService.class).setAction(action);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        long t0 = SystemClock.uptimeMillis();
        Log.i(TAG, "onCreate begin(轻量: 只建通知通道, 图与服务延迟到后台线程)");
        createChannel();
        Log.i(TAG, "onCreate end elapsedMs=" + (SystemClock.uptimeMillis() - t0));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action + " startId=" + startId);
        if (ACTION_STOP.equals(action)) {
            requested = false;
            startForeground(NOTIFICATION_ID, notification("正在停止"));
            final int stopId = startId;
            starter.execute(() -> stopInBackground(stopId));
            return START_NOT_STICKY;
        }
        requested = true;
        // 主线程只做 startForeground，随即返回：真正的图初始化与端口绑定全部在后台线程，
        // 首点不再卡主线程，通知立刻可见。
        startForeground(NOTIFICATION_ID, notification("正在启动(初始化中)…"));
        final String deferred = action;
        starter.execute(() -> startInBackground(deferred, startId));
        return START_STICKY;
    }

    private void startInBackground(String action, int startId) {
        long t0 = SystemClock.uptimeMillis();
        Log.i(TAG, "startInBackground begin action=" + action + " startId=" + startId);
        try {
            long g0 = SystemClock.uptimeMillis();
            Log.i(TAG, "graph_init_start");
            AppGraph current = ((MainApplication) getApplication()).getGraph();
            long graphMs = SystemClock.uptimeMillis() - g0;
            Log.i(TAG, "graph_init_end elapsedMs=" + graphMs);
            synchronized (this) {
                graph = current;
                if (server == null) {
                    server = new LocalHttpServer(current.config, current.resources, current.runtime, current.events);
                    Log.i(TAG, "server_created");
                }
                if (!configListenerRegistered) {
                    current.config.addListener(configListener);
                    configListenerRegistered = true;
                    Log.i(TAG, "config_listener_registered");
                }
            }
            current.events.info("service", "后端启动 graph就绪 elapsedMs=" + graphMs + " action=" + action);
            if (ACTION_RESTART.equals(action)) {
                Log.i(TAG, "restart_stop_old");
                current.events.info("service", "RESTART 先停旧监听");
                server.stop();
            }
            JSONObject serverConfig = current.config.current().optJSONObject("server");
            String host = serverConfig == null ? "?" : serverConfig.optString("host");
            int port = serverConfig == null ? -1 : serverConfig.optInt("port");
            Log.i(TAG, "server_start_start host=" + host + " port=" + port);
            long s0 = SystemClock.uptimeMillis();
            server.start();
            long serverMs = SystemClock.uptimeMillis() - s0;
            String address = server.address();
            Log.i(TAG, "server_start_end elapsedMs=" + serverMs + " address=" + address);
            current.backend.update(new BackendStatus(true, address, null));
            current.events.info("service", "后端启动完成 totalElapsedMs=" + (SystemClock.uptimeMillis() - t0)
                    + " graphMs=" + graphMs + " serverMs=" + serverMs + " address=" + address);
            notifyState("服务地址 " + address);
        } catch (Exception error) {
            Log.e(TAG, "startInBackground failed totalElapsedMs=" + (SystemClock.uptimeMillis() - t0), error);
            AppGraph current = graph;
            if (current != null) {
                try {
                    current.events.error("service", "HTTP 服务启动失败 totalElapsedMs="
                            + (SystemClock.uptimeMillis() - t0), error);
                } catch (Exception ignored) {
                }
                try {
                    current.backend.update(new BackendStatus(false, null, error.getMessage()));
                } catch (Exception ignored) {
                }
            }
            notifyState("启动失败: " + error.getMessage());
        }
    }

    private void stopInBackground(int stopId) {
        long t0 = SystemClock.uptimeMillis();
        Log.i(TAG, "stopInBackground begin stopId=" + stopId);
        try {
            LocalHttpServer active;
            AppGraph current;
            synchronized (this) {
                active = server;
                current = graph;
            }
            if (active != null) active.stop();
            if (current != null) {
                current.backend.update(BackendStatus.stopped());
                current.events.info("service", "后端已停止 elapsedMs=" + (SystemClock.uptimeMillis() - t0));
            }
        } catch (Exception error) {
            Log.e(TAG, "stopInBackground failed", error);
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(stopId);
            Log.i(TAG, "stopInBackground end elapsedMs=" + (SystemClock.uptimeMillis() - t0));
        }
    }

    @Override
    public void onDestroy() {
        long t0 = SystemClock.uptimeMillis();
        Log.i(TAG, "onDestroy begin");
        try {
            AppGraph current = graph;
            if (current != null && configListenerRegistered) {
                try {
                    current.config.removeListener(configListener);
                } catch (Exception ignored) {
                }
                configListenerRegistered = false;
            }
            LocalHttpServer active = server;
            if (active != null) {
                try {
                    active.stop();
                } catch (Exception ignored) {
                }
            }
            if (current != null) {
                try {
                    current.backend.update(BackendStatus.stopped());
                } catch (Exception ignored) {
                }
            }
        } finally {
            starter.shutdownNow();
            super.onDestroy();
            Log.i(TAG, "onDestroy end elapsedMs=" + (SystemClock.uptimeMillis() - t0));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onConfigChanged(JSONObject config) {
        if (!requested) {
            Log.i(TAG, "onConfigChanged ignored(requested=false)");
            return;
        }
        Log.i(TAG, "onConfigChanged enqueue_restart");
        starter.execute(() -> {
            long t0 = SystemClock.uptimeMillis();
            AppGraph current = graph;
            LocalHttpServer active = server;
            if (!requested || current == null || active == null) {
                Log.i(TAG, "onConfigChanged skipped(graph/server未就绪)");
                return;
            }
            try {
                if (active.matchesListener(current.config.current().optJSONObject("server"))) {
                    current.events.info("service", "配置已生效，监听地址未变，保留 HTTP 监听");
                    return;
                }
                current.events.info("service", "配置变更: 重新监听开始");
                active.stop();
                long s0 = SystemClock.uptimeMillis();
                active.start();
                String address = active.address();
                current.backend.update(new BackendStatus(true, address, null));
                current.events.info("service", "配置变更: 重新监听完成 elapsedMs="
                        + (SystemClock.uptimeMillis() - t0) + " bindMs=" + (SystemClock.uptimeMillis() - s0)
                        + " address=" + address);
                notifyState("服务地址 " + address);
            } catch (Exception error) {
                Log.e(TAG, "onConfigChanged restart failed", error);
                current.events.error("service", "配置激活后重新监听失败", error);
                try {
                    current.backend.update(new BackendStatus(false, null, error.getMessage()));
                } catch (Exception ignored) {
                }
                notifyState("重启失败: " + error.getMessage());
            }
        });
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "LocalCore backend",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("LocalCore HTTP backend and resource tasks");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String content) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("LocalCore")
                .setContentText(content)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    private void notifyState(String content) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(content));
    }
}
