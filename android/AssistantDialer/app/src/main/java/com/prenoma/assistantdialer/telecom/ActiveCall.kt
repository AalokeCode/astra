package com.prenoma.assistantdialer.telecom

import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import com.prenoma.assistantdialer.sip.SipManager
import java.lang.ref.WeakReference

/**
 * Tracks the one call this app can have in flight.
 *
 * Exists because the notification's "Hang up" action previously only called
 * [SipManager.hangup]. When SIP had never connected that call did nothing, the
 * Telecom connection stayed alive, and there was no way to clear the call from
 * the UI. Hanging up has to tear down the Telecom side too, and only the
 * Connection itself can do that — hence this handle.
 *
 * Held weakly: Telecom owns the Connection's lifetime, and a strong reference
 * here would keep a destroyed call object alive.
 */
object ActiveCall {
    private var connection: WeakReference<AssistantConnection>? = null

    fun register(call: AssistantConnection) {
        connection = WeakReference(call)
    }

    fun clear(call: AssistantConnection) {
        if (connection?.get() === call) connection = null
    }

    val isActive: Boolean get() = connection?.get() != null

    /** Tear down both the SIP call and the Telecom connection. Safe to call twice. */
    fun hangUp() {
        SipManager.hangup()
        connection?.get()?.disconnectFromUi(DisconnectCause.LOCAL)
        connection = null
    }

    /**
     * Where call audio goes. Linphone owns the SIP media stream, so always tell
     * it directly even if Telecom has already released its weak Connection
     * reference. Telecom is still notified when available so Android's system
     * call endpoint stays in sync.
     */
    fun setRoute(route: Int) {
        SipManager.setAudioRoute(route)
        connection?.get()?.routeAudioTo(route)
    }

    /** Current route, or [CallAudioState.ROUTE_EARPIECE] when there is no call. */
    fun currentRoute(): Int =
        SipManager.audioRoute.value

    /** Bluetooth is only offerable when a device is actually connected. */
    fun bluetoothAvailable(): Boolean {
        val mask = connection?.get()?.callAudioState?.supportedRouteMask ?: 0
        return mask and CallAudioState.ROUTE_BLUETOOTH != 0
    }
}
