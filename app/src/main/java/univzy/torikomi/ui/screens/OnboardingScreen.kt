package univzy.torikomi.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import univzy.torikomi.R

/**
 * Multi-step onboarding wizard shown on first launch.
 *
 *  1. Welcome
 *  2. Notification permission (Android 13+)
 *  3. Install-unknown-apps permission (needed to install extension APKs)
 *  4. Choose storage folder
 *  5. Get started!
 *
 * Steps that don't apply on the current OS / state are skipped automatically.
 * Transitions between steps use a horizontal slide + fade.
 */
@Composable
fun OnboardingScreen(onComplete: (folderUri: String, folderName: String) -> Unit) {
    val context = LocalContext.current

    val steps = remember {
        buildList {
            add(OnboardingStep.WELCOME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(OnboardingStep.NOTIFICATIONS)
            }
            add(OnboardingStep.INSTALL_PERMISSION)
            add(OnboardingStep.STORAGE)
            add(OnboardingStep.DONE)
        }
    }

    // Indexing rather than enum-direct so we can animate forward/backward.
    var stepIndex by remember { mutableStateOf(0) }
    var direction by remember { mutableStateOf(1) } // 1 = forward, -1 = back
    val current = steps[stepIndex]

    // Storage state
    var customFolderUri by remember { mutableStateOf("") }
    var customFolderName by remember { mutableStateOf("") }
    var useCustomFolder by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            customFolderUri = uri.toString()
            customFolderName = uri.lastPathSegment
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?: "Custom Folder"
            useCustomFolder = true
        }
    }

    // Permission state
    var notificationsGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    // canRequestPackageInstalls is dynamic — re-check when the activity resumes
    // (user may toggle the system setting).
    var installPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true
            else context.packageManager.canRequestPackageInstalls()
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ) {
                installPermissionGranted = context.packageManager.canRequestPackageInstalls()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canGoBack by remember(stepIndex) { derivedStateOf { stepIndex > 0 } }

    fun goNext() {
        if (stepIndex < steps.lastIndex) {
            direction = 1
            stepIndex += 1
        }
    }
    fun goBack() {
        if (stepIndex > 0) {
            direction = -1
            stepIndex -= 1
        }
    }
    fun finish() {
        if (useCustomFolder && customFolderUri.isNotBlank()) {
            onComplete(customFolderUri, customFolderName)
        } else {
            onComplete("", "Torikomi")
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // safeDrawing covers status bar + navigation bar + display
                // cutout (punch-hole / notch). More reliable than systemBars on
                // OEM ROMs (MIUI/HyperOS, OneUI) where systemBars can be
                // reported as 0 on the first composition.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            val backAlpha by animateFloatAsState(
                targetValue = if (canGoBack) 1f else 0f,
                animationSpec = tween(200),
                label = "back-alpha",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (canGoBack) goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.alpha(backAlpha),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = if (canGoBack) "Back" else null,
                    )
                }
                Spacer(Modifier.width(4.dp))
                StepProgressIndicator(
                    total = steps.size,
                    currentIndex = stepIndex,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(32.dp))

            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val slideIn = slideInHorizontally(
                        animationSpec = tween(durationMillis = 320),
                        initialOffsetX = { full -> direction * full },
                    ) + fadeIn(animationSpec = tween(220))
                    val slideOut = slideOutHorizontally(
                        animationSpec = tween(durationMillis = 320),
                        targetOffsetX = { full -> -direction * full },
                    ) + fadeOut(animationSpec = tween(180))
                    slideIn togetherWith slideOut
                },
                label = "onboarding-step",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.NOTIFICATIONS -> NotificationsStep(
                        granted = notificationsGranted,
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    OnboardingStep.INSTALL_PERMISSION -> InstallPermissionStep(
                        granted = installPermissionGranted,
                        onRequest = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                    )
                    OnboardingStep.STORAGE -> StorageStep(
                        useCustomFolder = useCustomFolder,
                        customFolderName = customFolderName,
                        onSelectDefault = { useCustomFolder = false },
                        onPickCustomFolder = { folderPicker.launch(null) },
                    )
                    OnboardingStep.DONE -> DoneStep(
                        usingCustomFolder = useCustomFolder,
                        customFolderName = customFolderName,
                    )
                }
            }

            val (label, enabled, action) = remember(
                current, notificationsGranted, installPermissionGranted, useCustomFolder, customFolderUri,
            ) {
                when (current) {
                    OnboardingStep.WELCOME ->
                        Triple("Get started", true) { goNext() }

                    OnboardingStep.NOTIFICATIONS ->
                        if (notificationsGranted)
                            Triple("Continue", true) { goNext() }
                        else
                            // Force the user to grant the permission first.
                            Triple("Allow notifications to continue", false) { }

                    OnboardingStep.INSTALL_PERMISSION ->
                        if (installPermissionGranted)
                            Triple("Continue", true) { goNext() }
                        else
                            Triple("Allow installs to continue", false) { }

                    OnboardingStep.STORAGE -> {
                        val ok = !useCustomFolder || customFolderUri.isNotBlank()
                        Triple(if (ok) "Continue" else "Pick a folder first", ok) { if (ok) goNext() }
                    }

                    OnboardingStep.DONE ->
                        Triple("Enter Torikomi", true) { finish() }
                }
            }

            Button(
                onClick = action,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

private enum class OnboardingStep {
    WELCOME, NOTIFICATIONS, INSTALL_PERMISSION, STORAGE, DONE
}

@Composable
private fun WelcomeStep() {
    val infinite = rememberInfiniteTransition(label = "welcome-pulse")
    val scale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.torikomi_icon),
            contentDescription = "Torikomi",
            modifier = Modifier
                .size(120.dp)
                .scale(scale),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Welcome to Torikomi",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "A multi-platform downloader powered by\nplugin-based extensions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "We'll set up a few permissions and choose\nwhere to save your downloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun NotificationsStep(granted: Boolean, onRequest: () -> Unit) {
    PermissionStepLayout(
        icon = Icons.Rounded.NotificationsActive,
        title = "Stay updated",
        description = "Allow notifications so Torikomi can show download progress and alert you about new app or extension updates.",
        granted = granted,
        grantedLabel = "Notifications enabled",
        actionLabel = "Allow notifications",
        onRequest = onRequest,
    )
}

@Composable
private fun InstallPermissionStep(granted: Boolean, onRequest: () -> Unit) {
    PermissionStepLayout(
        icon = Icons.Rounded.Extension,
        title = "Allow extension installs",
        description = "Extensions are small APK plugins that add support for each platform. Torikomi needs permission to install them from inside the app.",
        granted = granted,
        grantedLabel = "Allowed by system",
        actionLabel = "Open system settings",
        onRequest = onRequest,
    )
}

@Composable
private fun PermissionStepLayout(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    grantedLabel: String,
    actionLabel: String,
    onRequest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedPermissionIcon(icon = icon, granted = granted)
        Spacer(Modifier.height(28.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(28.dp))

        AnimatedContent(
            targetState = granted,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally { it / 4 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally { -it / 4 })
            },
            label = "perm-state",
        ) { isGranted ->
            if (isGranted) {
                GrantedPill(label = grantedLabel)
            } else {
                OutlinedButton(
                    onClick = onRequest,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(actionLabel, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun AnimatedPermissionIcon(icon: ImageVector, granted: Boolean) {
    val targetSize by animateDpAsState(
        targetValue = if (granted) 92.dp else 88.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "icon-size",
    )
    val bgColor by animateColorAsState(
        targetValue = if (granted)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        animationSpec = tween(280),
        label = "icon-bg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (granted)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = tween(280),
        label = "icon-tint",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(140.dp)
            .background(bgColor, CircleShape),
    ) {
        AnimatedContent(
            targetState = granted,
            transitionSpec = {
                (fadeIn(tween(260)) + scaleInSpring()) togetherWith
                    (fadeOut(tween(180)) + scaleOutSpring())
            },
            label = "icon-swap",
        ) { isGranted ->
            Icon(
                imageVector = if (isGranted) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(targetSize),
            )
        }
    }
}

private fun scaleInSpring() = androidx.compose.animation.scaleIn(
    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
    initialScale = 0.6f,
)
private fun scaleOutSpring() = androidx.compose.animation.scaleOut(
    animationSpec = tween(180),
    targetScale = 0.8f,
)

@Composable
private fun GrantedPill(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = Modifier.wrapContentSize(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Done,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun StorageStep(
    useCustomFolder: Boolean,
    customFolderName: String,
    onSelectDefault: () -> Unit,
    onPickCustomFolder: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Where should we save downloads?",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        FolderOptionCard(
            selected = !useCustomFolder,
            icon = Icons.Rounded.CreateNewFolder,
            title = "Default folder",
            description = "Files will be saved to\nTorikomi (sdcard root)",
            onClick = onSelectDefault,
        )
        Spacer(Modifier.height(12.dp))
        FolderOptionCard(
            selected = useCustomFolder,
            icon = Icons.Rounded.FolderOpen,
            title = "Custom folder",
            description = if (customFolderName.isNotBlank())
                "Selected: $customFolderName"
            else
                "Tap to pick a folder from your storage",
            onClick = onPickCustomFolder,
        )
    }
}

@Composable
private fun DoneStep(usingCustomFolder: Boolean, customFolderName: String) {
    val infinite = rememberInfiniteTransition(label = "rocket")
    val tilt by infinite.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rocket-tilt",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(160.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.RocketLaunch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(86.dp)
                    .scale(1f + tilt / 200f),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "All set!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        val saveTo = if (usingCustomFolder && customFolderName.isNotBlank())
            "Saving to $customFolderName"
        else
            "Saving to default Torikomi folder"
        Text(
            text = "Torikomi is ready to use.\n$saveTo",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun StepProgressIndicator(
    total: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 0 until total) {
            val isActive = i == currentIndex
            val isDone = i < currentIndex
            val width by animateDpAsState(
                targetValue = if (isActive) 28.dp else 10.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "dot-width",
            )
            val color by animateColorAsState(
                targetValue = when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isDone -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                animationSpec = tween(220),
                label = "dot-color",
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .background(color = color, shape = RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun FolderOptionCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        animationSpec = tween(220),
        label = "border",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "bg",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        animationSpec = tween(220),
        label = "icon-tint",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                exit = fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.6f),
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
