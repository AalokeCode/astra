package com.prenoma.assistantdialer.sip

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.telecom.CallAudioState
import android.util.Log
import com.prenoma.assistantdialer.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState as LinphoneRegistrationState
import org.linphone.core.TransportType

enum class RegistrationState { STOPPED, CONFIGURING, PROGRESS, REGISTERED, FAILED }
enum class CallState { IDLE, DIALING, RINGING, CONNECTED, HELD, ENDED, ERROR }

object SipManager {
    private val mutableRegistrationState = MutableStateFlow(RegistrationState.STOPPED)
    val registrationState: StateFlow<RegistrationState> = mutableRegistrationState.asStateFlow()

    // Why the last registration attempt failed, in words the user can act on.
    // Log.e alone was useless here: release builds surfaced nothing in logcat, so
    // FAILED was indistinguishable between "no password", "bad address" and
    // "core would not start".
    private val mutableLastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = mutableLastError.asStateFlow()

    private val mutableCallState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = mutableCallState.asStateFlow()

    private val mutableAudioRoute = MutableStateFlow(CallAudioState.ROUTE_EARPIECE)
    val audioRoute: StateFlow<Int> = mutableAudioRoute.asStateFlow()

    private val worker = HandlerThread("linphone-core").apply { start() }
    private val handler = Handler(worker.looper)
    private var core: Core? = null
    private var account: Account? = null
    /** Domain of the registered account, used to qualify bare dial targets. */
    private var domain: String? = null
    /** Telecom can ask us to dial before Linphone finishes registering. */
    private var pendingCallUri: String? = null
    private var requestedAudioRoute = CallAudioState.ROUTE_EARPIECE
    private var started = false

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: LinphoneRegistrationState,
            message: String,
        ) {
            val next = when (state.name.lowercase()) {
                "ok" -> RegistrationState.REGISTERED
                "progress", "refreshing" -> RegistrationState.PROGRESS
                "failed", "cleared" -> RegistrationState.FAILED
                else -> mutableRegistrationState.value
            }
            // Linphone hands us the reason in `message`; this used to go only to
            // Log.i, which release builds strip — so a failed registration showed
            // as a bare "FAILED" with no way to tell a bad password from an
            // unreachable server. Keep it.
            mutableLastError.value = when (next) {
                RegistrationState.FAILED -> message.ifBlank { "Registration failed ($state)" }
                RegistrationState.REGISTERED -> null
                else -> mutableLastError.value
            }
            mutableRegistrationState.value = next
            Log.i(TAG, "SIP registration: $state ($message)")
            if (next == RegistrationState.REGISTERED) {
                pendingCallUri?.let { uri ->
                    pendingCallUri = null
                    placeCallOnCore(uri)
                }
            } else if (next == RegistrationState.FAILED && pendingCallUri != null) {
                pendingCallUri = null
                mutableCallState.value = CallState.ERROR
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            mutableCallState.value = when (state.name.lowercase()) {
                "outgoinginit", "outgoingprogress", "outgoingringing", "outgoingearlymedia" -> CallState.DIALING
                "incomingreceived", "incomingearlymedia" -> CallState.RINGING
                "connected", "streamsrunning", "resuming" -> CallState.CONNECTED
                "pausing", "paused", "pausedbyremote" -> CallState.HELD
                "end", "released" -> CallState.ENDED
                "error" -> CallState.ERROR
                else -> mutableCallState.value
            }
            Log.i(TAG, "SIP call: $state ($message)")
            if (state.name.equals("StreamsRunning", ignoreCase = true) ||
                state.name.equals("Connected", ignoreCase = true)
            ) {
                applyAudioRoute(core, call, requestedAudioRoute)
            }
            if (state.name.equals("Released", ignoreCase = true)) {
                mutableCallState.value = CallState.IDLE
            }
        }

        override fun onAudioDevicesListUpdated(core: Core) {
            core.currentCall?.let { applyAudioRoute(core, it, requestedAudioRoute) }
        }
    }

    private val iterate = object : Runnable {
        override fun run() {
            core?.iterate()
            if (started) handler.postDelayed(this, ITERATE_INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        handler.post {
            if (started) return@post
            mutableRegistrationState.value = RegistrationState.CONFIGURING
            val settings = runBlocking {
                SettingsRepository.get(context).settings.first()
            }
            if (!settings.isConfigured) {
                mutableLastError.value = when {
                    settings.domain.isBlank() -> "SIP domain is empty"
                    settings.username.isBlank() -> "SIP username is empty"
                    settings.password.isBlank() -> "SIP password is empty (it was not saved)"
                    else -> "Settings incomplete"
                }
                mutableRegistrationState.value = RegistrationState.FAILED
                return@post
            }
            mutableLastError.value = null

            runCatching {
                val factory = Factory.instance()
                val nextCore = factory.createCore(null, null, context.applicationContext)
                nextCore.addListener(listener)
                nextCore.setAutoIterateEnabled(false)
                nextCore.setVideoCaptureEnabled(false)
                nextCore.setVideoDisplayEnabled(false)
                nextCore.setVideoPreviewEnabled(false)
                val videoPolicy = nextCore.videoActivationPolicy
                videoPolicy.setAutomaticallyAccept(false)
                videoPolicy.setAutomaticallyInitiate(false)
                nextCore.setVideoActivationPolicy(videoPolicy)

                nextCore.audioPayloadTypes.forEach { payload ->
                    payload.enable(payload.mimeType.uppercase() in ENABLED_CODECS)
                }

                val identity = requireNotNull(
                    factory.createAddress("sip:${settings.username}@${settings.domain}"),
                ) { "Invalid SIP identity" }
                val server = requireNotNull(factory.createAddress("sip:${settings.domain}")) {
                    "Invalid SIP domain"
                }
                val params = nextCore.createAccountParams().apply {
                    setIdentityAddress(identity)
                    setServerAddress(server)
                    setRegisterEnabled(true)
                    setTransport(when (settings.transport.uppercase()) {
                        "TCP" -> TransportType.Tcp
                        "TLS" -> TransportType.Tls
                        else -> TransportType.Udp
                    })
                }
                val auth = factory.createAuthInfo(
                    settings.username,
                    null,
                    settings.password,
                    null,
                    null,
                    settings.domain,
                )
                nextCore.addAuthInfo(auth)
                account = nextCore.createAccount(params).also {
                    nextCore.addAccount(it)
                    nextCore.setDefaultAccount(it)
                }
                check(nextCore.start() == 0) { "Linphone core failed to start" }
                core = nextCore
                domain = settings.domain
                started = true
                mutableRegistrationState.value = RegistrationState.PROGRESS
                handler.post(iterate)
            }.onFailure { error ->
                Log.e(TAG, "Unable to initialize Linphone", error)
                mutableLastError.value = error.message ?: error::class.java.simpleName
                mutableRegistrationState.value = RegistrationState.FAILED
            }
        }
    }

    fun restart(context: Context) {
        handler.post {
            stopCore()
            start(context)
        }
    }

    fun stop() {
        handler.post(::stopCore)
    }

    private fun stopCore() {
        started = false
        handler.removeCallbacks(iterate)
        core?.removeListener(listener)
        core?.stop()
        core = null
        account = null
        domain = null
        pendingCallUri = null
        mutableRegistrationState.value = RegistrationState.STOPPED
        mutableCallState.value = CallState.IDLE
    }

    fun placeCall(uri: String) {
        handler.post {
            if (core == null && mutableRegistrationState.value == RegistrationState.FAILED) {
                mutableCallState.value = CallState.ERROR
                return@post
            }
            if (core == null || mutableRegistrationState.value != RegistrationState.REGISTERED) {
                pendingCallUri = uri
                mutableLastError.value = null
                mutableCallState.value = CallState.DIALING
                return@post
            }
            placeCallOnCore(uri)
        }
    }

    private fun placeCallOnCore(uri: String) {
        val activeCore = core ?: run {
            pendingCallUri = uri
            return
        }

        // Telecom hands us `sip:700` — Uri.fromParts builds a scheme-specific
        // part with no host. Linphone cannot route a bare user, so qualify it.
        val target = qualify(uri)
        val address = activeCore.interpretUrl(target, false)
        if (address == null) {
            mutableLastError.value = "Could not parse dial target '$target'"
            mutableCallState.value = CallState.ERROR
            return
        }
        if (activeCore.inviteAddress(address) == null) {
            mutableLastError.value = "Linphone refused to place the call to $target"
            mutableCallState.value = CallState.ERROR
            return
        }
        mutableLastError.value = null
        mutableCallState.value = CallState.DIALING
    }

    /** Turn `sip:700` / `700` into `sip:700@<registered domain>`. */
    private fun qualify(uri: String): String {
        // Managed Android Telecom accounts may normalize our `sip:700` handle
        // to `tel:700` before it reaches ConnectionService. Passing that value
        // through verbatim produces `sip:tel:700@host`; SIP then interprets
        // `tel` as the extension and Asterisk rejects the call. Peel every
        // supported dial scheme before rebuilding a canonical SIP URI.
        var address = uri.trim()
        while (true) {
            val stripped = when {
                address.startsWith("sips:", ignoreCase = true) -> address.drop(5)
                address.startsWith("sip:", ignoreCase = true) -> address.drop(4)
                address.startsWith("tel:", ignoreCase = true) -> address.drop(4)
                else -> address
            }
            if (stripped == address) break
            address = stripped.trim()
        }
        address = address.substringBefore(';').substringBefore('?').trim()
        if (address.contains('@')) return "sip:$address"
        val host = domain
        return if (host.isNullOrBlank()) "sip:$address" else "sip:$address@$host"
    }

    fun answer() = onCore { it.currentCall?.accept() }
    fun hangup() = onCore { it.currentCall?.terminate() }
    fun setMuted(muted: Boolean) = onCore { it.setMicEnabled(!muted) }
    fun setAudioRoute(route: Int) {
        handler.post {
            requestedAudioRoute = route
            val activeCore = core ?: return@post
            activeCore.currentCall?.let { applyAudioRoute(activeCore, it, route) }
        }
    }
    fun hold() = onCore { it.currentCall?.pause() }
    fun unhold() = onCore { it.currentCall?.resume() }

    private fun onCore(action: (Core) -> Unit) {
        handler.post { core?.let(action) }
    }

    private fun applyAudioRoute(core: Core, call: Call, route: Int) {
        val preferredTypes = when (route) {
            CallAudioState.ROUTE_SPEAKER -> listOf(AudioDevice.Type.Speaker)
            CallAudioState.ROUTE_BLUETOOTH -> listOf(
                AudioDevice.Type.Bluetooth,
                AudioDevice.Type.HearingAid,
                AudioDevice.Type.BluetoothA2DP,
            )
            CallAudioState.ROUTE_WIRED_HEADSET -> listOf(
                AudioDevice.Type.Headset,
                AudioDevice.Type.Headphones,
                AudioDevice.Type.GenericUsb,
            )
            else -> listOf(AudioDevice.Type.Earpiece)
        }
        val output = preferredTypes.firstNotNullOfOrNull { type ->
            core.extendedAudioDevices.firstOrNull { device ->
                device.type == type &&
                    device.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
            }
        }
        if (output == null) {
            Log.w(TAG, "No Linphone playback device for Telecom route $route")
            return
        }
        call.outputAudioDevice = output
        if (output.hasCapability(AudioDevice.Capabilities.CapabilityRecord)) {
            call.inputAudioDevice = output
        }
        mutableAudioRoute.value = route
        Log.i(TAG, "Audio route $route -> ${output.type} (${output.deviceName})")
    }

    private const val TAG = "AssistantDialer"
    private const val ITERATE_INTERVAL_MS = 20L
    private val ENABLED_CODECS = setOf("OPUS", "PCMU", "PCMA")
}
