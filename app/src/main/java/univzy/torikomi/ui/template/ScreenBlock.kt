package univzy.torikomi.ui.template

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A single UI block that can be rendered on an extension downloader screen.
 *
 * Combine blocks inside [ExtensionScreenConfig.blocks] to build any screen
 * layout without writing Composables from scratch.
 *
 * Example:
 * ```kotlin
 * val config = ExtensionScreenConfig(
 *     blocks = listOf(
 *         ScreenBlock.UrlInput(),
 *         ScreenBlock.SearchButton(),
 *         ScreenBlock.Spacer(16),
 *         ScreenBlock.ActiveDownloads,
 *         ScreenBlock.Thumbnail(),
 *         ScreenBlock.Title,
 *         ScreenBlock.Author,
 *         ScreenBlock.Divider,
 *         ScreenBlock.VideoPreview,
 *         ScreenBlock.DownloadButtons,
 *         ScreenBlock.ImageGallery,
 *     )
 * )
 * ```
 */
sealed class ScreenBlock {

    // Input / Search

    /**
     * URL text field + save directory hint (optional).
     *
     * @param placeholder  Placeholder text inside the field.
     * @param showPaste    Show paste button at the end of the field.
     * @param showSavePath Show "Save to: …" row below the field.
     */
    data class UrlInput(
        val placeholder: String = "https://...",
        val showPaste: Boolean = true,
        val showSavePath: Boolean = true,
    ) : ScreenBlock()

    /**
     * Primary search / fetch button.
     *
     * @param label        Label when idle.
     * @param loadingLabel Label while processing.
     */
    data class SearchButton(
        val label: String = "Search Media",
        val loadingLabel: String = "Processing…",
    ) : ScreenBlock()

    // Media Info

    /**
     * Scrape result thumbnail image.
     *
     * @param heightDp  Image height in dp.
     * @param cornerDp  Corner radius in dp (0 = square).
     */
    data class Thumbnail(
        val heightDp: Int = 180,
        val cornerDp: Int = 8,
    ) : ScreenBlock()

    /** Media title from scrape result. */
    object Title : ScreenBlock()

    /** Author / uploader name. */
    object Author : ScreenBlock()

    /**
     * Description text.
     *
     * @param text Static text. If null, the description field from the scrape result is used.
     */
    data class Description(val text: String? = null) : ScreenBlock()

    // Preview

    /** In-app video player for the first video URL in the scrape result. */
    object VideoPreview : ScreenBlock()

    // Download

    /** Download button row for each item in [ScrapeResult.downloadItems]. */
    object DownloadButtons : ScreenBlock()

    /**
     * Image gallery card: each image is shown with its download button.
     * Only visible when [ScrapeResult.isImagePost] == true.
     */
    object ImageGallery : ScreenBlock()

    /** Progress card for active downloads. */
    object ActiveDownloads : ScreenBlock()

    // Custom / Layout

    /**
     * Static customizable text.
     *
     * @param text  Text content.
     * @param style Typography style to apply.
     */
    data class CustomText(
        val text: String,
        val style: CustomTextStyle = CustomTextStyle.Body,
    ) : ScreenBlock()

    /**
     * Custom action button (for additional actions beyond downloading).
     *
     * @param label   Button label.
     * @param icon    Optional icon to the left of the label.
     * @param filled  true = FilledButton, false = OutlinedButton.
     * @param onClick Action when the button is pressed.
     */
    data class CustomButton(
        val label: String,
        val icon: ImageVector? = null,
        val filled: Boolean = true,
        val onClick: () -> Unit = {},
    ) : ScreenBlock()

    /** Full-width horizontal divider. */
    object Divider : ScreenBlock()

    /**
     * Vertical empty space.
     *
     * @param heightDp Height in dp.
     */
    data class Spacer(val heightDp: Int = 8) : ScreenBlock()
}

/** Typography style for [ScreenBlock.CustomText]. */
enum class CustomTextStyle {
    Title,      // titleLarge + Bold
    Subtitle,   // titleMedium + SemiBold
    Body,       // bodyMedium
    Caption,    // bodySmall / onSurfaceVariant
    Label,      // labelSmall + uppercase + tracking
}

/**
 * Describes the complete layout of one extension downloader screen.
 *
 * @param titleOverride    Override the AppBar title (default = platform name).
 * @param wrapInputInCard  Wrap UrlInput + SearchButton blocks in a Card.
 * @param blocks           Ordered list of UI blocks to render.
 */
data class ExtensionScreenConfig(
    val titleOverride: String? = null,
    val wrapInputInCard: Boolean = true,
    val blocks: List<ScreenBlock> = defaultBlocks,
) {
    companion object {
        /** Susunan blok bawaan: lengkap dengan thumbnail, preview, gallery. */
        val defaultBlocks: List<ScreenBlock> = listOf(
            ScreenBlock.UrlInput(),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Thumbnail(),
            ScreenBlock.Title,
            ScreenBlock.Author,
            ScreenBlock.Divider,
            ScreenBlock.Spacer(8),
            ScreenBlock.VideoPreview,
            ScreenBlock.DownloadButtons,
            ScreenBlock.ImageGallery,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Builder helper – buat config langsung tanpa lambda verbose
// ─────────────────────────────────────────────────────────────────────────────

/** Standard full layout (thumbnail + preview + download). */
fun defaultExtensionConfig(titleOverride: String? = null, placeholder: String = "Paste URL here…") =
    ExtensionScreenConfig(
        titleOverride = titleOverride,
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = placeholder),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Thumbnail(),
            ScreenBlock.Title,
            ScreenBlock.Author,
            ScreenBlock.Divider,
            ScreenBlock.Spacer(8),
            ScreenBlock.VideoPreview,
            ScreenBlock.DownloadButtons,
            ScreenBlock.ImageGallery,
        ),
    )

/** URL input + search + download only (no preview / thumbnail). */
fun minimalExtensionConfig(titleOverride: String? = null, placeholder: String = "Paste URL here…") =
    ExtensionScreenConfig(
        titleOverride = titleOverride,
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = placeholder),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Title,
            ScreenBlock.DownloadButtons,
        ),
    )

/** Large thumbnail + video preview + download. */
fun mediaFocusedExtensionConfig(titleOverride: String? = null, placeholder: String = "Paste URL here…") =
    ExtensionScreenConfig(
        titleOverride = titleOverride,
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = placeholder),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Thumbnail(heightDp = 220),
            ScreenBlock.Title,
            ScreenBlock.Author,
            ScreenBlock.Divider,
            ScreenBlock.VideoPreview,
            ScreenBlock.DownloadButtons,
        ),
    )

/** Image gallery focus (Instagram, Pinterest, etc.). */
fun galleryExtensionConfig(titleOverride: String? = null, placeholder: String = "Paste URL here…") =
    ExtensionScreenConfig(
        titleOverride = titleOverride,
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = placeholder),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Title,
            ScreenBlock.Author,
            ScreenBlock.Divider,
            ScreenBlock.ImageGallery,
        ),
    )

/** Audio focus (Spotify, SoundCloud, etc.). */
fun audioExtensionConfig(titleOverride: String? = null, placeholder: String = "Paste URL here…") =
    ExtensionScreenConfig(
        titleOverride = titleOverride,
        blocks = listOf(
            ScreenBlock.UrlInput(placeholder = placeholder),
            ScreenBlock.SearchButton(),
            ScreenBlock.Spacer(16),
            ScreenBlock.ActiveDownloads,
            ScreenBlock.Thumbnail(heightDp = 220, cornerDp = 16),
            ScreenBlock.Title,
            ScreenBlock.Author,
            ScreenBlock.Divider,
            ScreenBlock.DownloadButtons,
        ),
    )
