package univzy.torikomi.ui.template

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.DownloadViewModel

/**
 * Template utama layar extension downloader.
 *
 * Render semua blok yang didefinisikan di [ExtensionScreenConfig.blocks]
 * secara berurutan dalam satu Column yang bisa di-scroll.
 *
 * Blok input (UrlInput + SearchButton) dibungkus dalam Card secara otomatis
 * bila [ExtensionScreenConfig.wrapInputInCard] == true.
 *
 * ### Cara pakai minimal
 * ```kotlin
 * ExtensionScreenTemplate(
 *     platform = "youtube",
 *     onBack   = { navController.popBackStack() },
 * )
 * ```
 *
 * ### Kustomisasi config
 * ```kotlin
 * ExtensionScreenTemplate(
 *     platform = "spotify",
 *     config   = audioExtensionConfig(titleOverride = "Spotify Downloader"),
 *     onBack   = { navController.popBackStack() },
 * )
 * ```
 *
 * ### Config sepenuhnya kustom
 * ```kotlin
 * ExtensionScreenTemplate(
 *     platform = "custom",
 *     config   = ExtensionScreenConfig(
 *         blocks = listOf(
 *             ScreenBlock.CustomText("Masukkan link video", CustomTextStyle.Subtitle),
 *             ScreenBlock.UrlInput(placeholder = "https://example.com/video"),
 *             ScreenBlock.SearchButton(label = "Cari Media"),
 *             ScreenBlock.Spacer(16),
 *             ScreenBlock.Thumbnail(heightDp = 240, cornerDp = 12),
 *             ScreenBlock.Title,
 *             ScreenBlock.Author,
 *             ScreenBlock.Divider,
 *             ScreenBlock.VideoPreview,
 *             ScreenBlock.DownloadButtons,
 *             ScreenBlock.CustomButton(
 *                 label   = "Buka di Browser",
 *                 icon    = Icons.Rounded.OpenInNew,
 *                 filled  = false,
 *                 onClick = { /* buka URL */ },
 *             ),
 *         )
 *     ),
 *     onBack = { navController.popBackStack() },
 * )
 * ```
 *
 * @param platform     ID platform (mis. "tiktok", "youtube"). Digunakan sebagai ViewModel key,
 *                     judul AppBar default, dan argumen ke [DownloadViewModel.fetchMedia].
 * @param config       Konfigurasi tampilan layar. Default = [defaultExtensionConfig].
 * @param initialUrl   URL awal (dari share intent atau clipboard detection).
 * @param onBack       Aksi tombol navigasi kembali.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionScreenTemplate(
    platform: String,
    config: ExtensionScreenConfig = defaultExtensionConfig(),
    initialUrl: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: DownloadViewModel = viewModel(key = "download_$platform", factory = factory)
    val state by vm.uiState.collectAsState()

    // Pre-fill URL bila dibuka lewat share intent
    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            vm.updateUrl(initialUrl)
            vm.fetchMedia(platform)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshSavePathLabel()
    }

    val screenTitle = config.titleOverride
        ?: platform.trim().replaceFirstChar { c ->
            if (c.isLowerCase()) c.titlecase() else c.toString()
        }.let { "$it Downloader" }

    Scaffold(
        topBar = {
            TopAppBar(
                title           = { Text(screenTitle) },
                navigationIcon  = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (config.wrapInputInCard) {
                // Blok UrlInput + SearchButton dibungkus dalam satu Card
                val inputBlocks = config.blocks.filter {
                    it is ScreenBlock.UrlInput || it is ScreenBlock.SearchButton
                }
                val otherBlocks = config.blocks.filter {
                    it !is ScreenBlock.UrlInput && it !is ScreenBlock.SearchButton
                }

                if (inputBlocks.isNotEmpty()) {
                    Card(
                        colors    = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            inputBlocks.forEachIndexed { index, block ->
                                if (index > 0) Spacer(Modifier.height(12.dp))
                                RenderBlock(block, state, vm, platform, context)
                            }
                        }
                    }
                }

                otherBlocks.forEach { block ->
                    RenderBlock(block, state, vm, platform, context)
                }
            } else {
                // Render semua blok flat tanpa card wrapper
                config.blocks.forEach { block ->
                    RenderBlock(block, state, vm, platform, context)
                }
            }

            // Error result
            state.result?.let { result ->
                if (!result.isSuccess) {
                    Spacer(Modifier.height(16.dp))
                    ErrorResultCard(message = result.error ?: "Failed to fetch media")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorResultCard(message: String) {
    Card {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
