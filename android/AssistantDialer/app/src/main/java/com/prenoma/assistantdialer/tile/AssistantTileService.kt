package com.prenoma.assistantdialer.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.prenoma.assistantdialer.data.SettingsRepository
import com.prenoma.assistantdialer.telecom.CallCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AssistantTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "ASTRA"
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val settings = runBlocking { SettingsRepository.get(applicationContext).settings.first() }
        if (!settings.isConfigured) {
            Toast.makeText(this, "Configure SIP settings first", Toast.LENGTH_LONG).show()
            return
        }
        CallCoordinator.placeAssistantCall(this, settings.assistantNumber)
            .onFailure {
                Toast.makeText(this, it.message ?: "Unable to place call", Toast.LENGTH_LONG).show()
            }
    }
}
