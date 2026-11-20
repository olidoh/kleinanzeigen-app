package de.olidoh.inserathelfer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ListingViewModel) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val hasKey = vm.apiKey != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    if (hasKey) {
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Claude-API-Schlüssel", style = MaterialTheme.typography.titleMedium)
            Text(
                "Die App schreibt Titel, Beschreibung und Preis mit Claude. Dafür brauchst du einen " +
                    "eigenen API-Schlüssel von Anthropic. Die Kosten (wenige Cent pro Anzeige) laufen über " +
                    "dein Anthropic-Konto. Der Schlüssel wird verschlüsselt im Android Keystore dieses " +
                    "Geräts gespeichert und nur an api.anthropic.com gesendet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
            }) { Text("Schlüssel bei Anthropic erstellen") }

            if (hasKey) {
                Text("Ein Schlüssel ist gespeichert (…${vm.apiKey!!.takeLast(4)}).")
                OutlinedButton(onClick = { vm.removeApiKey() }) { Text("Schlüssel entfernen") }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (hasKey) "Neuen Schlüssel eingeben" else "API-Schlüssel (sk-ant-…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveApiKey(input) },
                enabled = input.trim().startsWith("sk-ant-"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }

            Text(
                "Deine Kleinanzeigen-Zugangsdaten braucht diese App nicht: Die Anzeige wird an die " +
                    "offizielle Kleinanzeigen-App übergeben, in der du angemeldet bleibst.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
