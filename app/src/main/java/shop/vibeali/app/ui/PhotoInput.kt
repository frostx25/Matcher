package shop.vibeali.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.vibeali.app.data.profile.ProfilePhotoProcessor

internal class PhotoInputLauncher(
    val openGallery: () -> Unit,
    val openCamera: () -> Unit,
)

@Composable
internal fun rememberPhotoInputLauncher(
    onPhotoSelected: (ByteArray) -> Unit,
    onPhotoError: () -> Unit,
): PhotoInputLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPhotoSelected by rememberUpdatedState(onPhotoSelected)
    val currentOnPhotoError by rememberUpdatedState(onPhotoError)
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun process(uri: Uri, temporaryFile: File? = null) {
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    ProfilePhotoProcessor.prepareJpeg(context.contentResolver, uri)
                }
                currentOnPhotoSelected(jpeg)
            } catch (_: Exception) {
                currentOnPhotoError()
            } finally {
                temporaryFile?.delete()
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) process(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val file = cameraFile
        val uri = cameraUri
        cameraFile = null
        cameraUri = null
        if (captured && uri != null) {
            process(uri, file)
        } else {
            file?.delete()
        }
    }

    return remember(galleryLauncher, cameraLauncher) {
        PhotoInputLauncher(
            openGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            openCamera = {
                try {
                    val directory = File(context.cacheDir, "camera-photos").apply { mkdirs() }
                    val file = File(directory, "capture-${UUID.randomUUID()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    cameraFile = file
                    cameraUri = uri
                    cameraLauncher.launch(uri)
                } catch (_: Exception) {
                    cameraFile?.delete()
                    cameraFile = null
                    cameraUri = null
                    currentOnPhotoError()
                }
            },
        )
    }
}

@Composable
internal fun PhotoSourceDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    launcher: PhotoInputLauncher,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar foto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        launcher.openCamera()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("take-photo-now"),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tirar foto agora")
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        launcher.openGallery()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("choose-photo-gallery"),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Escolher da galeria")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
