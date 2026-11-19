package uk.jj.backchannel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart or an update the app is sealed, so remind the user to unlock it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Runtime.phase.value == Runtime.Phase.SEALED) Notify.locked(context)
    }
}
