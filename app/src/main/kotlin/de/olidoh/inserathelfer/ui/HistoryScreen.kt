package de.olidoh.inserathelfer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.core.SavedListing
import java.text.DateFormat
import java.util.Date

/** „Meine Anzeigen“: zuletzt erstellte Anzeigen mit Fotos und Text, zum Ansehen oder späteren Veröffentlichen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: ListingViewModel) {
    var toDelete by remember { mutableStateOf<SavedListing?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Anzeigen") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.CAPTURE }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        if (vm.savedListings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine Anzeigen gespeichert. Jede Anzeige, die die KI erstellt, landet automatisch hier.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(vm.savedListings, key = { it.id }) { listing ->
                ListingCard(listing, onOpen = { vm.openSaved(listing) }, onDelete = { toDelete = listing })
            }
        }
    }

    toDelete?.let { listing ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Anzeige löschen?") },
            text = { Text("„${listing.draft.title}“ wird samt Fotos aus der App entfernt. Bei Kleinanzeigen ändert sich nichts.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSaved(listing); toDelete = null }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ListingCard(listing: SavedListing, onOpen: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = listing.photos.firstOrNull()
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Image, contentDescription = null) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    listing.draft.title.ifBlank { "(ohne Titel)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(listing.draft.priceLabel, style = MaterialTheme.typography.bodyMedium)
                val status = listing.publishedAt?.let { "Veröffentlicht am ${dateFormat.format(Date(it))}" }
                    ?: "Noch nicht veröffentlicht"
                Text(
                    "${dateFormat.format(Date(listing.createdAt))} · ${listing.photos.size} Foto(s)\n$status",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (listing.isPublished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
        }
    }
}
