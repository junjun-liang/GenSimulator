package com.flyme.auto.gensimulator.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Debug/test bridge started through [BridgeBootstrapActivity]. The foreground notification keeps
 * the localhost server alive after the visible bootstrap activity closes.
 */
class BridgeRuntimeService : Service() {
    private var binderClient: GenCardsBinderClient? = null
    private var httpServer: LocalHttpBridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
        // CREATE goes straight to GenCards' Binder ingress; GenCards opens its own Generation window.
        val client = GenCardsBinderClient(this).also { it.start() }
        binderClient = client
        try {
            httpServer = LocalHttpBridgeServer(client).also { it.start() }
            Log.i(TAG, "Bridge ready on 127.0.0.1:39091")
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start localhost bridge", exception)
            client.close()
            binderClient = null
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        binderClient?.ensureBound()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        httpServer?.close()
        httpServer = null
        binderClient?.close()
        binderClient = null
        leaveForeground()
        super.onDestroy()
    }

    private fun enterForeground() {
        createNotificationChannel()
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GenSimulator Bridge")
            .setContentText("Local ADB bridge is running")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GenSimulator Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun leaveForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private companion object {
        const val TAG = "GenSimulatorBridge"
        const val NOTIFICATION_ID = 39_091
        const val NOTIFICATION_CHANNEL_ID = "gensimulator_bridge"
    }
}
