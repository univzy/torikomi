package univzy.torikomi.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val WA_GREEN = Color(0xFF25D366)

private val STATUS_PATHS = listOf(
    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
    "/storage/emulated/0/WhatsApp/Media/.Statuses",
    "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
)

private val IMAGE_EXT = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp")
private val VIDEO_EXT = setOf(".mp4", ".mov", ".avi", ".mkv", ".3gp")

data class WaStatus(
    val file: File,
    val isVideo: Boolean,
    val sizeLabel: String,
)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024            -> "${bytes}B"
    bytes < 1024 * 1024     -> "${"%.1f".format(bytes / 1024f)}KB"
    else                    -> "${"%.1f".format(bytes / (1024f * 1024f))}MB"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppStatusScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(false) }
    var isLoading     by remember { mutableStateOf(true) }
    var statuses      by remember { mutableStateOf<List<WaStatus>>(emptyList()) }
    var error         by remember { mutableStateOf<String?>(null) }
    var savingAll     by remember { mutableStateOf(false) }
    val saving        = remember { mutableStateListOf<String>() }

    val snackState = remember { SnackbarHostState() }

    fun loadStatuses() {
        scope.launch {
            isLoading = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val list = mutableListOf<WaStatus>()
                    for (path in STATUS_PATHS) {
                        val dir = File(path)
                        if (!dir.exists()) continue
                        dir.listFiles()?.forEach { f ->
                            if (f.name.startsWith(".")) return@forEach
                            val ext = f.name.substringAfterLast('.', "").let { ".${it.lowercase()}" }
                            val isImg   = IMAGE_EXT.contains(ext)
                            val isVideo = VIDEO_EXT.contains(ext)
                            if (!isImg && !isVideo) return@forEach
                            list.add(WaStatus(f, isVideo, formatSize(f.length())))
                        }
                    }
                    list.sortedByDescending { it.file.lastModified() }
                }
            }
            result.onSuccess { statuses = it; isLoading = false }
                .onFailure { error = it.message; isLoading = false }
        }
    }

    // Permission launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) loadStatuses()
    }

    // Manage-storage launcher (Android 11+)
    val manageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        hasPermission = ok
        if (ok) loadStatuses()
    }

    // Check permission on enter
    LaunchedEffect(Unit) {
        isLoading = true
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        hasPermission = ok
        if (ok) loadStatuses() else isLoading = false
    }

    fun saveFile(status: WaStatus) {
        scope.launch {
            saving.add(status.file.absolutePath)
            runCatching {
                withContext(Dispatchers.IO) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, status.file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE,
                            if (status.isVideo) "video/mp4" else "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH,
                            if (status.isVideo) "Movies/WhatsApp Statuses" else "Pictures/WhatsApp Statuses")
                    }
                    val uri = if (status.isVideo)
                        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                    else
                        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        status.file.inputStream().use { it.copyTo(out) }
                    }
                }
            }.onSuccess { snackState.showSnackbar("Saved to gallery ✓") }
             .onFailure { snackState.showSnackbar("Save failed: ${it.message}") }
            saving.remove(status.file.absolutePath)
        }
    }

    fun saveAll() {
        scope.launch {
            savingAll = true
            var count = 0
            statuses.forEach { s ->
                runCatching { saveFile(s); count++ }
            }
            snackState.showSnackbar("$count files saved to gallery")
            savingAll = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                title            = { Text("WhatsApp Status") },
                navigationIcon   = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors           = TopAppBarDefaults.topAppBarColors(
                    containerColor    = WA_GREEN,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor     = Color.White,
                ),
                actions = {
                    if (hasPermission && statuses.isNotEmpty()) {
                        IconButton(onClick = { saveAll() }, enabled = !savingAll) {
                            if (savingAll) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.SaveAlt, "Save all")
                        }
                        IconButton(onClick = { loadStatuses() }) {
                            Icon(Icons.Rounded.Refresh, "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WA_GREEN)
                }
                !hasPermission -> PermissionRequest(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            manageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"))
                            )
                        } else {
                            permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }
                )
                error != null -> ErrorState(error!!, onRetry = { loadStatuses() })
                statuses.isEmpty() -> EmptyState(onRetry = { loadStatuses() })
                else -> StatusGrid(
                    statuses = statuses,
                    saving   = saving.toSet(),
                    onSave   = { saveFile(it) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(64.dp), tint = WA_GREEN)
        Spacer(Modifier.height(16.dp))
        Text("Storage Permission Required", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Torikomi Downloader needs storage access to read your WhatsApp statuses.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = Color.Gray,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            colors  = ButtonDefaults.buttonColors(containerColor = WA_GREEN),
        ) {
            Icon(Icons.Rounded.Security, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Grant Permission")
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(64.dp), tint = Color.Red)
        Spacer(Modifier.height(16.dp))
        Text("An error occurred", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = WA_GREEN)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.HourglassEmpty, null, Modifier.size(64.dp), tint = Color.LightGray)
        Spacer(Modifier.height(16.dp))
        Text("No statuses found", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("View some WhatsApp statuses first, then come back here.", color = Color.Gray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = WA_GREEN)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun StatusGrid(
    statuses: List<WaStatus>,
    saving: Set<String>,
    onSave: (WaStatus) -> Unit,
) {
    val images = statuses.filter { !it.isVideo }
    val videos = statuses.filter { it.isVideo }

    Column {
        // Stats bar
        Surface(color = WA_GREEN.copy(alpha = .1f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.Image,    null, Modifier.size(16.dp), tint = WA_GREEN)
                Spacer(Modifier.width(4.dp))
                Text("${images.size} photos", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Rounded.Videocam, null, Modifier.size(16.dp), tint = WA_GREEN)
                Spacer(Modifier.width(4.dp))
                Text("${videos.size} videos", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Text("Photos", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                items(images.size) { StatusTile(images[it], saving, onSave) }
            }
            if (videos.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Text("Videos", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                items(videos.size) { StatusTile(videos[it], saving, onSave) }
            }
        }
    }
}

@Composable
private fun StatusTile(status: WaStatus, saving: Set<String>, onSave: (WaStatus) -> Unit) {
    val isSaving = saving.contains(status.file.absolutePath)
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color.Black.copy(.7f))
    ) {
        Image(
            painter            = rememberAsyncImagePainter(status.file),
            contentDescription = null,
            modifier           = Modifier.fillMaxSize(),
            contentScale       = ContentScale.Crop,
        )
        if (status.isVideo) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(.35f), shape = androidx.compose.foundation.shape.CircleShape)
                    .padding(8.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp), tint = Color.White)
            }
            // File size badge
            Surface(
                Modifier.align(Alignment.BottomStart).padding(4.dp),
                color  = Color.Black.copy(.55f),
                shape  = MaterialTheme.shapes.extraSmall,
            ) {
                Text(status.sizeLabel, Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = Color.White, fontSize = 10.sp)
            }
        }
        // Save button
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(28.dp)
                .background(WA_GREEN, shape = androidx.compose.foundation.shape.CircleShape)
                .clickable(enabled = !isSaving) { onSave(status) },
            contentAlignment = Alignment.Center,
        ) {
            if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.SaveAlt, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}
