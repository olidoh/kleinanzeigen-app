package de.olidoh.inserathelfer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.core.AiProvider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ListingViewModel) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(vm.settings.selected) }
    // Pro Anbieter neu laden, wenn die Auswahl wechselt.
    var keyInput by remember(provider) { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf(vm.settings.model(provider)) }
    var baseUrl by remember(provider) { mutableStateOf(vm.settings.baseUrl(provider)) }
    var storedKey by remember(provider) { mutableStateOf(vm.settings.apiKey(provider)) }

    val isCustom = provider == AiProvider.CUSTOM
    val canSave = (storedKey != null || keyInput.isNotBlank()) &&
        model.isNotBlank() &&
        (!provider.hasEditableBaseUrl || baseUrl.trim().startsWith("https://"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KI-Anbieter") },
                navigationIcon = {
                    if (vm.aiConfig != null) {
                        IconButton(onClick = { vm.screen = Screen.CAPTURE }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Wähle, welche KI Titel, Beschreibung und Preis schreibt. Du brauchst dafür einen eigenen " +
                    "API-Schlüssel des Anbieters, die Kosten laufen über dein Konto dort. Schlüssel werden " +
                    "verschlüsselt im Android Keystore dieses Geräts gespeichert und nur an den jeweiligen " +
                    "Anbieter gesendet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Column {
                AiProvider.entries.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = provider == p, role = Role.RadioButton, onClick = { provider = p }),
                    ) {
                        RadioButton(selected = provider == p, onClick = null)
                        val suffix = if (vm.settings.apiKey(p) != null) " · Schlüssel gespeichert" else ""
                        Text(p.label + suffix, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
            }

            HorizontalDivider()
            Text(provider.label, style = MaterialTheme.typography.titleMedium)
            Text(provider.hint, style = MaterialTheme.typography.bodySmall)
            if (provider.keyUrl.isNotEmpty()) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)))
                }) { Text("Schlüssel beim Anbieter erstellen") }
            }

            storedKey?.let { key ->
                Text("Ein Schlüssel ist gespeichert (…${key.takeLast(4)}).")
                OutlinedButton(onClick = {
                    vm.removeKey(provider)
                    storedKey = null
                }) { Text("Schlüssel entfernen") }
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (storedKey != null) "Neuen Schlüssel eingeben (optional)" else "API-Schlüssel") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Modell") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider.hasEditableBaseUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Basis-URL") },
                    placeholder = { if (isCustom) Text("https://…/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { vm.saveProvider(provider, keyInput, model, baseUrl) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern und verwenden") }

            Text(
                "Deine Kleinanzeigen-Zugangsdaten braucht diese App nicht: Die Anzeige wird an die " +
                    "offizielle Kleinanzeigen-App übergeben, in der du angemeldet bleibst.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
