package shop.vibeali.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import shop.vibeali.app.data.profile.ProfilePhotoProcessor
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
    onRevokeGrants: (Set<String>) -> Unit,
    onDeleteAlbum: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showContentPolicy by rememberSaveable { mutableStateOf(false) }
    var contentPolicyAccepted by rememberSaveable { mutableStateOf(false) }
    var showDeleteAlbum by rememberSaveable { mutableStateOf(false) }
    var showSharing by rememberSaveable { mutableStateOf(false) }
    var showAlbumMenu by rememberSaveable { mutableStateOf(false) }
    var showPhotoSource by rememberSaveable { mutableStateOf(false) }
    var previewPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGrantIds by remember { mutableStateOf(emptySet<String>()) }
    val photoInput = rememberPhotoInputLauncher(onAddPhoto, onPhotoError)

    if (showSharing) {
        PrivateAlbumSharingScreen(
            grants = grants,
            targets = targets,
            selectedGrantIds = selectedGrantIds,
            loading = loading,
            errorMessage = errorMessage,
            onBack = {
                selectedGrantIds = emptySet()
                showSharing = false
            },
            onSelectedChange = { selectedGrantIds = it },
            onGrant = { recipientId -> onToggleGrant(recipientId, false) },
            onRevokeSelected = {
                val recipients = selectedGrantIds
                selectedGrantIds = emptySet()
                onRevokeGrants(recipients)
            },
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .background(Black)
                .testTag("my-private-album"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OwnerAlbumHeader(
                    subtitle = "${photos.size}/10 fotos · acesso individual",
                    albumExists = albumExists,
                    loading = loading,
                    menuExpanded = showAlbumMenu,
                    onMenuExpandedChange = { showAlbumMenu = it },
                    onBack = onBack,
                    onManageSharing = { showSharing = true },
                    onDeleteAlbum = { showDeleteAlbum = true },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrivateAlbumOverviewCard(
                    photoCount = photos.size,
                    grantCount = grants.size,
                    albumExists = albumExists,
                    loading = loading,
                    onManageSharing = { showSharing = true },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = SoftPink)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Privado por padrão", color = SoftPink, fontWeight = FontWeight.Bold)
                        Text(
                            "Não aparece na descoberta. Só abre para quem você liberar.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Pink)
                    }
                }
            }
            item(key = "add-private-album-photo") {
                PrivateAlbumAddTile(
                    enabled = !loading && photos.size < 10,
                    onClick = { showContentPolicy = true },
                )
            }
            items(photos.sortedBy { it.position }, key = { it.id }) { photo ->
                PrivatePhotoTile(
                    photo = photo,
                    canDelete = !loading,
                    onOpen = { previewPhotoId = photo.id },
                    onDelete = { onDeletePhoto(photo.id) },
                )
            }
            if (photos.isEmpty() && !loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Adicione a primeira foto. O álbum continuará visível somente para você até liberar um acesso.",
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
            if (photos.size >= 10) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "O álbum chegou ao limite de 10 fotos.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            errorMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(message, color = Pink, modifier = Modifier.padding(top = 10.dp).testTag("private-album-error"))
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Capturas de tela ou fotos feitas com outro aparelho não podem ser impedidas. Libere apenas para pessoas em quem confia.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
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
                        showPhotoSource = true
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

    PhotoSourceDialog(
        visible = showPhotoSource,
        onDismiss = { showPhotoSource = false },
        launcher = photoInput,
    )

    photos.firstOrNull { it.id == previewPhotoId }?.let { photo ->
        PrivatePhotoPreviewDialog(
            photo = photo,
            onDismiss = { previewPhotoId = null },
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
private fun OwnerAlbumHeader(
    subtitle: String,
    albumExists: Boolean,
    loading: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onManageSharing: () -> Unit,
    onDeleteAlbum: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("back-private-album")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", tint = MaterialTheme.colorScheme.onBackground)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Meu álbum privado", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        if (albumExists) {
            Box {
                IconButton(
                    onClick = { onMenuExpandedChange(true) },
                    enabled = !loading,
                    modifier = Modifier.testTag("private-album-menu"),
                ) {
                    Icon(Icons.Outlined.MoreVert, "Mais opções do álbum", tint = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("Gerenciar compartilhamento") },
                        leadingIcon = { Icon(Icons.Outlined.Group, null) },
                        onClick = {
                            onMenuExpandedChange(false)
                            onManageSharing()
                        },
                        enabled = !loading,
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir álbum") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = {
                            onMenuExpandedChange(false)
                            onDeleteAlbum()
                        },
                        enabled = !loading,
                        modifier = Modifier.testTag("delete-private-album"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateAlbumOverviewCard(
    photoCount: Int,
    grantCount: Int,
    albumExists: Boolean,
    loading: Boolean,
    onManageSharing: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = SurfaceRaised,
        border = BorderStroke(1.dp, SoftPink.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(44.dp).background(SoftPink.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = SoftPink)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Seu espaço reservado", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    Text(
                        "$photoCount ${if (photoCount == 1) "foto" else "fotos"} · $grantCount ${if (grantCount == 1) "pessoa com acesso" else "pessoas com acesso"}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedButton(
                onClick = onManageSharing,
                enabled = albumExists && photoCount > 0 && !loading,
                modifier = Modifier.fillMaxWidth().testTag("manage-private-album-sharing"),
            ) {
                Icon(if (grantCount == 0) Icons.Outlined.PersonAdd else Icons.Outlined.Group, null)
                Text(
                    if (grantCount == 0) "Compartilhar álbum" else "Compartilhado com $grantCount",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PrivateAlbumAddTile(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("add-private-album-photo"),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        border = BorderStroke(1.dp, SoftPink.copy(alpha = if (enabled) 0.55f else 0.18f)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(SoftPink.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, "Adicionar foto", tint = if (enabled) SoftPink else TextSecondary)
            }
            Text(
                "Adicionar",
                color = if (enabled) MaterialTheme.colorScheme.onBackground else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun PrivateAlbumSharingScreen(
    grants: List<PrivateAlbumGrantUi>,
    targets: List<PrivateAlbumTargetUi>,
    selectedGrantIds: Set<String>,
    loading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    onGrant: (String) -> Unit,
    onRevokeSelected: () -> Unit,
) {
    val unsharedTargets = targets.filterNot { it.shared }.sortedBy { it.displayName.lowercase() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .testTag("private-album-sharing"),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = if (selectedGrantIds.isEmpty()) 28.dp else 126.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AlbumHeader(
                    title = "Compartilhamento",
                    subtitle = "${grants.size} ${if (grants.size == 1) "pessoa com acesso" else "pessoas com acesso"}",
                    onBack = onBack,
                )
            }
            item {
                Text(
                    "Selecione quem deve perder o acesso. A remoção passa a valer assim que for confirmada.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                )
            }
            item {
                Text("Com acesso", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            if (grants.isEmpty()) {
                item {
                    Text(
                        "Ninguém tem acesso ao seu álbum agora.",
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                lazyItems(grants.sortedBy { it.displayName.lowercase() }, key = { it.recipientId }) { grant ->
                    val selected = grant.recipientId in selectedGrantIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) SoftPink.copy(alpha = 0.10f) else Surface, RoundedCornerShape(16.dp))
                            .clickable(enabled = !loading) {
                                onSelectedChange(
                                    if (selected) selectedGrantIds - grant.recipientId else selectedGrantIds + grant.recipientId,
                                )
                            }
                            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(grant.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            Text("Acesso ativo", color = ActiveMint, fontSize = 12.sp)
                        }
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                onSelectedChange(
                                    if (selected) selectedGrantIds - grant.recipientId else selectedGrantIds + grant.recipientId,
                                )
                            },
                            enabled = !loading,
                            modifier = Modifier.testTag("select-album-grant-${grant.recipientId}"),
                        )
                    }
                }
            }
            item {
                Text(
                    "Liberar novo acesso",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (unsharedTargets.isEmpty()) {
                item { Text("Nenhum perfil disponível agora.", color = TextSecondary) }
            } else {
                lazyItems(unsharedTargets, key = { it.id }) { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceRaised, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            target.displayName,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onGrant(target.id) },
                            enabled = !loading,
                            modifier = Modifier.testTag("album-grant-${target.id}"),
                        ) { Text("Liberar") }
                    }
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Pink)
                    }
                }
            }
            errorMessage?.let { message ->
                item { Text(message, color = Pink, modifier = Modifier.testTag("private-album-error")) }
            }
        }

        if (selectedGrantIds.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = Surface,
                shadowElevation = 12.dp,
            ) {
                Button(
                    onClick = onRevokeSelected,
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp)
                        .testTag("stop-private-album-sharing"),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                ) {
                    Text("Parar de compartilhar (${selectedGrantIds.size})", fontWeight = FontWeight.Bold)
                }
            }
        }
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
    onReport: (String, String?) -> Unit,
) {
    var showReport by rememberSaveable { mutableStateOf(false) }
    var previewPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
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
            PrivatePhotoTile(
                photo = photo,
                canDelete = false,
                onOpen = { previewPhotoId = photo.id },
                onDelete = {},
            )
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
                    "Não compartilhe imagens sem consentimento. O VibeAli não consegue impedir capturas externas.",
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
            photos = photos,
            onDismiss = { showReport = false },
            onConfirm = { details, itemId ->
                showReport = false
                onReport(details, itemId)
            },
        )
    }
    photos.firstOrNull { it.id == previewPhotoId }?.let { photo ->
        PrivatePhotoPreviewDialog(
            photo = photo,
            onDismiss = { previewPhotoId = null },
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
    onOpen: () -> Unit,
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
            .then(if (bitmap != null) Modifier.clickable(onClick = onOpen) else Modifier)
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
private fun PrivatePhotoPreviewDialog(
    photo: PrivateAlbumPhotoUi,
    onDismiss: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = photo.id,
        key2 = photo.bytes,
    ) {
        value = PrivateAlbumImageDecoder.decode(photo.bytes)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("private-photo-preview"),
        title = { Text("Foto privada") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
                    .background(Black, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = requireNotNull(bitmap).asImageBitmap(),
                        contentDescription = "Foto privada ampliada",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(Icons.Outlined.Lock, "Imagem privada indisponível", tint = TextSecondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun PrivateAlbumReportDialog(
    ownerName: String,
    photos: List<PrivateAlbumPhotoUi>,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var details by rememberSaveable { mutableStateOf("") }
    var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Denunciar álbum de $ownerName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A denúncia encerra seu acesso, bloqueia o conteúdo e abre uma análise de moderação.")
                Text("O que você quer denunciar?", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedItemId == null,
                        onClick = { selectedItemId = null },
                        label = { Text("Álbum inteiro") },
                        modifier = Modifier.testTag("report-entire-private-album"),
                    )
                    photos.sortedBy { it.position }.forEachIndexed { index, photo ->
                        FilterChip(
                            selected = selectedItemId == photo.id,
                            onClick = { selectedItemId = photo.id },
                            label = { Text("Foto ${index + 1}") },
                            modifier = Modifier.testTag("report-private-photo-${photo.id}"),
                        )
                    }
                }
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
                onClick = { onConfirm(details.trim(), selectedItemId) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                modifier = Modifier.testTag("confirm-private-album-report"),
            ) { Text("Denunciar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
