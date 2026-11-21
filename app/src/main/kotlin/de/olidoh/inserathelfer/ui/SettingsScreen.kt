package de.olidoh.inserathelfer.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
    var styleInput by remember { mutableStateOf(vm.styleGuide) }

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

            HorizontalDivider()
            Text("Stil und Tonalität", style = MaterialTheme.typography.titleMedium)
            Text(
                "Diese Vorgabe geht bei jeder Anzeige an die KI mit, egal welcher Anbieter gewählt ist.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = styleInput,
                onValueChange = { styleInput = it.take(MAX_STYLE_LENGTH) },
                label = { Text("Eigene Vorgabe (optional)") },
                placeholder = { Text("z. B. Schreibe immer in der Du-Form, locker und freundlich. Keine Emojis.") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    vm.saveStyleGuide(styleInput)
                    styleInput = vm.styleGuide
                    Toast.makeText(context, if (vm.styleGuide.isEmpty()) "Vorgabe entfernt" else "Vorgabe gespeichert", Toast.LENGTH_SHORT).show()
                },
                enabled = styleInput.trim() != vm.styleGuide,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Vorgabe speichern") }

            EbaySection(vm)

            Text(
                "Deine Kleinanzeigen-Zugangsdaten speichert diese App nicht. Du meldest dich beim Einstellen " +
                    "direkt bei Kleinanzeigen an.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_STYLE_LENGTH = 1000

@Composable
private fun EbaySection(vm: ListingViewModel) {
    val context = LocalContext.current
    val flow = vm.ebay
    val settings = flow.settings
    var advanced by remember { mutableStateOf(!settings.hasBuiltInConfig) }
    var clientId by remember { mutableStateOf(settings.customClientId) }
    var ruName by remember { mutableStateOf(settings.customRuName) }
    var serviceUrl by remember { mutableStateOf(settings.customServiceUrl) }
    var sandbox by remember { mutableStateOf(settings.customSandbox) }

    HorizontalDivider()
    Text("eBay", style = MaterialTheme.typography.titleMedium)
    Text(
        "Zum Einstellen bei eBay meldest du dich einmal mit deinem eBay-Konto an. Dein Passwort geht nur an eBay; " +
            "die App speichert nur den Zugang, verschlüsselt auf diesem Gerät.",
        style = MaterialTheme.typography.bodySmall,
    )
    when {
        !flow.isConfigured -> Text(
            "eBay ist noch nicht eingerichtet. Das ist optional: Kleinanzeigen funktioniert auch ohne. " +
                "Zum Einrichten unten die Daten der eBay-Anwendung eintragen (Anleitung: docs/eBay-Einrichtung.md im Repo).",
            style = MaterialTheme.typography.bodySmall,
        )
        flow.loggedIn -> {
            Text(
                if (flow.accountName.isNotBlank()) "Angemeldet als ${flow.accountName}." else "Bei eBay angemeldet.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    flow.logout()
                    vm.openEbayLogin(Screen.SETTINGS)
                }) { Text("Anderes Konto") }
                OutlinedButton(onClick = flow::logout) { Text("Abmelden") }
            }
        }
        else -> Button(onClick = { vm.openEbayLogin(Screen.SETTINGS) }) { Text("Bei eBay anmelden") }
    }
    TextButton(onClick = { advanced = !advanced }) {
        Text(if (advanced) "Erweiterte eBay-Einstellungen ausblenden" else "Erweiterte eBay-Einstellungen")
    }
    if (advanced) {
        Text(
            "Nur nötig, wenn diese App-Version ohne eBay-Daten gebaut wurde oder du eine eigene eBay-Anwendung " +
                "nutzt. Leere Felder nehmen die eingebauten Werte.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(clientId, { clientId = it }, label = { Text("App ID (Client ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ruName, { ruName = it }, label = { Text("RuName") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            serviceUrl, { serviceUrl = it },
            label = { Text("Adresse des Token-Dienstes") },
            placeholder = { Text("https://…workers.dev") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("eBay-Sandbox (Testumgebung)", modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = sandbox, onCheckedChange = { sandbox = it })
        }
        OutlinedButton(onClick = {
            val changed = settings.customSandbox != sandbox || settings.customClientId != clientId.trim()
            settings.customClientId = clientId
            settings.customRuName = ruName
            settings.customServiceUrl = serviceUrl
            settings.customSandbox = sandbox
            // Ein Zugang gilt nur für die eBay-Anwendung und Umgebung, mit der er erstellt wurde.
            if (changed) flow.logout()
            flow.reloadConfig()
            Toast.makeText(
                context,
                if (flow.isConfigured) "eBay-Einstellungen gespeichert" else "Gespeichert, aber noch unvollständig",
                Toast.LENGTH_SHORT,
            ).show()
        }) { Text("eBay-Einstellungen speichern") }
    }
}
