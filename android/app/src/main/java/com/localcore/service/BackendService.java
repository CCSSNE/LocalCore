package com.localcore.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.localcore.MainApplication;
import com.localcore.MainActivity;
import com.localcore.app.AppGraph;
import com.localcore.server.LocalHttpServer;
import com.localcore.config.ConfigRepository;

import org.json.JSONObject;

public final class BackendService extends Service {
    public static final String ACTION_START = "com.localcore.action.START";
    public static final String ACTION_STOP = "com.localcore.action.STOP";
    public static final String ACTION_RESTART = "com.localcore.action.RESTART";
    private static final String CHANNEL = "localcore_backend";
    private static final int NOTIFICATION_ID = 1;

    private AppGraph graph;
    private LocalHttpServer server;
    private boolean requested;
    private int lastStartId;
    private final ConfigRepository.Listener configListener = this::onConfigChanged;

    public static void command(Context context, String action) {
        Intent intent = new Intent(context, BackendService.class).setAction(action);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        graph = ((MainApplication) getApplication()).getGraph();
        server = new LocalHttpServer(graph.config, graph.resources, graph.runtime, graph.events);
        createChannel();
        graph.config.addListener(configListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        startForeground(NOTIFICATION_ID, notification("正在启动"));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requested = false;
            server.stop();
            graph.backend.update(BackendStatus.stopped());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(lastStartId);
            return START_NOT_STICKY;
        }
        requested = true;
        if (ACTION_RESTART.equals(action)) server.stop();
        try {
            server.start();
            graph.backend.update(new BackendStatus(true, server.address(), null));
            notifyState("服务地址 " + server.address());
        } catch (Exception error) {
            graph.events.error("service", "HTTP 服务启动失败", error);
            graph.backend.update(new BackendStatus(false, null, error.getMessage()));
            notifyState("启动失败: " + error.getMessage());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        graph.config.removeListener(configListener);
        server.stop();
        graph.backend.update(BackendStatus.stopped());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onConfigChanged(JSONObject ignored) {
        if (!requested) return;
        server.stop();
        try {
            server.start();
            graph.backend.update(new BackendStatus(true, server.address(), null));
            notifyState("服务地址 " + server.address());
        } catch (Exception error) {
            graph.events.error("service", "配置激活后重新监听失败", error);
            graph.backend.update(new BackendStatus(false, null, error.getMessage()));
            notifyState("重启失败: " + error.getMessage());
        }
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
