package de.olidoh.inserathelfer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import de.olidoh.inserathelfer.ListingViewModel
import de.olidoh.inserathelfer.Screen
import de.olidoh.inserathelfer.image.ImagePreparer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vm: ListingViewModel) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris -> if (uris.isNotEmpty()) vm.importPhotos(uris) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fotos aufnehmen") },
                actions = {
                    IconButton(onClick = vm::showHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Meine Anzeigen")
                    }
                    IconButton(onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Aus Galerie wählen") }
                    IconButton(onClick = { vm.screen = Screen.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (hasCameraPermission) {
                    CameraWithShutter(onPhoto = vm::addPhoto)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Für Fotos braucht die App Zugriff auf die Kamera.", color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Kamera erlauben")
                        }
                    }
                }
            }

            PhotoStrip(vm)

            Button(
                onClick = { vm.screen = Screen.DETAILS },
                enabled = vm.photos.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (vm.photos.isEmpty()) "Mindestens ein Foto aufnehmen" else "Weiter mit ${vm.photos.size} Foto(s)")
            }
        }
    }
}

@Composable
private fun CameraWithShutter(onPhoto: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    LaunchedEffect(lifecycleOwner) { controller.bindToLifecycle(lifecycleOwner) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize(),
        )
        FilledIconButton(
            onClick = {
                if (busy) return@FilledIconButton
                busy = true
                val file = ImagePreparer.newPhotoFile(context)
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            busy = false
                            onPhoto(file)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            Toast.makeText(context, "Foto fehlgeschlagen: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            },
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .size(72.dp)
                .border(4.dp, Color.White, CircleShape),
        ) {}
    }
}

@Composable
private fun PhotoStrip(vm: ListingViewModel) {
    if (vm.photos.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        items(vm.photos, key = { it.path }) { file ->
            Box {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
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
