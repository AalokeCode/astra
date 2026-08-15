package com.prenoma.assistantdialer.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object CallCoordinator {
    fun placeAssistantCall(context: Context, number: String = "700"): Result<Unit> = runCatching {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for SIP calls"
        }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            "Phone permission is required to create the Telecom call"
        }
        TelecomRegistrar.register(context)
        val telecom = context.getSystemService(TelecomManager::class.java)

        // Prefer the managed (call-provider) account: it uses the system in-call
        // UI and is mirrored to Bluetooth HFP devices, which is what makes the
        // call appear as a normal call on a paired watch. The self-managed
        // account is the fallback — it works, but stays invisible to the watch.
        val managed = TelecomRegistrar.callProviderHandle(context)
        val account = if (TelecomRegistrar.isCallProviderEnabled(context) &&
            telecom.isOutgoingCallPermitted(managed)
        ) {
            managed
        } else {
            TelecomRegistrar.accountHandle(context)
        }

        check(telecom.isOutgoingCallPermitted(account)) {
            "Android Telecom is blocking outgoing calls for this account. Enable " +
                "\"ASTRA (Call with)\" under Settings > Apps > Default apps > Calling accounts."
        }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
        }
        telecom.placeCall(Uri.fromParts(PhoneAccountScheme.SIP, number, null), extras)
    }
}

private object PhoneAccountScheme {
    const val SIP = "sip"
}
