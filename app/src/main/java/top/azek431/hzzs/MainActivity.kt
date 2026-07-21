package top.azek431.hzzs

import android.annotation.SuppressLint

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.designsystem.HzzsTheme
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.feature.about.AboutScreen
import top.azek431.hzzs.feature.about.DonationKind
import top.azek431.hzzs.feature.home.HomeScreen
import top.azek431.hzzs.feature.onboarding.OnboardingScreen
import top.azek431.hzzs.feature.runtime.RuntimeScreen
import top.azek431.hzzs.feature.settings.SettingsScreen
import top.azek431.hzzs.mcp.McpApprovalRequest
import top.azek431.hzzs.mcp.McpForegroundService
import top.azek431.hzzs.mcp.McpUiBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The single exported activity. Navigation, onboarding and MCP approval UI stay
 * here so feature packages do not own process-level Android lifecycle concerns.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingDonation: DonationKind? = null
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingDonation
        pendingDonation = null
        if (granted && pending != null) {
            saveDonationImage(pending)
        } else if (!granted) {
            Toast.makeText(this, "未授予图片保存权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HzzsRoot(onSaveDonation = ::saveDonationImage) }
    }

    @SuppressLint("ResourceType")
    private fun saveDonationImage(kind: DonationKind) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDonation = kind
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val resId = if (kind == DonationKind.WECHAT) {
            R.drawable.donation_wechat
        } else {
            R.drawable.donation_alipay
        }
        val extension = if (kind == DonationKind.WECHAT) "png" else "jpg"
        val mime = if (extension == "png") "image/png" else "image/jpeg"
        val name = "Azek_${kind.name.lowercase()}_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.$extension"

        var inserted: android.net.Uri? = null
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HZZS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            inserted = requireNotNull(
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
            ) { "系统未创建图片记录" }
            contentResolver.openOutputStream(requireNotNull(inserted), "w")?.use { output ->
                resources.openRawResource(resId).use { input -> input.copyTo(output) }
            } ?: error("无法写入图片")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    requireNotNull(inserted),
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }.onSuccess {
            Toast.makeText(this, "赞赏码已保存到相册 HZZS", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            inserted?.let { contentResolver.delete(it, null, null) }
            Toast.makeText(
                this,
                "保存失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: SettingsRepository,
    val mcpUiBridge: McpUiBridge,
    private val updateRepository: top.azek431.hzzs.core.update.UpdateRepository,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )

    /** 启动后按配置尝试静默检查更新；失败只写日志，不打扰用户。 */
    fun maybeAutoCheckUpdates() {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            if (!snapshot.update.autoCheck) return@launch
            runCatching {
                updateRepository.check(beta = snapshot.update.channel == top.azek431.hzzs.core.model.UpdateChannel.BETA)
            }
        }
    }
    val approval: StateFlow<McpApprovalRequest?> = mcpUiBridge.approval
    val mcpNavigation: StateFlow<String?> = mcpUiBridge.navigation

    fun preview(config: AppConfig) {
        viewModelScope.launch { repository.preview(config) }
    }

    fun completeOnboarding(config: AppConfig) {
        viewModelScope.launch { repository.save(config) }
    }

    fun resolveApproval(request: McpApprovalRequest, approved: Boolean) {
        mcpUiBridge.resolveApproval(request.id, approved)
    }

    fun consumeMcpNavigation(route: String) {
        mcpUiBridge.consumeNavigation(route)
    }
}

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "首页", Icons.Rounded.Home),
    RUNTIME("runtime", "运行", Icons.Rounded.PlayCircle),
    SETTINGS("settings", "设置", Icons.Rounded.Settings),
    ABOUT("about", "关于", Icons.Rounded.Info),
}

@Composable
private fun HzzsRoot(
    onSaveDonation: (DonationKind) -> Unit,
    vm: AppViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val approval by vm.approval.collectAsState()
    val mcpNavigation by vm.mcpNavigation.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(config.mcp.enabled, config.mcp.port, config.mcp.permissionLevel) {
        syncMcpService(
            context = context,
            enabled = config.mcp.enabled,
            fingerprint = "${config.mcp.enabled}:${config.mcp.port}:${config.mcp.permissionLevel}",
        )
    }
    LaunchedEffect(Unit) {
        vm.maybeAutoCheckUpdates()
    }

    HzzsTheme(config.theme) {
        if (!config.onboarding.completed) {
            OnboardingScreen(
                initial = config,
                onPreview = vm::preview,
                onComplete = vm::completeOnboarding,
            )
        } else {
            MainNavigation(
                onSaveDonation = onSaveDonation,
                requestedRoute = mcpNavigation,
                onRouteConsumed = vm::consumeMcpNavigation,
            )
        }

        approval?.let { request ->
            McpApprovalDialog(
                request = request,
                onResolve = { approved -> vm.resolveApproval(request, approved) },
            )
        }
    }
}

@Composable
private fun MainNavigation(
    onSaveDonation: (DonationKind) -> Unit,
    requestedRoute: String?,
    onRouteConsumed: (String) -> Unit,
) {
    val nav = rememberNavController()
    LaunchedEffect(requestedRoute) {
        requestedRoute?.let { route ->
            nav.open(route)
            onRouteConsumed(route)
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = current == destination.route,
                            onClick = { nav.open(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                AppNavHost(nav, onSaveDonation, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = current == destination.route,
                                onClick = { nav.open(destination.route) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AppNavHost(nav, onSaveDonation, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    onSaveDonation: (DonationKind) -> Unit,
    modifier: Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = Destination.HOME.route,
        modifier = modifier,
    ) {
        composable(Destination.HOME.route) {
            HomeScreen(
                onOpenRuntime = { nav.navigate(Destination.RUNTIME.route) },
                onOpenSettings = { nav.navigate(Destination.SETTINGS.route) },
            )
        }
        composable(Destination.RUNTIME.route) { RuntimeScreen() }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(onExit = { nav.popBackStack() })
        }
        composable(Destination.ABOUT.route) {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onSaveQr = onSaveDonation,
            )
        }
    }
}

@Composable
private fun McpApprovalDialog(
    request: McpApprovalRequest,
    onResolve: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null) },
        title = { Text("MCP 操作确认") },
        text = {
            Text("工具：${request.tool}\n\n${request.summary}\n\n仅批准你理解并信任的 AI 操作。")
        },
        confirmButton = { Button(onClick = { onResolve(true) }) { Text("允许一次") } },
        dismissButton = { OutlinedButton(onClick = { onResolve(false) }) { Text("拒绝") } },
    )
}

private fun NavHostController.open(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private var lastMcpFingerprint: String? = null

private fun syncMcpService(context: Context, enabled: Boolean, fingerprint: String) {
    if (enabled) {
        if (lastMcpFingerprint == fingerprint) return
        // 端口/权限变化：先发 ACTION_STOP，确保旧 socket 关闭后再 START。
        if (lastMcpFingerprint != null) {
            context.startService(
                Intent(context, McpForegroundService::class.java)
                    .setAction(McpForegroundService.ACTION_STOP),
            )
            context.stopService(Intent(context, McpForegroundService::class.java))
        }
        val intent = Intent(context, McpForegroundService::class.java)
            .setAction(McpForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
        lastMcpFingerprint = fingerprint
    } else {
        context.startService(
            Intent(context, McpForegroundService::class.java)
                .setAction(McpForegroundService.ACTION_STOP),
        )
        context.stopService(Intent(context, McpForegroundService::class.java))
        lastMcpFingerprint = null
    }
}
