package de.olidoh.inserathelfer.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.core.ebay.EbayMapping
import de.olidoh.inserathelfer.core.ebay.EbayPolicy

/**
 * Angaben, die eBay zusätzlich zu Titel, Text und Fotos braucht: Kategorie, Pflichtmerkmale, Zustand,
 * Versand/Zahlung/Rücknahme und Artikelstandort. Vorschläge kommen von eBay, alles bleibt änderbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbayPostScreen(vm: ListingViewModel) {
    val draft = vm.draft ?: return
    val flow = vm.ebay
    val context = LocalContext.current
    var showOptional by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bei eBay einstellen") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.REVIEW }) {
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
            flow.busy?.let {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            flow.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            flow.result?.let { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bei eBay online (Artikel ${r.listingId})", style = MaterialTheme.typography.titleSmall)
                        r.warnings.forEach { Text("Hinweis von eBay: $it", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            "Erneutes Einstellen aktualisiert dieses Angebot.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }) {
                            Text("Angebot bei eBay öffnen")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = flow.title,
                onValueChange = { flow.title = it.take(EbayMapping.MAX_TITLE_LENGTH) },
                label = { Text("Titel (${flow.title.length}/${EbayMapping.MAX_TITLE_LENGTH})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = if (flow.priceEur > 0) flow.priceEur.toString() else "",
                onValueChange = { v -> flow.priceEur = v.filter(Char::isDigit).take(7).toIntOrNull() ?: 0 },
                label = { Text("Sofort-Kaufen-Preis in €") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Preisvorschläge erlauben", style = MaterialTheme.typography.titleSmall)
                    Text("Entspricht „VB“ bei Kleinanzeigen.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = flow.bestOffer, onCheckedChange = { flow.bestOffer = it })
            }
            Text("Beschreibung und Fotos kommen aus der Prüfansicht.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("Kategorie", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = flow.categoryQuery,
                    onValueChange = { flow.categoryQuery = it },
                    label = { Text("Suchbegriff") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = flow::searchCategories, enabled = flow.busy == null) { Text("Suchen") }
            }
            flow.categories.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = flow.category?.id == c.id,
                            enabled = flow.busy == null,
                            role = Role.RadioButton,
                            onClick = { flow.selectCategory(c) },
                        ),
                ) {
                    RadioButton(selected = flow.category?.id == c.id, onClick = null)
                    Text(c.path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                }
            }

            if (flow.category != null) {
                if (flow.conditions.isNotEmpty()) {
                    ChoiceField(
                        label = "Zustand (bei Kleinanzeigen: ${draft.condition})",
                        value = flow.condition?.label.orEmpty(),
                        options = flow.conditions.map { it.label },
                        freeText = false,
                        onChange = { label -> flow.condition = flow.conditions.firstOrNull { it.label == label } },
                    )
                }

                val required = flow.aspects.filter { it.required }
                val optional = flow.aspects.filterNot { it.required }
                if (required.isNotEmpty()) Text("Pflichtangaben von eBay", style = MaterialTheme.typography.titleSmall)
                required.forEach { a ->
                    ChoiceField(
                        label = a.name + " *",
                        value = flow.aspectValues[a.name].orEmpty(),
                        options = a.values,
                        freeText = a.freeText,
                        onChange = { flow.aspectValues[a.name] = it },
                    )
                }
                if (optional.isNotEmpty()) {
                    TextButton(onClick = { showOptional = !showOptional }) {
                        Text(if (showOptional) "Weitere Merkmale ausblenden" else "Weitere Merkmale (${optional.size}, freiwillig)")
                    }
                    if (showOptional) optional.forEach { a ->
                        ChoiceField(
                            label = a.name,
                            value = flow.aspectValues[a.name].orEmpty(),
                            options = a.values,
                            freeText = a.freeText,
                            onChange = { flow.aspectValues[a.name] = it },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Versand, Zahlung, Rücknahme", style = MaterialTheme.typography.titleMedium)
            val policies = flow.policies
            if (policies != null && !policies.isComplete) {
                Text(
                    "In deinem eBay-Konto fehlen noch Grundsätze für Versand, Zahlung oder Rücknahme. Lege sie bei " +
                        "eBay unter „Mein eBay > Konto > Grundsätze für den Verkauf“ an (die App hat die Funktion " +
                        "dafür eingeschaltet, das kann bei eBay bis zu einem Tag dauern). Danach hier zurückkommen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (policies != null) {
                PolicyField("Versand", policies.fulfillment, flow.fulfillmentId) { flow.fulfillmentId = it }
                PolicyField("Zahlung", policies.payment, flow.paymentId) { flow.paymentId = it }
                PolicyField("Rücknahme", policies.returns, flow.returnId) { flow.returnId = it }
            }

            Text("Artikelstandort", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = flow.postalCode,
                    onValueChange = { flow.postalCode = it.filter(Char::isDigit).take(5) },
                    label = { Text("PLZ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = flow.city,
                    onValueChange = { flow.city = it },
                    label = { Text("Ort") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }

            val missing = flow.missingRequired
            if (missing.isNotEmpty()) {
                Text("Noch offen: ${missing.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { flow.publish(vm.ebaySku, draft, vm.photos.toList(), vm::onEbayPublished) },
                enabled = flow.canPublish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (flow.result != null) "Angebot aktualisieren" else "Jetzt bei eBay einstellen") }
            Text(
                "Das Angebot geht sofort online (Festpreis, gültig bis auf Widerruf). eBay berechnet dafür ggf. Gebühren.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    flow.logout()
                    vm.screen = Screen.REVIEW
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Von eBay abmelden") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyField(label: String, options: List<EbayPolicy>, selectedId: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    ChoiceField(
        label = label,
        value = options.firstOrNull { it.id == selectedId }?.name.orEmpty(),
        options = options.map { it.name },
        freeText = false,
        onChange = { name -> options.firstOrNull { it.name == name }?.let { onSelect(it.id) } },
    )
}

/** Auswahlfeld; bei [freeText] auch frei beschreibbar, die Vorschläge filtern dann mit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(label: String, value: String, options: List<String>, freeText: Boolean, onChange: (String) -> Unit) {
    if (options.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (freeText && value.isNotBlank()) {
        options.filter { it.contains(value, ignoreCase = true) }
    } else {
        options
    }.take(MAX_SHOWN_OPTIONS)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (freeText) { onChange(it); expanded = true } },
            readOnly = !freeText,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(if (freeText) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
            shown.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val MAX_SHOWN_OPTIONS = 50
