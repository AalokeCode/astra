package com.prenoma.assistantdialer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "assistant_settings")

data class SipSettings(
    val domain: String = "",
    val username: String = "700",
    val password: String = "",
    val assistantNumber: String = "700",
    val transport: String = "UDP",
) {
    /**
     * A password is required, not optional.
     *
     * Without it Linphone answers Asterisk's digest challenge with no
     * Authorization header, so the server re-challenges and registration loops
     * on 401 forever with no error surfaced anywhere in the app. Treating a
     * blank password as "configured" turned a typo into a silent hang.
     */
    val isConfigured: Boolean
        get() = domain.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

class SettingsRepository private constructor(private val context: Context) {
    private object Keys {
        val domain = stringPreferencesKey("sip_domain")
        val username = stringPreferencesKey("sip_username")
        val password = stringPreferencesKey("sip_password")
        val assistantNumber = stringPreferencesKey("assistant_number")
        val transport = stringPreferencesKey("sip_transport")
    }

    val settings: Flow<SipSettings> = context.settingsDataStore.data.map { preferences ->
        SipSettings(
            domain = preferences[Keys.domain].orEmpty(),
            username = preferences[Keys.username] ?: "700",
            password = preferences[Keys.password].orEmpty(),
            assistantNumber = preferences[Keys.assistantNumber] ?: "700",
            transport = preferences[Keys.transport] ?: "UDP",
        )
    }

    suspend fun save(value: SipSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.domain] = value.domain.trim()
            preferences[Keys.username] = value.username.trim()
            preferences[Keys.password] = value.password
            preferences[Keys.assistantNumber] = value.assistantNumber.filter(Char::isDigit).ifBlank { "700" }
            preferences[Keys.transport] = value.transport.uppercase()
        }
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }
    }
}
