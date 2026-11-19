package uk.jj.backchannel

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uk.jj.backchannel.net.TorStatus

/**
 * Keeps the app alive in the background. With no server to hold messages, this phone has to
 * stay connected to Tor for anything to reach it, and Android only allows that from a
 * service with a permanent notification.
 */
class NodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = Notify.service(this, statusText(Runtime.tor.value))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notify.ID_SERVICE, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notify.ID_SERVICE, first)
        }
        if (Runtime.phase.value != Runtime.Phase.OPEN) {
            // Restarted by Android after being cleared from memory: the key is gone.
            Notify.locked(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Off the main thread: this opens sockets and starts the Tor program.
        Thread({ Runtime.startNetwork() }, "bc-start").start()
        scope.launch {
            Runtime.tor.map { statusText(it) }.distinctUntilChanged().collect { text ->
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(Notify.ID_SERVICE, Notify.service(this@NodeService, text))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun statusText(s: TorStatus): String = when (s.phase) {
        TorStatus.Phase.ONLINE -> if (s.reachable) "Connected. Messages can reach you." else "Connected. Announcing your address…"
        TorStatus.Phase.CONNECTING, TorStatus.Phase.STARTING -> "Connecting to Tor…"
        TorStatus.Phase.NO_INTERNET -> "Waiting for an internet connection."
        TorStatus.Phase.FAILED -> "Tor could not start."
        TorStatus.Phase.OFF -> "Starting…"
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, NodeService::class.java))
        }
    }
}
