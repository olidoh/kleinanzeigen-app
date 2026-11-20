package de.olidoh.inserathelfer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.core.CONDITIONS
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.PriceType
import de.olidoh.inserathelfer.share.KleinanzeigenHandoff

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(vm: ListingViewModel) {
    val draft = vm.draft ?: return
    val context = LocalContext.current

    fun copy(label: String, text: String) {
        KleinanzeigenHandoff.copyToClipboard(context, label, text)
        Toast.makeText(context, "$label kopiert", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anzeige prüfen") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.DETAILS }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            CopyableField(
                label = "Titel (${draft.title.length}/${ListingDraft.MAX_TITLE_LENGTH})",
                value = draft.title,
                onChange = { v -> vm.updateDraft { it.copy(title = v.take(ListingDraft.MAX_TITLE_LENGTH)) } },
                onCopy = { copy("Titel", draft.title) },
            )
            CopyableField(
                label = "Beschreibung",
                value = draft.description,
                onChange = { v -> vm.updateDraft { it.copy(description = v.take(ListingDraft.MAX_DESCRIPTION_LENGTH)) } },
                onCopy = { copy("Beschreibung", draft.description) },
                singleLine = false,
            )

            Text("Preis", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriceType.entries.forEach { type ->
                    FilterChip(
                        selected = draft.priceType == type,
                        onClick = { vm.updateDraft { it.copy(priceType = type) } },
                        label = { Text(type.label) },
                    )
                }
            }
            if (draft.priceType != PriceType.FREE) {
                CopyableField(
                    label = "Preis in €",
                    value = draft.priceEur.toString(),
                    onChange = { v -> vm.updateDraft { it.copy(priceEur = v.filter(Char::isDigit).take(7).toIntOrNull() ?: 0) } },
                    onCopy = { copy("Preis", draft.priceEur.toString()) },
                    keyboardType = KeyboardType.Number,
                )
            }
            if (draft.priceReasoning.isNotBlank()) {
                Text("Warum dieser Preis: ${draft.priceReasoning}", style = MaterialTheme.typography.bodySmall)
            }

            CopyableField(
                label = "Kategorie",
                value = draft.category,
                onChange = { v -> vm.updateDraft { it.copy(category = v) } },
                onCopy = { copy("Kategorie", draft.category) },
            )

            Text("Zustand", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CONDITIONS.forEach { c ->
                    FilterChip(
                        selected = draft.condition == c,
                        onClick = { vm.updateDraft { it.copy(condition = c) } },
                        label = { Text(c) },
                    )
                }
            }

            Button(
                onClick = { vm.screen = Screen.POST },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Direkt bei Kleinanzeigen einstellen") }

            OutlinedButton(
                onClick = {
                    val result = KleinanzeigenHandoff.handOff(context, vm.photos.toList(), draft.title, draft.asShareText())
                    val hint = when (result) {
                        KleinanzeigenHandoff.Result.SHARED_TO_APP ->
                            "Text ist in der Zwischenablage. In Kleinanzeigen einfügen und veröffentlichen."
                        KleinanzeigenHandoff.Result.OPENED_APP_PHOTOS_IN_GALLERY ->
                            "Fotos liegen in der Galerie (Inserat-Helfer), der Text in der Zwischenablage."
                        KleinanzeigenHandoff.Result.APP_NOT_INSTALLED ->
                            "Bitte zuerst die Kleinanzeigen-App installieren. Der Text ist kopiert."
                    }
                    Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("An die Kleinanzeigen-App übergeben") }

            OutlinedButton(onClick = vm::startOver, modifier = Modifier.fillMaxWidth()) {
                Text("Neue Anzeige beginnen")
            }
        }
    }
}

@Composable
private fun CopyableField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onCopy: () -> Unit,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 6,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = {
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "$label kopieren") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
