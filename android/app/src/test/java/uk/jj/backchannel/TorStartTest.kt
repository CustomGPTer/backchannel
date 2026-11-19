package uk.jj.backchannel

import org.briarproject.onionwrapper.TorWrapper
import org.briarproject.onionwrapper.UnixTorWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.jj.backchannel.core.isOnion
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Starts the real Tor program (the desktop Linux build of the same version that ships in the
 * app) and runs the same sequence of commands the app uses on the phone: start, create an
 * onion address, switch the network on, restart with the saved key.
 *
 * It does not need the internet, so it proves the commands and their order, not that
 * messages cross the Tor network. That can only be shown on real phones.
 */
class TorStartTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test fun torStartsAndGivesAStableOnionAddress() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(System.getProperty("os.arch") in setOf("amd64", "x86_64"))

        val io = Executors.newCachedThreadPool()
        val events = Executors.newSingleThreadExecutor()
        val states = CopyOnWriteArrayList<TorWrapper.TorState>()
        val dir = File(tmp.root, "tor")
        val listen = ServerSocket(0, 5, InetAddress.getLoopbackAddress())

        fun wrapper() = UnixTorWrapper(io, events, "x86_64", dir, freePort(), freePort()).also { w ->
            w.setObserver(object : TorWrapper.Observer {
                override fun onState(s: TorWrapper.TorState) { states += s }
                override fun onBootstrapPercentage(percentage: Int) {}
                override fun onHsDescriptorUpload(onion: String) {}
                override fun onClockSkewDetected(skewSeconds: Long) {}
            })
        }

        // First run: no saved key, so Tor makes a new address.
        val first = wrapper()
        first.start()
        assertTrue(first.isTorRunning)
        val created = first.publishHiddenService(listen.localPort, 5223, null)
        assertTrue("address should be a v3 onion address: ${created.onion}", isOnion(created.onion))
        assertTrue(created.privKey.startsWith("ED25519-V3:"))
        first.enableNetwork(true)
        Thread.sleep(500)
        first.enableNetwork(false)
        first.enableNetwork(true)
        first.stop()

        // Second run: the saved key must bring back the same address, or contacts could never find the phone again.
        val second = wrapper()
        second.start()
        val restored = second.publishHiddenService(listen.localPort, 5223, created.privKey)
        assertEquals(created.onion, restored.onion)
        // A different phone gets a different address.
        val other = second.publishHiddenService(listen.localPort, 5224, null)
        assertNotEquals(created.onion, other.onion)
        second.stop()

        events.shutdown()
        io.shutdownNow()
        listen.close()
        assertTrue("Tor should have reported starting: $states", states.contains(TorWrapper.TorState.STARTED))
        assertTrue(states.contains(TorWrapper.TorState.STOPPED))
    }
}
