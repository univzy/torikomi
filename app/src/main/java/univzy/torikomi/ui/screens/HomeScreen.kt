package univzy.torikomi.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import univzy.torikomi.Torikomi
import univzy.torikomi.data.model.ExtensionInfo
import univzy.torikomi.data.model.ExtensionStatus
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.ExtensionViewModel
import univzy.torikomi.ui.components.PlatformCard
import univzy.torikomi.util.UrlDetectorService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val extVm: ExtensionViewModel = viewModel(factory = factory)
    val extState by extVm.uiState.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var showAbout  by remember { mutableStateOf(false) }
    val snackState = remember { SnackbarHostState() }

    LaunchedEffect(extState.error) {
        val message = extState.error ?: return@LaunchedEffect
        snackState.showSnackbar(message)
    }

    // Clipboard detection on resume + catalog refresh on every resume
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastClipboard by remember { mutableStateOf("") }
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                extVm.refreshCatalog()

                val text = runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.toString()
                }.getOrNull() ?: return@LifecycleEventObserver

                val url = UrlDetectorService.extractUrl(text) ?: return@LifecycleEventObserver
                if (url != lastClipboard) {
                    lastClipboard = url
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val tabTitles = listOf("Downloads", "Extensions", "History", "Settings")

    Scaffold(
        snackbarHost = { SnackbarHost(snackState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(tabTitles[selectedTab], fontWeight = FontWeight.Bold)
                },
                actions = {
                    if (selectedTab == 1) {
                        IconButton(onClick = extVm::refreshCatalog) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh catalog")
                        }
                    } else {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = "Toggle theme",
                            )
                        }
                        IconButton(onClick = { showAbout = true }) {
                            Icon(Icons.Rounded.Info, contentDescription = "About")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Downloads",  Icons.Rounded.Download,  Icons.Rounded.DownloadDone),
                    Triple("Extensions", Icons.Rounded.Extension, Icons.Rounded.Extension),
                    Triple("History",    Icons.Rounded.History,   Icons.Rounded.History),
                    Triple("Settings",   Icons.Rounded.Settings,  Icons.Rounded.Settings),
                ).forEachIndexed { index, (label, icon, selectedIcon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick  = { selectedTab = index },
                        icon     = {
                            Icon(if (selectedTab == index) selectedIcon else icon, label)
                        },
                        label    = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> DownloadsTab(
                modifier = Modifier.padding(padding),
                installed = extState.catalog.filter { it.status == ExtensionStatus.INSTALLED },
                onPlatformTap = { platform, isWhatsApp ->
                    if (isWhatsApp) navController.navigate("whatsapp")
                    else navController.navigate("download/$platform")
                },
                onBrowseExtensions = { selectedTab = 1 },
            )
            1 -> ExtensionsTab(
                modifier = Modifier.padding(padding),
                catalog  = extState.catalog,
                isLoading = extState.isLoading,
                onInstall   = extVm::install,
                onRefresh   = extVm::fetchCatalog,
            )
            2 -> HistoryScreen(modifier = Modifier.padding(padding))
            3 -> SettingsScreen(modifier = Modifier.padding(padding))
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}


@Composable
private fun DownloadsTab(
    modifier: Modifier,
    installed: List<ExtensionInfo>,
    onPlatformTap: (String, Boolean) -> Unit,
    onBrowseExtensions: () -> Unit,
) {
    val platformIds = remember(installed) {
        installed.map { ext ->
            ext.dynamicPlatform?.takeIf { it.isNotBlank() }?.lowercase()
                ?: inferPlatformIdFromBaseUrl(ext.baseUrl)
                ?: ext.id.lowercase()
        }.distinct()
    }
    var platformFilter by remember { mutableStateOf("all") }
    val visibleInstalled = remember(installed, platformFilter) {
        if (platformFilter == "all") installed
        else installed.filter { ext ->
            val pid = ext.dynamicPlatform?.takeIf { it.isNotBlank() }?.lowercase()
                ?: inferPlatformIdFromBaseUrl(ext.baseUrl)
                ?: ext.id.lowercase()
            pid == platformFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Share hint banner
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 0.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = null,
                    tint   = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Share a video URL from any app directly to Torikomi Downloader",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.secondary
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Select Platform", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

        if (platformIds.size > 1) {
            Spacer(Modifier.height(10.dp))
            val chips = listOf("all" to "All") + platformIds.map { id -> id to platformIdToDisplayName(id) }
            TabFilterRow(chips = chips, selected = platformFilter, onSelect = { platformFilter = it })
        }

        Spacer(Modifier.height(16.dp))

        if (installed.isEmpty()) {
            NoExtensionsState(onBrowseExtensions)
        } else if (visibleInstalled.isEmpty()) {
            Text(
                "No platforms match the filter",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        } else {
            visibleInstalled.forEach { ext ->
                val targetExtensionId = ext.dynamicPlatform
                    ?.takeIf { it.isNotBlank() }
                    ?.trim()
                    ?.lowercase()
                    ?: inferPlatformIdFromBaseUrl(ext.baseUrl)
                    ?: ext.id.lowercase()

                val platformForDownload = ext.dynamicPlatform
                    ?.takeIf { it.isNotBlank() }
                    ?.let { platformIdToDisplayName(it) }
                    ?: inferPlatformFromBaseUrl(ext.baseUrl)
                    ?: ext.displayName
                PlatformCard(
                    name        = ext.displayName,
                    platformName = platformForDownload,
                    version     = "v${ext.version}",
                    extensionId = ext.id,
                    appPackageName = ext.pkg,
                    dynamicColor = ext.dynamicColor,
                    dynamicIcon  = ext.dynamicIcon,
                    onClick     = { onPlatformTap(targetExtensionId, ext.id == "whatsapp_status") },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun platformIdToDisplayName(platform: String): String = when (platform.lowercase()) {
    "tiktok" -> "TikTok"
    "youtube" -> "YouTube"
    "instagram" -> "Instagram"
    "facebook" -> "Facebook"
    "twitter", "x" -> "Twitter"
    "threads" -> "Threads"
    "pinterest" -> "Pinterest"
    "spotify" -> "Spotify"
    "soundcloud" -> "SoundCloud"
    "douyin" -> "Douyin"
    "bilibili" -> "Bilibili"
    else -> platform
}

private fun inferPlatformFromBaseUrl(baseUrl: String): String? {
    val host = baseUrl.trim().lowercase()
    return when {
        "tiktok" in host -> "TikTok"
        "youtube" in host || "youtu.be" in host -> "YouTube"
        "instagram" in host -> "Instagram"
        "facebook" in host || "fb.watch" in host -> "Facebook"
        "twitter" in host || "x.com" in host -> "Twitter"
        "threads" in host -> "Threads"
        "pinterest" in host || "pin.it" in host -> "Pinterest"
        "spotify" in host -> "Spotify"
        "soundcloud" in host -> "SoundCloud"
        "douyin" in host -> "Douyin"
        "bilibili" in host || "b23.tv" in host -> "Bilibili"
        else -> null
    }
}

private fun inferPlatformIdFromBaseUrl(baseUrl: String): String? {
    val host = baseUrl.trim().lowercase()
    return when {
        "tiktok" in host -> "tiktok"
        "youtube" in host || "youtu.be" in host -> "youtube"
        "instagram" in host -> "instagram"
        "facebook" in host || "fb.watch" in host -> "facebook"
        "twitter" in host || "x.com" in host -> "twitter"
        "threads" in host -> "threads"
        "pinterest" in host || "pin.it" in host -> "pinterest"
        "spotify" in host -> "spotify"
        "soundcloud" in host -> "soundcloud"
        "douyin" in host -> "douyin"
        "bilibili" in host || "b23.tv" in host -> "bilibili"
        else -> null
    }
}

@Composable
private fun NoExtensionsState(onBrowseExtensions: () -> Unit) {
    Column(
        Modifier.padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.ExtensionOff,
            contentDescription = null,
            tint     = Color.Gray.copy(alpha = 0.4f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("No extensions installed", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Go to the Extensions tab to install\nplatform downloaders",
            style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBrowseExtensions) {
            Icon(Icons.Rounded.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Browse Extensions")
        }
    }
}


@Composable
private fun ExtensionsTab(
    modifier: Modifier,
    catalog: List<ExtensionInfo>,
    isLoading: Boolean,
    onInstall: (ExtensionInfo) -> Unit,
    onRefresh: () -> Unit,
) {
    if (isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val platformIds = remember(catalog) {
        catalog.map { ext ->
            ext.dynamicPlatform?.takeIf { it.isNotBlank() }?.lowercase()
                ?: inferPlatformIdFromBaseUrl(ext.baseUrl)
                ?: ext.id.lowercase()
        }.distinct().sorted()
    }
    var platformFilter by remember { mutableStateOf("all") }

    Column(modifier.fillMaxSize()) {
        if (platformIds.size > 1) {
            Surface(tonalElevation = 1.dp) {
                val chips = listOf("all" to "All") + platformIds.map { id -> id to platformIdToDisplayName(id) }
                TabFilterRow(
                    chips    = chips,
                    selected = platformFilter,
                    onSelect = { platformFilter = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            val filteredCatalog = if (platformFilter == "all") catalog else catalog.filter { ext ->
                val pid = ext.dynamicPlatform?.takeIf { it.isNotBlank() }?.lowercase()
                    ?: inferPlatformIdFromBaseUrl(ext.baseUrl)
                    ?: ext.id.lowercase()
                pid == platformFilter
            }
            val available = filteredCatalog.filter { it.status == ExtensionStatus.AVAILABLE }
            val installed = filteredCatalog.filter { it.status == ExtensionStatus.INSTALLED }
            val updatable = filteredCatalog.filter { it.status == ExtensionStatus.UPDATE_AVAILABLE }

            if (updatable.isNotEmpty()) {
                item { SectionHeader("Update Available") }
                items(updatable.size) { i ->
                    ExtensionListItem(updatable[i], onInstall = onInstall, onRefresh = onRefresh)
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (installed.isNotEmpty()) {
                item { SectionHeader("Installed") }
                items(installed.size) { i ->
                    ExtensionListItem(installed[i], onInstall = onInstall, onRefresh = onRefresh)
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (available.isNotEmpty()) {
                item { SectionHeader("Available") }
                items(available.size) { i ->
                    ExtensionListItem(available[i], onInstall = onInstall, onRefresh = onRefresh)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TabFilterRow(
    chips: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(chips) { (value, display) ->
            FilterChip(
                selected = selected == value,
                onClick  = { onSelect(value) },
                label    = { Text(display, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.secondary,
        ),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ExtensionListItem(
    ext: ExtensionInfo,
    onInstall: (ExtensionInfo) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val isInstalled = ext.status == ExtensionStatus.INSTALLED || ext.status == ExtensionStatus.UPDATE_AVAILABLE
    val hasUpdate = ext.status == ExtensionStatus.UPDATE_AVAILABLE

    val appIcon = remember(ext.pkg, isInstalled) {
        if (!isInstalled) null
        else runCatching { context.packageManager.getApplicationIcon(ext.pkg) }.getOrNull()
    }
    val catalogIconUrl = remember(ext.catalogIconUrl, isInstalled) {
        if (isInstalled) "" else ext.catalogIconUrl
    }
    val platformName = ext.dynamicPlatform
        ?.takeIf { it.isNotBlank() }
        ?.let { platformIdToDisplayName(it) }
        ?: ext.dynamicPlatformName?.takeIf { it.isNotBlank() }
        ?: inferPlatformFromBaseUrl(ext.baseUrl)
        ?: "Unknown"
    val description = ext.dynamicDescription
        ?.takeIf { it.isNotBlank() }
        ?: run {
            val downloaderName = ext.dynamicDownloaderName
                ?.takeIf { it.isNotBlank() }
                ?: ext.displayName
            "$downloaderName downloader for $platformName"
        }

    Card(elevation = CardDefaults.cardElevation(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon.toBitmap(96, 96).asImageBitmap(),
                    contentDescription = ext.displayName,
                    modifier = Modifier.size(26.dp),
                )
            } else if (catalogIconUrl.isNotBlank()) {
                AsyncImage(
                    model = catalogIconUrl,
                    contentDescription = ext.displayName,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(ext.displayName, fontWeight = FontWeight.Bold)
                Text("v${ext.version}", style = MaterialTheme.typography.bodySmall)
                Text("Platform: $platformName", style = MaterialTheme.typography.bodySmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (hasUpdate) {
                IconButton(onClick = { onInstall(ext) }) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = "Update",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            } else if (isInstalled) {
                IconButton(onClick = {
                    // Open system App Info — user can uninstall from there
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ext.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "App Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = { onInstall(ext) }) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Install",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}


@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column {
                // Gradient header
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Core Developers", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("TobyG74 • arugaz • nugraizy", color = Color.White.copy(.8f), fontSize = 12.sp)
                    }
                }

                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Torikomi Downloader", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large,
                        ) { Text("v1.0.0", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A multi-platform media downloader powered by a plugin-based extension system.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Divider(Modifier.padding(vertical = 12.dp))

                    SocialLink("TobyG74",  "GitHub", "https://github.com/TobyG74",  context)
                    Spacer(Modifier.height(8.dp))
                    SocialLink("arugaz",   "GitHub", "https://github.com/arugaz",   context)
                    Spacer(Modifier.height(8.dp))
                    SocialLink("nugraizy", "GitHub", "https://github.com/nugraizy", context)

                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))

                    Text("© 2026 Torikomi Developers. All rights reserved.", fontSize = 10.sp, color = Color.Gray)
                }

                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun SocialLink(label: String, subtitle: String, url: String, context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    Surface(
        color  = MaterialTheme.colorScheme.surfaceVariant.copy(.5f),
        shape  = MaterialTheme.shapes.small,
        onClick = { context.startActivity(intent) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label,    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
        }
    }
}
