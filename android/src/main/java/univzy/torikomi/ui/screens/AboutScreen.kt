package univzy.torikomi.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.SettingsViewModel

private const val RELEASES_URL = "https://github.com/univzy/torikomi/releases"

private enum class AboutPage { MAIN, LICENSES, PRIVACY }

private data class LicenseEntry(val name: String, val license: String, val url: String)

private val LICENSES = listOf(
    LicenseEntry("yt-dlp", "The Unlicense", "https://github.com/yt-dlp/yt-dlp"),
    LicenseEntry("youtubedl-android", "GPL-3.0", "https://github.com/JunkFood02/youtubedl-android"),
    LicenseEntry("FFmpeg", "LGPL-2.1+ / GPL", "https://ffmpeg.org"),
    LicenseEntry("Jetpack Compose & AndroidX", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    LicenseEntry("Kotlin & Coroutines", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    LicenseEntry("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    LicenseEntry("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: SettingsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()

    var page by remember { mutableStateOf(AboutPage.MAIN) }

    BackHandler(enabled = page != AboutPage.MAIN) { page = AboutPage.MAIN }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (page != AboutPage.MAIN) page = AboutPage.MAIN else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        when (page) {
            AboutPage.MAIN -> MainPage(
                state = state,
                modifier = Modifier.padding(padding),
                onCheckUpdates = { vm.checkLatestRelease() },
                onOpenReleases = { openUrl(RELEASES_URL) },
                onOpenUrl = ::openUrl,
                onOpenLicenses = { page = AboutPage.LICENSES },
                onOpenPrivacy = { page = AboutPage.PRIVACY },
            )
            AboutPage.LICENSES -> LicensesPage(
                modifier = Modifier.padding(padding),
                onOpenUrl = ::openUrl,
            )
            AboutPage.PRIVACY -> PrivacyPage(modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun MainPage(
    state: univzy.torikomi.ui.SettingsUiState,
    modifier: Modifier,
    onCheckUpdates: () -> Unit,
    onOpenReleases: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val release = state.latestRelease
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(androidx.compose.ui.graphics.Color.Transparent),
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(univzy.torikomi.R.drawable.torikomi_icon),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(96.dp),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        AboutRow(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.about_version),
            subtitle = stringResource(R.string.about_version_value, state.currentVersion),
        )

        val updateSubtitle = when {
            release != null && state.hasUpdate -> stringResource(R.string.about_update_available, release.tagName)
            release != null -> stringResource(R.string.about_up_to_date)
            else -> stringResource(R.string.about_tap_to_check)
        }
        AboutRow(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(R.string.about_check_updates),
            subtitle = updateSubtitle,
            trailing = {
                if (state.isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            onClick = {
                if (state.hasUpdate && release != null) onOpenUrl(release.htmlUrl) else onCheckUpdates()
            },
        )
        if (state.updateError != null) {
            Text(
                state.updateError ?: stringResource(R.string.about_unknown_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
            )
        }

        AboutRow(
            icon = Icons.Rounded.NewReleases,
            title = stringResource(R.string.about_whats_new),
            subtitle = stringResource(R.string.about_whats_new_subtitle),
            onClick = onOpenReleases,
        )

        AboutRow(
            icon = Icons.Rounded.Description,
            title = stringResource(R.string.about_licenses),
            subtitle = stringResource(R.string.about_licenses_subtitle),
            trailing = { Chevron() },
            onClick = onOpenLicenses,
        )

        AboutRow(
            icon = Icons.Rounded.PrivacyTip,
            title = stringResource(R.string.about_privacy),
            subtitle = stringResource(R.string.about_privacy_subtitle),
            trailing = { Chevron() },
            onClick = onOpenPrivacy,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.about_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LicensesPage(
    modifier: Modifier,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.about_licenses),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.about_licenses_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LICENSES.forEach { entry ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenUrl(entry.url) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            entry.license,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.action_open),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyPage(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.about_privacy),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.about_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
