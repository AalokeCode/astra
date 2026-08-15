package com.prenoma.assistantdialer.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

object TelecomRegistrar {
    private const val SELF_MANAGED_ACCOUNT_ID = "prenoma-assistant-self-managed"
    private const val CALL_PROVIDER_ACCOUNT_ID = "prenoma-assistant-call-provider"

    fun accountHandle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, AssistantConnectionService::class.java),
        SELF_MANAGED_ACCOUNT_ID,
    )

    /**
     * The managed (call-provider) account.
     *
     * Prefer this for outgoing calls. A self-managed call deliberately bypasses
     * the system in-call UI, and most Bluetooth HFP implementations only mirror
     * managed calls — so a self-managed call does not appear as a normal call on
     * a paired watch or headset, which defeats the point of the watch trigger.
     */
    fun callProviderHandle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, AssistantConnectionService::class.java),
        CALL_PROVIDER_ACCOUNT_ID,
    )

    /** True when the user has enabled the managed account in Settings -> Calling accounts. */
    fun isCallProviderEnabled(context: Context): Boolean = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val telecom = context.getSystemService(TelecomManager::class.java)
        telecom.callCapablePhoneAccounts.contains(callProviderHandle(context))
    }.getOrDefault(false)

    fun register(context: Context) {
        val extras = Bundle().apply {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                putBoolean(PhoneAccount.EXTRA_ALWAYS_USE_VOIP_AUDIO_MODE, true)
            }
        }
        val selfManagedAccount = PhoneAccount.builder(accountHandle(context), "ASTRA SIP")
            .setAddress(Uri.parse("sip:assistant"))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
            .setExtras(extras)
            .build()
        val callProviderAccount = PhoneAccount.builder(
            callProviderHandle(context),
            "ASTRA (Call with)",
        )
            .setAddress(Uri.parse("sip:assistant"))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
            .setExtras(extras)
            .build()

        context.getSystemService(TelecomManager::class.java).apply {
            registerPhoneAccount(selfManagedAccount)
            runCatching { registerPhoneAccount(callProviderAccount) }
                .onFailure { Log.e("AssistantDialer", "Unable to register call-provider fallback", it) }
        }
    }
}
