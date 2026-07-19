package top.azek431.hzzs

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import top.azek431.hzzs.core.designsystem.HzzsTheme
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.feature.about.AboutScreen
import top.azek431.hzzs.feature.about.DonationKind
import top.azek431.hzzs.feature.home.HomeScreen
import top.azek431.hzzs.feature.runtime.RuntimeScreen
import top.azek431.hzzs.feature.settings.SettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { HzzsRoot(onSaveDonation = ::saveDonationImage) }
    }

    private fun saveDonationImage(kind: DonationKind) {
        val resId = if (kind == DonationKind.WECHAT) top.azek431.hzzs.feature.about.R.drawable.donation_wechat else top.azek431.hzzs.feature.about.R.drawable.donation_alipay
        val extension = if (kind == DonationKind.WECHAT) "png" else "jpg"
        val mime = if (extension == "png") "image/png" else "image/jpeg"
        val name = "Azek_${kind.name.lowercase()}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HZZS")
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { output -> resources.openRawResource(resId).use { it.copyTo(output) } }
        }
    }
}

@HiltViewModel
class AppViewModel @Inject constructor(repository: SettingsRepository) : ViewModel() {
    val config: StateFlow<AppConfig> = repository.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppConfig())
}

private enum class Destination(val route: String, val label: String) {
    HOME("home", "首页"), RUNTIME("runtime", "运行"), SETTINGS("settings", "设置"), ABOUT("about", "关于")
}

@Composable
private fun HzzsRoot(onSaveDonation: (DonationKind) -> Unit, vm: AppViewModel = hiltViewModel()) {
    val config by vm.config.collectAsState()
    HzzsTheme(config.theme) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = entry?.destination?.route
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row {
                    NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(selected = current == destination.route, onClick = { nav.navigate(destination.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon(destination), null) }, label = { Text(destination.label) })
                        }
                    }
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    AppNavHost(nav, onSaveDonation, Modifier.weight(1f))
                }
            } else {
                Scaffold(bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected = current == destination.route, onClick = { nav.navigate(destination.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon(destination), null) }, label = { Text(destination.label) })
                        }
                    }
                }) { padding -> AppNavHost(nav, onSaveDonation, Modifier.padding(padding)) }
            }
        }
    }
}

@Composable private fun AppNavHost(nav: androidx.navigation.NavHostController, onSaveDonation: (DonationKind) -> Unit, modifier: Modifier) {
    NavHost(navController = nav, startDestination = Destination.HOME.route, modifier = modifier) {
        composable(Destination.HOME.route) { HomeScreen(onOpenRuntime = { nav.navigate(Destination.RUNTIME.route) }, onOpenSettings = { nav.navigate(Destination.SETTINGS.route) }) }
        composable(Destination.RUNTIME.route) { RuntimeScreen() }
        composable(Destination.SETTINGS.route) { SettingsScreen(onExit = { nav.popBackStack() }) }
        composable(Destination.ABOUT.route) { AboutScreen(onSaveQr = onSaveDonation) }
    }
}

private fun icon(destination: Destination) = when (destination) {
    Destination.HOME -> Icons.Rounded.Home
    Destination.RUNTIME -> Icons.Rounded.PlayCircle
    Destination.SETTINGS -> Icons.Rounded.Settings
    Destination.ABOUT -> Icons.Rounded.Info
}
