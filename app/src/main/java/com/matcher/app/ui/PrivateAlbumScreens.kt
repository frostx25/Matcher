package com.matcher.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matcher.app.data.profile.ProfilePhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PrivateAlbumPhotoUi(
    val id: String,
    val position: Int,
    val bytes: ByteArray,
)

internal data class PrivateAlbumGrantUi(
    val recipientId: String,
    val displayName: String,
)

internal data class PrivateAlbumTargetUi(
    val id: String,
    val displayName: String,
    val shared: Boolean,
)

internal data class SharedPrivateAlbumUi(
    val ownerId: String,
    val ownerName: String,
    val itemCount: Int,
)

@Composable
internal fun SharedPrivateAlbumsSection(
    albums: List<SharedPrivateAlbumUi>,
    onOpen: (SharedPrivateAlbumUi) -> Unit,
) {
    if (albums.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .testTag("shared-private-albums"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Álbuns liberados para você",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Abra diretamente daqui, mesmo que a pessoa não esteja na descoberta.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        albums.sortedBy { it.ownerName.lowercase() }.forEach { album ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(album.ownerName, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "${album.itemCount} ${if (album.itemCount == 1) "foto" else "fotos"}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(
                    onClick = { onOpen(album) },
                    modifier = Modifier.testTag("open-shared-private-album-${album.ownerId}"),
                ) { Text("Abrir") }
            }
        }
    }
}

@Composable
internal fun MyPrivateAlbumScreen(
    albumExists: Boolean,
    photos: List<PrivateAlbumPhotoUi>,
    grants: List<PrivateAlbumGrantUi>,
    targets: List<PrivateAlbumTargetUi>,
    loading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onAddPhoto: (ByteArray) -> Unit,
    onPhotoError: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onToggleGrant: (String, Boolean) -> Unit,
    onDeleteAlbum: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showContentPolicy by rememberSaveable { mutableStateOf(false) }
    var contentPolicyAccepted by rememberSaveable { mutableStateOf(false) }
    var showDeleteAlbum by rememberSaveable { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val jpeg = withContext(Dispatchers.IO) {
                        ProfilePhotoProcessor.prepareJpeg(context.contentResolver, uri)
                    }
                    onAddPhoto(jpeg)
                } catch (_: Exception) {
                    onPhotoError()
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .testTag("my-private-album"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            AlbumHeader(
                title = "Seu álbum privado",
                subtitle = "${photos.size}/10 fotos · acesso individual",
                onBack = onBack,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = SoftPink)
                    Text("Privado por padrão", color = SoftPink, fontWeight = FontWeight.Bold)
                }
                Text(
                    "As fotos entram sem aprovação prévia e nunca aparecem na descoberta. Violações ainda podem ser denunciadas e removidas.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Button(
                    onClick = { showContentPolicy = true },
                    enabled = !loading && photos.size < 10,
                    modifier = Modifier.fillMaxWidth().testTag("add-private-album-photo"),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                ) {
                    Text(
                        if (photos.isEmpty()) "Criar álbum e adicionar foto" else "Adicionar foto",
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (photos.size >= 10) {
                    Text("O álbum chegou ao limite de 10 fotos.", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        if (loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Pink)
                }
            }
        }
        items(photos.sortedBy { it.position }, key = { it.id }) { photo ->
            PrivatePhotoTile(
                photo = photo,
                canDelete = !loading,
                onDelete = { onDeletePhoto(photo.id) },
            )
        }
        if (photos.isEmpty() && !loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Seu álbum ainda está vazio. Só você verá a primeira foto até liberar o acesso para alguém.",
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        if (photos.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceRaised, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Liberar para alguém", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    Text(
                        "A outra pessoa verá um aviso antes de abrir. Você pode revogar a qualquer momento.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    if (targets.isEmpty()) {
                        Text("Nenhum perfil disponível agora.", color = TextSecondary, fontSize = 12.sp)
                    }
                    targets.forEach { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(target.displayName, color = MaterialTheme.colorScheme.onBackground)
                            OutlinedButton(
                                onClick = { onToggleGrant(target.id, target.shared) },
                                enabled = !loading,
                                modifier = Modifier.testTag("album-grant-${target.id}"),
                            ) {
                                Text(if (target.shared) "Revogar" else "Liberar")
                            }
                        }
                    }
                }
            }
        }
        if (grants.any { grant -> targets.none { it.id == grant.recipientId } }) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Outros acessos ativos", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    grants.filter { grant -> targets.none { it.id == grant.recipientId } }.forEach { grant ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(grant.displayName, color = TextSecondary)
                            TextButton(
                                onClick = { onToggleGrant(grant.recipientId, true) },
                                enabled = !loading,
                            ) { Text("Revogar") }
                        }
                    }
                }
            }
        }
        errorMessage?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(message, color = Pink, modifier = Modifier.testTag("private-album-error"))
            }
        }
        if (albumExists) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(
                    onClick = { showDeleteAlbum = true },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("delete-private-album"),
                ) { Text("Excluir álbum privado") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Capturas de tela ou fotos feitas com outro aparelho não podem ser impedidas. Libere apenas para pessoas em quem confia.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }

    if (showContentPolicy) {
        AlertDialog(
            onDismissRequest = {
                showContentPolicy = false
                contentPolicyAccepted = false
            },
            title = { Text("Antes de adicionar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "É proibido conteúdo com menores, sem consentimento, exploração, violência proibida, atividade ilegal ou abuso. Fotos denunciadas podem ser removidas.",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(contentPolicyAccepted, { contentPolicyAccepted = it })
                        Text("Li e aceito a Política de Conteúdo.")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showContentPolicy = false
                        contentPolicyAccepted = false
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = contentPolicyAccepted,
                    modifier = Modifier.testTag("accept-private-album-policy"),
                ) { Text("Escolher foto") }
            },
            dismissButton = {
                TextButton(onClick = { showContentPolicy = false }) { Text("Cancelar") }
            },
        )
    }

    if (showDeleteAlbum) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbum = false },
            title = { Text("Excluir álbum privado?") },
            text = { Text("As fotos e todos os acessos concedidos serão removidos.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAlbum = false
                        onDeleteAlbum()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbum = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
internal fun PrivateAlbumWarningScreen(
    ownerName: String,
    loading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onReveal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(22.dp)
            .testTag("private-album-warning"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AlbumHeader("Álbum de $ownerName", "Conteúdo privado bloqueado", onBack)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Surface, RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(76.dp).background(SurfaceRaised, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = SoftPink, modifier = Modifier.size(34.dp))
                }
                Text("Você decide quando abrir", color = MaterialTheme.colorScheme.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Este álbum pode conter imagens privadas. Capturas ou fotos externas não podem ser impedidas.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        Button(
            onClick = onReveal,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("reveal-private-album"),
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
        ) { Text("Entendi, abrir álbum", fontWeight = FontWeight.Bold) }
        if (loading) CircularProgressIndicator(color = Pink, modifier = Modifier.align(Alignment.CenterHorizontally))
        errorMessage?.let { Text(it, color = Pink) }
    }
}

@Composable
internal fun ReceivedPrivateAlbumScreen(
    ownerName: String,
    photos: List<PrivateAlbumPhotoUi>,
    loading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onReport: (String) -> Unit,
) {
    var showReport by rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .testTag("received-private-album"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            AlbumHeader("Álbum de $ownerName", "Acesso privado", onBack)
        }
        items(photos.sortedBy { it.position }, key = { it.id }) { photo ->
            PrivatePhotoTile(photo = photo, canDelete = false, onDelete = {})
        }
        if (photos.isEmpty() && !loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Este álbum não está mais disponível.", color = TextSecondary)
            }
        }
        if (loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Pink)
                }
            }
        }
        errorMessage?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = Pink) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Não compartilhe imagens sem consentimento. O Matcher não consegue impedir capturas externas.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                OutlinedButton(
                    onClick = { showReport = true },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().testTag("report-private-album"),
                ) { Text("Denunciar álbum e bloquear acesso") }
            }
        }
    }

    if (showReport) {
        PrivateAlbumReportDialog(
            ownerName = ownerName,
            onDismiss = { showReport = false },
            onConfirm = {
                showReport = false
                onReport(it)
            },
        )
    }
}

@Composable
private fun AlbumHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("back-private-album")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", tint = MaterialTheme.colorScheme.onBackground)
        }
        Column {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 27.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrivatePhotoTile(
    photo: PrivateAlbumPhotoUi,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = photo.id,
        key2 = photo.bytes,
    ) {
        value = PrivateAlbumImageDecoder.decode(photo.bytes)
    }
    // Once handed to Image, Compose's renderer can retain this bitmap beyond this
    // composable's disposal. Recycling it here races the render thread; the backing
    // private bytes are still wiped by RemoteMatcherViewModel when access ends.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .background(Surface, RoundedCornerShape(18.dp))
            .testTag("private-album-photo-${photo.id}"),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = "Foto privada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("private-album-invalid-photo-${photo.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lock, "Imagem privada indisponível", tint = TextSecondary)
            }
        }
        if (canDelete) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Black.copy(alpha = 0.72f), CircleShape)
                    .testTag("delete-private-photo-${photo.id}"),
            ) {
                Icon(Icons.Outlined.DeleteOutline, "Excluir foto privada", tint = SoftPink)
            }
        }
    }
}

@Composable
private fun PrivateAlbumReportDialog(
    ownerName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var details by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Denunciar álbum de $ownerName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A denúncia encerra seu acesso, bloqueia o conteúdo e abre uma análise de moderação.")
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(1000) },
                    modifier = Modifier.fillMaxWidth().testTag("private-album-report-details"),
                    label = { Text("Conte o que aconteceu (opcional)") },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(details.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
            ) { Text("Denunciar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
