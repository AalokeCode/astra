package com.prenoma.assistantdialer.telecom

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.prenoma.assistantdialer.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AssistantRedirectionService : CallRedirectionService() {
    override fun onPlaceCall(
        handle: Uri,
        initialPhoneAccount: PhoneAccountHandle,
        allowInteractiveResponse: Boolean,
    ) {
        // A call launched by CallCoordinator already targets ASTRA's managed
        // PhoneAccount. Redirecting that call again cancels it and recursively
        // creates replacements until Telecom falls back to the self-managed
        // account. Besides duplicate SIP INVITEs, that fallback also removes the
        // call from Phone by Google and its connected wearable surfaces.
        if (initialPhoneAccount.componentName.packageName == packageName) {
            Log.i(TAG, "Passing ASTRA PhoneAccount call through unchanged")
            placeCallUnmodified()
            return
        }

        // This method sits in front of EVERY outgoing call on the device, and
        // Telecom drops the call if we do not respond within a few seconds. So
        // every failure path here must end in placeCallUnmodified() — breaking
        // the user's ability to make ordinary phone calls is far worse than
        // failing to reach ASTRA.
        val configuredNumber = try {
            runBlocking {
                withTimeout(SETTINGS_READ_TIMEOUT_MS) {
                    SettingsRepository.get(applicationContext).settings.first().assistantNumber
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read settings; passing call through", t)
            placeCallUnmodified()
            return
        }

        val target = normalize(configuredNumber)
        if (target.isEmpty()) {
            // Unconfigured. Without this guard an empty target matches any
            // handle that normalizes to empty (SIP URIs, non-numeric handles),
            // and we would cancel a call that was never meant for us.
            Log.i(TAG, "No assistant number configured; passing call through")
            placeCallUnmodified()
            return
        }

        val dialed = normalize(handle.schemeSpecificPart)
        if (dialed.isEmpty() || dialed != target) {
            Log.i(TAG, "Passing non-assistant call through unchanged")
            placeCallUnmodified()
            return
        }

        // Respond before Telecom's five-second deadline, then create a distinct SIP call.
        cancelCall()
        Handler(Looper.getMainLooper()).post {
            CallCoordinator.placeAssistantCall(applicationContext, configuredNumber)
                .onFailure { Log.e(TAG, "Unable to start assistant SIP call", it) }
        }
    }

    private fun normalize(value: String): String =
        PhoneNumberUtils.normalizeNumber(value).filter(Char::isDigit)

    companion object {
        private const val TAG = "AssistantDialer"

        /** Well inside Telecom's response deadline, leaving room to pass through. */
        private const val SETTINGS_READ_TIMEOUT_MS = 1_500L
    }
}
