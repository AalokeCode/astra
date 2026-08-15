package com.prenoma.assistantdialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prenoma.assistantdialer.contacts.ContactProvisioner
import com.prenoma.assistantdialer.data.SettingsRepository
import com.prenoma.assistantdialer.data.SipSettings
import com.prenoma.assistantdialer.sip.CallState
import com.prenoma.assistantdialer.sip.ForegroundCallService
import com.prenoma.assistantdialer.sip.RegistrationState
import com.prenoma.assistantdialer.sip.SipManager
import com.prenoma.assistantdialer.telecom.ActiveCall
import com.prenoma.assistantdialer.telecom.CallCoordinator
import com.prenoma.assistantdialer.telecom.TelecomRegistrar
import com.prenoma.assistantdialer.ui.AssistantTheme
import com.prenoma.assistantdialer.ui.AssistantOrb
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AssistantTheme {
                AssistantApp()
            }
        }
    }

    @Composable
    private fun AssistantApp() {
        var showSettings by remember { mutableStateOf(false) }
        val repository = remember { SettingsRepository.get(this) }
        val settings by repository.settings.collectAsStateWithLifecycle(initialValue = SipSettings())
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

        LaunchedEffect(Unit) {
            permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CALL_PHONE)
                    add(Manifest.permission.READ_PHONE_STATE)
                    if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
        }

        Scaffold { padding ->
            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    repository = repository,
                    onBack = { showSettings = false },
                )
            } else {
                HomeScreen(
                    settings = settings,
                    onOpenSettings = { showSettings = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    @Composable
    private fun HomeScreen(
        settings: SipSettings,
        onOpenSettings: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val registration by SipManager.registrationState.collectAsStateWithLifecycle()
        val call by SipManager.callState.collectAsStateWithLifecycle()
        val route by SipManager.audioRoute.collectAsStateWithLifecycle()
        val lastError by SipManager.lastError.collectAsStateWithLifecycle()
        var muted by remember { mutableStateOf(false) }

        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ASTRA", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onOpenSettings) { Text("SETTINGS") }
                }
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
                StatusLine("SIP", registration.name)
                StatusLine("CALL", call.name)
                if (!settings.isConfigured) {
                    Spacer(Modifier.height(16.dp))
                    Text("Add the Mac LAN IP and SIP credentials in Settings before calling.")
                }
                lastError?.let { reason ->
                    Spacer(Modifier.height(12.dp))
                    Text(reason, color = MaterialTheme.colorScheme.error)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                AssistantOrb(
                    state = call,
                    enabled = call == CallState.IDLE || call == CallState.ERROR || call == CallState.ENDED,
                    onClick = {
                        if (!settings.isConfigured) {
                            onOpenSettings()
                        } else {
                            startForegroundService(Intent(this@MainActivity, ForegroundCallService::class.java))
                            CallCoordinator.placeAssistantCall(this@MainActivity, settings.assistantNumber)
                                .onFailure { toast(it.message ?: "Unable to place call") }
                        }
                    },
                )
            }

            // Linphone owns SIP media while Telecom owns Android's call endpoint.
            // ActiveCall updates both; the highlighted route is emitted only
            // after Linphone has actually selected a matching playback device.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val inCall = call == CallState.DIALING ||
                    call == CallState.RINGING ||
                    call == CallState.CONNECTED ||
                    call == CallState.HELD
                OutlinedButton(
                    onClick = { ActiveCall.setRoute(CallAudioState.ROUTE_SPEAKER) },
                    modifier = Modifier.weight(1f),
                    enabled = inCall,
                ) { Text(if (route == CallAudioState.ROUTE_SPEAKER) "● SPKR" else "SPKR") }
                OutlinedButton(
                    onClick = { ActiveCall.setRoute(CallAudioState.ROUTE_EARPIECE) },
                    modifier = Modifier.weight(1f),
                    enabled = inCall,
                ) { Text(if (route == CallAudioState.ROUTE_EARPIECE) "● PHONE" else "PHONE") }
                OutlinedButton(
                    onClick = { ActiveCall.setRoute(CallAudioState.ROUTE_BLUETOOTH) },
                    modifier = Modifier.weight(1f),
                    enabled = inCall && ActiveCall.bluetoothAvailable(),
                ) { Text(if (route == CallAudioState.ROUTE_BLUETOOTH) "● WATCH" else "WATCH") }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        muted = !muted
                        SipManager.setMuted(muted)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = call != CallState.IDLE,
                ) { Text(if (muted) "UNMUTE" else "MUTE") }
                Button(
                    onClick = { ActiveCall.hangUp() },
                    modifier = Modifier.weight(1f),
                    enabled = call != CallState.IDLE,
                ) { Text("HANG UP") }
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, value: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
            Text(value, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun SettingsScreen(
        settings: SipSettings,
        repository: SettingsRepository,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        // Seed the form from stored settings, but never clobber the user's typing.
        //
        // This was `remember(settings) { ... }` — keyed on the DataStore flow, so
        // every emission recreated the state and discarded in-progress edits. The
        // flow emits at least twice on load (default, then stored) and again after
        // each save, so a typed SIP password could be wiped before save() ran. It
        // then persisted blank, and registration looped on 401 forever with no
        // error shown anywhere.
        var draft by remember { mutableStateOf(settings) }
        var userEdited by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(settings) { if (!userEdited) draft = settings }
        val edit: (SipSettings) -> Unit = { updated ->
            userEdited = true
            draft = updated
        }
        // The TEST REGISTRATION button previously gave no feedback at all — no
        // toast, and this screen never showed registration state — so a working
        // button was indistinguishable from a stub. Surface the live state here.
        val regState by SipManager.registrationState.collectAsStateWithLifecycle()
        val regError by SipManager.lastError.collectAsStateWithLifecycle()
        val roleManager = remember { getSystemService(RoleManager::class.java) }
        val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            toast(if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) "Call redirection enabled" else "Call redirection was not granted; use Call with → ASTRA")
        }
        val contactPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (grants.values.all { it }) createContact(draft.assistantNumber)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SIP SETTINGS", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                TextButton(onClick = onBack) { Text("DONE") }
            }
            OutlinedTextField(
                value = draft.domain,
                onValueChange = { edit(draft.copy(domain = it)) },
                label = { Text("Mac LAN IP / SIP domain") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { edit(draft.copy(username = it)) },
                label = { Text("SIP username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { edit(draft.copy(password = it)) },
                label = { Text("SIP password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.assistantNumber,
                onValueChange = { edit(draft.copy(assistantNumber = it.filter(Char::isDigit))) },
                label = { Text("ASTRA number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("Transport", color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("UDP", "TCP", "TLS").forEach { transport ->
                    OutlinedButton(onClick = { edit(draft.copy(transport = transport)) }) {
                        Text(if (draft.transport == transport) "● $transport" else transport)
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        repository.save(draft)
                        toast("Settings saved")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SAVE") }
            Text(
                "Registration: ${regState.name}",
                color = when (regState) {
                    RegistrationState.REGISTERED -> MaterialTheme.colorScheme.primary
                    RegistrationState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.secondary
                },
                fontFamily = FontFamily.Monospace,
            )
            regError?.let { reason ->
                Text(reason, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (draft.password.isBlank()) {
                            toast("Enter the SIP password first")
                            return@launch
                        }
                        repository.save(draft)
                        runCatching {
                            startForegroundService(Intent(this@MainActivity, ForegroundCallService::class.java))
                            SipManager.restart(applicationContext)
                        }.onSuccess {
                            toast("Registering ${draft.username}@${draft.domain}...")
                        }.onFailure {
                            toast("Could not start SIP: ${it.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("TEST REGISTRATION") }

            Spacer(Modifier.height(8.dp))
            Text("DIALER INTEGRATION", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)

            // Android registers the managed (call-provider) account but leaves it
            // DISABLED until the user opts in. Until then every call falls back to
            // the self-managed account, which by design bypasses the system in-call
            // UI and is not mirrored to Bluetooth — so the call appears only inside
            // this app and never on a paired watch. Nothing in the app can enable
            // it; only the user can, so say so and open the exact screen.
            val managedEnabled = TelecomRegistrar.isCallProviderEnabled(this@MainActivity)
            Text(
                if (managedEnabled) {
                    "Calling account: ENABLED — calls use the system dialer UI and appear on a paired watch."
                } else {
                    "Calling account: DISABLED — calls show only inside this app and will NOT appear on your watch. " +
                        "Enable \"ASTRA Dialer\" below, then return here."
                },
                color = if (managedEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!managedEnabled) {
                Button(
                    onClick = {
                        runCatching {
                            startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
                        }.onFailure {
                            // Not every OEM exposes that screen directly.
                            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                            toast("Open Settings > Apps > Default apps > Calling accounts")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ENABLE CALLING ACCOUNT") }
            }
            Text("Redirection only intercepts the configured ASTRA number. Every other number is explicitly passed to the cellular dialer unchanged.")
            OutlinedButton(
                onClick = {
                    if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION)) {
                        toast("Call redirection is unavailable on this device")
                    } else if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) {
                        toast("Call redirection is already enabled")
                    } else {
                        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("REQUEST REDIRECTION ROLE") }
            OutlinedButton(
                onClick = {
                    val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                    if (permissions.all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }) {
                        createContact(draft.assistantNumber)
                    } else {
                        contactPermissionLauncher.launch(permissions)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("CREATE ASTRA CONTACT") }
            Text("After creating the contact, run a contact sync in the watch companion app before dialing from the watch.")
            Spacer(Modifier.height(24.dp))
        }
    }

    private fun createContact(number: String) {
        runCatching { ContactProvisioner.createAssistantContact(this, number) }
            .onSuccess { created -> toast(if (created) "ASTRA contact created" else "ASTRA contact already exists") }
            .onFailure { toast(it.message ?: "Unable to create contact") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
