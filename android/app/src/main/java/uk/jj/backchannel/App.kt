package uk.jj.backchannel

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannels(this)
        // Remove any half-finished photo or recording left from last time.
        uk.jj.backchannel.media.Scratch.clear(this)
        Runtime.init(this)

        // Lock the screens when the app has been out of sight for a while.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var leftAt = 0L

            override fun onStop(owner: LifecycleOwner) {
                leftAt = android.os.SystemClock.elapsedRealtime()
                Runtime.appVisible = false
            }

            override fun onStart(owner: LifecycleOwner) {
                Runtime.appVisible = true
                if (leftAt != 0L) Runtime.cameBack(android.os.SystemClock.elapsedRealtime() - leftAt)
                Notify.clearMessage(this@App)
            }
        })
    }
}
