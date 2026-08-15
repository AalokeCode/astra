package com.prenoma.assistantdialer.telecom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import androidx.core.content.ContextCompat
import com.prenoma.assistantdialer.sip.ForegroundCallService
import com.prenoma.assistantdialer.sip.SipManager

class AssistantConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        }
        val serviceIntent = Intent(this, ForegroundCallService::class.java)
        startForegroundService(serviceIntent)

        // android.telecom.DisconnectCause has no INVALID_NUMBER — that constant
        // belongs to the hidden telephony class. ERROR is the documented code
        // for a request we cannot service.
        val address = request.address ?: return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR, "No number was supplied for the call."),
        )
        val connection = AssistantConnection(
            context = this,
            address = address,
            selfManaged = request.accountHandle == TelecomRegistrar.accountHandle(this),
        )
        SipManager.placeCall(address.toString())
        return connection
    }
}
