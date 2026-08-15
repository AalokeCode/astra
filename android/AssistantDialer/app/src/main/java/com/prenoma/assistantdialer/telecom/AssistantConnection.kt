package com.prenoma.assistantdialer.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.prenoma.assistantdialer.sip.CallState
import com.prenoma.assistantdialer.sip.ForegroundCallService
import com.prenoma.assistantdialer.sip.SipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantConnection(
    context: Context,
    address: Uri,
    selfManaged: Boolean,
) : Connection() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext: Context? = context.applicationContext
    private var availableEndpoints: List<CallEndpoint> = emptyList()

    init {
        if (selfManaged) connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        setAudioModeIsVoip(true)
        // PRESENTATION_ALLOWED is declared on TelecomManager, not Connection.
        setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        setCallerDisplayName("ASTRA", TelecomManager.PRESENTATION_ALLOWED)
        setDialing()
        ActiveCall.register(this)

        scope.launch {
            SipManager.callState.collectLatest(::applySipState)
        }

        // Watchdog. SipManager.placeCall posts onto a handler that only exists
        // once the Linphone Core is running, so with no SIP account configured
        // the state never leaves DIALING, nothing ever disconnects us, and the
        // call sticks in the UI with no way to hang up. Telecom will not clean
        // this up on its own — a Connection is ours until we disconnect it.
        scope.launch {
            delay(DIAL_TIMEOUT_MS)
            if (state == STATE_DIALING || state == STATE_INITIALIZING || state == STATE_NEW) {
                Log.w(TAG, "No SIP progress within ${DIAL_TIMEOUT_MS}ms; disconnecting")
                SipManager.hangup()
                setDisconnected(
                    DisconnectCause(
                        DisconnectCause.ERROR,
                        "ASTRA did not answer",
                        "No response from the SIP server. Check the SIP settings and that " +
                            "Asterisk is reachable on this network.",
                        null,
                    ),
                )
                destroy()
                scope.cancel()
            }
        }
    }

    private fun applySipState(state: CallState) {
        when (state) {
            CallState.DIALING -> setDialing()
            CallState.RINGING -> setRinging()
            CallState.CONNECTED -> setActive()
            CallState.HELD -> setOnHold()
            CallState.ENDED -> disconnect(DisconnectCause.REMOTE)
            CallState.ERROR -> disconnect(DisconnectCause.ERROR)
            CallState.IDLE -> if (this.state != STATE_NEW && this.state != STATE_DISCONNECTED) {
                disconnect(DisconnectCause.LOCAL)
            }
        }
    }

    /** Called from the notification's Hang up action via [ActiveCall]. */
    fun disconnectFromUi(code: Int) = disconnect(code)

    private fun disconnect(code: Int) {
        setDisconnected(DisconnectCause(code))
        destroy()
        ActiveCall.clear(this)
        // Nothing else sent ACTION_STOP, so the foreground service outlived every
        // call and left a permanent "SIP service active" notification that the
        // user could not dismiss. Stop it whenever the call it exists for ends.
        appContext?.let { ctx ->
            ctx.startService(
                Intent(ctx, ForegroundCallService::class.java)
                    .setAction(ForegroundCallService.ACTION_STOP),
            )
        }
        scope.cancel()
    }

    /** Called from the UI (speaker/earpiece/bluetooth buttons) via [ActiveCall]. */
    fun routeAudioTo(route: Int) {
        if (Build.VERSION.SDK_INT >= 34) {
            val endpointType = when (route) {
                CallAudioState.ROUTE_SPEAKER -> CallEndpoint.TYPE_SPEAKER
                CallAudioState.ROUTE_BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
                CallAudioState.ROUTE_WIRED_HEADSET -> CallEndpoint.TYPE_WIRED_HEADSET
                else -> CallEndpoint.TYPE_EARPIECE
            }
            val endpoint = availableEndpoints.firstOrNull {
                it.endpointType == endpointType
            }
            val executor = appContext?.mainExecutor
            if (endpoint != null && executor != null) {
                requestCallEndpointChange(
                    endpoint,
                    executor,
                    object : OutcomeReceiver<Void?, CallEndpointException> {
                        override fun onResult(result: Void?) = Unit

                        override fun onError(error: CallEndpointException) {
                            Log.w(TAG, "Unable to select call endpoint $endpointType", error)
                            setLegacyAudioRoute(route)
                        }
                    },
                )
                return
            }
        }
        setLegacyAudioRoute(route)
    }

    @Suppress("DEPRECATION")
    private fun setLegacyAudioRoute(route: Int) {
        setAudioRoute(route)
    }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(endpoints)
        availableEndpoints = endpoints
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        super.onCallEndpointChanged(endpoint)
        val route = when (endpoint.endpointType) {
            CallEndpoint.TYPE_SPEAKER -> CallAudioState.ROUTE_SPEAKER
            CallEndpoint.TYPE_BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            CallEndpoint.TYPE_WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
            else -> CallAudioState.ROUTE_EARPIECE
        }
        SipManager.setAudioRoute(route)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        SipManager.setMuted(state.isMuted)
        SipManager.setAudioRoute(state.route)
    }

    override fun onAnswer() = SipManager.answer()
    override fun onDisconnect() {
        SipManager.hangup()
        disconnect(DisconnectCause.LOCAL)
    }
    override fun onAbort() = onDisconnect()
    override fun onReject() {
        SipManager.hangup()
        disconnect(DisconnectCause.REJECTED)
    }
    override fun onHold() = SipManager.hold()
    override fun onUnhold() = SipManager.unhold()
    override fun onMuteStateChanged(isMuted: Boolean) = SipManager.setMuted(isMuted)

    companion object {
        private const val TAG = "AssistantDialer"

        /** Long enough for a slow LAN registration, short enough not to feel hung. */
        private const val DIAL_TIMEOUT_MS = 20_000L
    }
}
