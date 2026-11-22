package de.olidoh.inserathelfer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.core.ListingGenerator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(vm: ListingViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Angaben zum Artikel") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.CAPTURE }) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Optional: Was sollte die KI wissen, das man auf den Fotos nicht sieht? " +
                    "Zum Beispiel Mängel, Alter, Lieferumfang, Neupreis oder deine Preisvorstellung.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = vm.sellerNotes,
                onValueChange = { vm.sellerNotes = it },
                label = { Text("Hinweise (optional)") },
                placeholder = { Text("z. B. 2 Jahre alt, kleiner Kratzer am Rand, mit Ladekabel") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )
            if (vm.photos.size > ListingGenerator.MAX_IMAGES) {
                Text(
                    "Die KI sieht sich die ersten ${ListingGenerator.MAX_IMAGES} Fotos an. " +
                        "Übergeben werden trotzdem alle ${vm.photos.size}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = vm::generate,
                enabled = !vm.isGenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.isGenerating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("KI schreibt die Anzeige …")
                    }
                } else {
                    Text("Anzeige mit KI erstellen")
                }
            }
        }
    }
}
