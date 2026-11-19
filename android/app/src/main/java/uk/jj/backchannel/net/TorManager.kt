package uk.jj.backchannel.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.TorWrapper
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

data class TorStatus(
    val phase: Phase = Phase.OFF,
    /** How far Tor has got with connecting, 0 to 100. */
    val percent: Int = 0,
    /** True once this phone's address has been announced, so contacts can find it. */
    val reachable: Boolean = false,
    val error: String? = null,
) {
    enum class Phase { OFF, STARTING, CONNECTING, ONLINE, NO_INTERNET, FAILED }
}

/**
 * Runs Tor on the phone and gives it an onion address. Tor is a separate program packed
 * inside the app; this class starts it, watches it, and tells it when the internet comes
 * and goes.
 */
class TorManager(private val app: Application, val status: MutableStateFlow<TorStatus> = MutableStateFlow(TorStatus())) {

    @Volatile var socksPort: Int = 0
        private set

    val connected: Boolean get() = status.value.phase == TorStatus.Phase.ONLINE

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "bc-tor").apply { isDaemon = true } }
    private val eventThread = Executors.newSingleThreadExecutor { r -> Thread(r, "bc-tor-events").apply { isDaemon = true } }
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "bc-tor-control").apply { isDaemon = true } }

    @Volatile private var wrapper: AndroidTorWrapper? = null
    @Volatile private var hasInternet = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * @param localPort where the app is listening; Tor passes incoming connections to it.
     * @param savedKey this phone's onion private key from last time, or null on first run.
     * @param onAddress called with the onion address and its private key once Tor has them.
     * @param onConnected called every time Tor (re)connects to the network.
     */
    fun start(localPort: Int, savedKey: String?, onAddress: (onion: String, key: String) -> Unit, onConnected: () -> Unit) {
        control.execute {
            if (wrapper != null) return@execute
            status.value = TorStatus(TorStatus.Phase.STARTING)
            try {
                socksPort = freePort()
                val controlPort = freePort()
                val wakeLocks = AndroidWakeLockManagerFactory.createAndroidWakeLockManager(app)
                val w = AndroidTorWrapper(
                    app, wakeLocks, io, eventThread, architecture(),
                    app.getDir("tor", Context.MODE_PRIVATE), socksPort, controlPort,
                )
                w.setObserver(object : TorWrapper.Observer {
                    override fun onState(s: TorWrapper.TorState) {
                        when (s) {
                            TorWrapper.TorState.CONNECTED -> {
                                status.update { it.copy(phase = TorStatus.Phase.ONLINE, percent = 100, error = null) }
                                onConnected()
                            }
                            TorWrapper.TorState.CONNECTING ->
                                status.update { it.copy(phase = TorStatus.Phase.CONNECTING, error = null) }
                            TorWrapper.TorState.DISABLED ->
                                status.update { it.copy(phase = TorStatus.Phase.NO_INTERNET) }
                            TorWrapper.TorState.STOPPED ->
                                status.update { it.copy(phase = TorStatus.Phase.OFF, reachable = false) }
                            else -> Unit
                        }
                    }

                    override fun onBootstrapPercentage(percentage: Int) {
                        status.update { it.copy(percent = percentage) }
                    }

                    override fun onHsDescriptorUpload(onion: String) {
                        status.update { it.copy(reachable = true) }
                    }

                    override fun onClockSkewDetected(skewSeconds: Long) {
                        status.update { it.copy(error = "This phone's clock looks wrong. Tor needs the right date and time.") }
                    }
                })
                w.start()
                wrapper = w
                val service = w.publishHiddenService(localPort, ONION_PORT, savedKey)
                onAddress(service.onion, service.privKey)
                watchInternet()
                w.enableNetwork(hasInternet)
            } catch (e: Exception) {
                status.value = TorStatus(TorStatus.Phase.FAILED, error = e.message ?: e.javaClass.simpleName)
                try { wrapper?.stop() } catch (_: Exception) {}
                wrapper = null
            }
        }
    }

    /** @param after runs once Tor has fully stopped. */
    fun stop(after: () -> Unit = {}) {
        control.execute {
            unwatchInternet()
            try { wrapper?.stop() } catch (_: Exception) {}
            wrapper = null
            status.value = TorStatus()
            try { after() } catch (_: Exception) {}
        }
    }

    private fun watchInternet() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        hasInternet = cm.activeNetwork != null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = setInternet(true)
            override fun onLost(network: Network) = setInternet(cm.activeNetwork != null)
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: RuntimeException) {
        }
    }

    private fun unwatchInternet() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: RuntimeException) {}
        }
        networkCallback = null
    }

    private fun setInternet(up: Boolean) {
        hasInternet = up
        control.execute {
            try {
                wrapper?.enableNetwork(up)
            } catch (_: IOException) {
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun architecture(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("arm") -> "arm"
            abi == "x86_64" -> "x86_64"
            else -> "x86"
        }
    }

    companion object {
        /** The port other phones connect to on this phone's onion address. */
        const val ONION_PORT = 5223
    }
}
