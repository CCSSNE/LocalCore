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
    private final ConfigRepository.Listener configListener = this::onConfigChanged;

    public static void command(Context context, String action) {
        Intent intent = new Intent(context, BackendService.class).setAction(action);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        graph = ((MainApplication) getApplication()).graph();
        server = new LocalHttpServer(graph.config, graph.resources, graph.runtime, graph.events);
        createChannel();
        graph.config.addListener(configListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("姝ｅ湪鍚姩"));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requested = false;
            server.stop();
            graph.backend.update(BackendStatus.stopped());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        requested = true;
        if (ACTION_RESTART.equals(action)) server.stop();
        try {
            server.start();
            graph.backend.update(new BackendStatus(true, server.address(), null));
            notifyState("鏈嶅姟鍦板潃 " + server.address());
        } catch (Exception error) {
            graph.events.error("service", "HTTP 鏈嶅姟鍚姩澶辫触", error);
            graph.backend.update(new BackendStatus(false, null, error.getMessage()));
            notifyState("鍚姩澶辫触: " + error.getMessage());
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
            notifyState("鏈嶅姟鍦板潃 " + server.address());
        } catch (Exception error) {
            graph.events.error("service", "閰嶇疆婵€娲诲悗閲嶆柊鐩戝惉澶辫触", error);
            graph.backend.update(new BackendStatus(false, null, error.getMessage()));
            notifyState("閲嶅惎澶辫触: " + error.getMessage());
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "LocalCore 鍚庣",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("鏈湴 LLM HTTP 鏈嶅姟涓庤祫婧愪换鍔＄姸鎬?);
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
