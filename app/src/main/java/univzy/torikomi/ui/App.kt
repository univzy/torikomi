package univzy.torikomi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import univzy.torikomi.Torikomi
import univzy.torikomi.ui.screens.DownloadScreen
import univzy.torikomi.ui.screens.HomeScreen
import univzy.torikomi.ui.screens.OnboardingScreen
import univzy.torikomi.ui.screens.SplashScreen
import univzy.torikomi.ui.screens.WhatsAppStatusScreen
import univzy.torikomi.ui.theme.TorikomiDownloaderTheme
import univzy.torikomi.util.UrlDetectorService
import java.net.URLEncoder
import java.net.URLDecoder

@Composable
fun App(initialSharedUrl: String? = null) {
    val context      = LocalContext.current
    val factory      = AppViewModelFactory(context.applicationContext as Torikomi)
    val themeVm: ThemeViewModel = viewModel(factory = factory)
    val startupVm: StartupUpdateViewModel = viewModel(factory = factory)
    val splashVm: SplashViewModel = viewModel(factory = factory)
    val themeMode   by themeVm.themeMode.collectAsState()
    val startupState by startupVm.uiState.collectAsState()
    val splashState  by splashVm.uiState.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.DARK   -> true
        AppThemeMode.LIGHT  -> false
        AppThemeMode.SYSTEM -> isSystemDark
    }

    TorikomiDownloaderTheme(darkTheme = isDark) {
        val navController = rememberNavController()

        // Navigate based on splash state
        LaunchedEffect(splashState.destination) {
            when (splashState.destination) {
                SplashDestination.HOME -> navController.navigate("home") {
                    popUpTo("splash") { inclusive = true }
                }
                SplashDestination.ONBOARDING -> navController.navigate("onboarding") {
                    popUpTo("splash") { inclusive = true }
                }
                SplashDestination.LOADING -> Unit
            }
        }

        // Handle initial shared URL (only after onboarding is done)
        LaunchedEffect(initialSharedUrl, splashState.destination) {
            if (splashState.destination == SplashDestination.HOME && !initialSharedUrl.isNullOrBlank()) {
                val url = UrlDetectorService.extractUrl(initialSharedUrl)
                val platform = url?.let { UrlDetectorService.detectPlatform(it) }
                if (url != null && platform != null) {
                    val encoded = URLEncoder.encode(url, "UTF-8")
                    navController.navigate("download/$platform?initialUrl=$encoded")
                }
            }
        }

        NavHost(navController = navController, startDestination = "splash") {
            composable("splash") {
                SplashScreen(loadingStep = splashState.loadingStep)
            }
            composable("onboarding") {
                OnboardingScreen(
                    onComplete = { uri, name -> splashVm.completeOnboarding(uri, name) },
                )
            }
            composable("home") {
                HomeScreen(
                    navController = navController,
                    isDark        = isDark,
                    onToggleTheme = { themeVm.toggleTheme(isDark) },
                )
            }
            composable(
                route     = "download/{platform}?initialUrl={initialUrl}",
                arguments = listOf(
                    navArgument("platform")   { type = NavType.StringType },
                    navArgument("initialUrl") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStack ->
                val platform   = backStack.arguments?.getString("platform") ?: ""
                val encodedUrl = backStack.arguments?.getString("initialUrl") ?: ""
                val decodedUrl = if (encodedUrl.isNotEmpty())
                    URLDecoder.decode(encodedUrl, "UTF-8") else ""
                DownloadScreen(
                    platform   = platform,
                    initialUrl = decodedUrl,
                    onBack     = { navController.popBackStack() },
                )
            }
            composable("whatsapp") {
                WhatsAppStatusScreen(onBack = { navController.popBackStack() })
            }
        }

        startupState.updateRelease?.let { release ->
            AlertDialog(
                onDismissRequest = { startupVm.dismissUpdatePopup() },
                title = { Text("Update Available") },
                text = {
                    Text(
                        "A newer version (${release.tagName}) is available.\n\n" +
                            release.body.ifBlank { "No changelog provided." }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
                        context.startActivity(intent)
                        startupVm.dismissUpdatePopup()
                    }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { startupVm.dismissUpdatePopup() }) {
                        Text("Later")
                    }
                },
            )
        }
    }
}
