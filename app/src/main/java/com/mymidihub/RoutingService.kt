package com.mymidihub

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*

class RoutingService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RoutingService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(Snapshot) -> Unit>()
    lateinit var engine: RoutingEngine
        private set
    var snapshot = Snapshot()
        private set
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MIDI routing", NotificationManager.IMPORTANCE_LOW))
        engine = RoutingEngine(this) { next ->
            main.post {
                if (snapshot != next) {
                    snapshot = next
                    listeners.toList().forEach { it(next) }
                    if (foreground) getSystemService(NotificationManager::class.java).notify(1, notification())
                }
            }
        }
    }

    override fun onBind(intent: Intent) = binder

    // A routing session has no time limit. The foreground Stop action and onDestroy
    // release this lock; Android also releases it if the process dies.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRouting()
            return START_NOT_STICKY
        }
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification())
            foreground = true
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "MyMidiHub:Routing").apply { acquire() }
            engine.start()
        }
        return START_STICKY
    }

    fun observe(listener: (Snapshot) -> Unit) { listeners.add(listener); listener(snapshot) }
    fun unobserve(listener: (Snapshot) -> Unit) { listeners.remove(listener) }

    fun stopRouting() {
        engine.stop()
        foreground = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RoutingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("My MIDI Hub is routing")
            .setContentText("${snapshot.active.size} active / ${snapshot.routes.size} saved routes")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop routing", stop).build()).build()
    }

    override fun onDestroy() {
        foreground = false
        engine.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        listeners.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "routing"
        const val STOP = "com.mymidihub.STOP"
    }
}
