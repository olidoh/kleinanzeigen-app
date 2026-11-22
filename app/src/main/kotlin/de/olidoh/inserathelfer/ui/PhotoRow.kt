package de.olidoh.inserathelfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.olidoh.inserathelfer.ListingViewModel

/**
 * Fotoleiste: das erste Foto ist das Titelbild (bei Kleinanzeigen das erste Bild).
 * Antippen eines anderen Fotos macht es zum Titelbild.
 */
@Composable
fun PhotoRow(vm: ListingViewModel, removable: Boolean, modifier: Modifier = Modifier, thumbSize: Int = 72) {
    if (vm.photos.isEmpty()) return
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.photos, key = { _, f -> f.path }) { index, file ->
                val isCover = index == 0
                Box(
                    Modifier
                        .size(thumbSize.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (isCover) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable(enabled = !isCover) { vm.setCoverPhoto(file) },
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = if (isCover) "Titelbild" else "Foto ${index + 1}, antippen für Titelbild",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(thumbSize.dp),
                    )
                    if (isCover) {
                        Text(
                            "Titelbild",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    if (removable) {
                        IconButton(
                            onClick = { vm.removePhoto(file) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Foto entfernen", modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
        if (vm.photos.size > 1) {
            Text(
                "Foto antippen, um es zum Titelbild zu machen.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
