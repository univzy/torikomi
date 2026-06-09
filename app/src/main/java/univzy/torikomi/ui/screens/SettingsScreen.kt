package univzy.torikomi.ui.screens

import android.content.Intent
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import univzy.torikomi.R
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.AppViewModelFactory
import univzy.torikomi.ui.SettingsUiState
import univzy.torikomi.ui.SettingsViewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenAbout: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
) {
    val context = LocalContext.current
    val factory = AppViewModelFactory(context.applicationContext as Torikomi)
    val vm: SettingsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()

    var showDownloadLocationDialog by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            // Request persistent permission
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Get folder name from URI
            val displayName = treeUri.lastPathSegment?.split(":")?.last() ?: "Selected Folder"
            vm.setDownloadFolder(treeUri.toString(), displayName)
            showDownloadLocationDialog = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background),
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.settings_section_download),
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 16.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        SettingsItem(
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(R.string.settings_download_folder),
            subtitle = if (state.downloadFolderName.isNotBlank() && state.downloadFolderName != "Select Folder")
                state.downloadFolderName else stringResource(R.string.settings_download_folder_choose),
            onClick = { showDownloadLocationDialog = true },
        )

        Divider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.settings_section_app),
            modifier = Modifier.padding(start = 20.dp, bottom = 16.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        SettingsItem(
            icon = Icons.Rounded.Palette,
            title = stringResource(R.string.settings_appearance),
            subtitle = stringResource(R.string.settings_appearance_subtitle),
            onClick = onOpenAppearance,
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SettingsItem(
            icon = Icons.Rounded.Terminal,
            title = stringResource(R.string.settings_engine),
            subtitle = if (state.ytDlpVersion.isNotBlank()) stringResource(R.string.settings_engine_version, state.ytDlpVersion) else stringResource(R.string.settings_engine_update),
            trailingContent = {
                if (state.isUpdatingYtDlp) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            },
            onClick = { vm.updateYtDlp() },
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SettingsItem(
            icon = Icons.Rounded.Schedule,
            title = stringResource(R.string.settings_queue),
            subtitle = stringResource(R.string.settings_queue_subtitle),
            onClick = onOpenQueue,
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SettingsItem(
            icon = Icons.Rounded.BarChart,
            title = stringResource(R.string.settings_statistics),
            subtitle = stringResource(R.string.settings_statistics_subtitle),
            onClick = onOpenStatistics,
        )
        Divider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SettingsItem(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_about_subtitle),
            onClick = onOpenAbout,
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showDownloadLocationDialog) {
        DownloadLocationDialog(
            state = state,
            vm = vm,
            onDismiss = { showDownloadLocationDialog = false },
            onPickFolder = { folderPickerLauncher.launch(null) },
        )
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        trailingContent?.invoke()
    }
}

@Composable
private fun DownloadLocationDialog(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val context = LocalContext.current
        
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.settings_folder_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Text(
                    stringResource(R.string.settings_folder_dialog_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.downloadFolderName.isNotBlank() && state.downloadFolderName != "Select Folder") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.settings_folder_current, state.downloadFolderName),
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.action_close),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    Button(
                        onClick = onPickFolder,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.settings_folder_choose_btn),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

