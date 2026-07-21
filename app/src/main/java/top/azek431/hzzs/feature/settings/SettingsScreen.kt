/**
 * 设置模块 Compose 入口与嵌套导航。
 *
 * 职责：首页 + 分类子页共享同一 [SettingsViewModel]；仅离开整个模块时询问保存/丢弃。
 * 数据流：订阅 draft/baseline/update/algorithm；子页经 [SettingsViewModel.update] 改草稿。
 * 边界：不直接 JNI/权限运行时；dispose 时 [discardSilently] 清理未提交预览。
 */
package top.azek431.hzzs.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import top.azek431.hzzs.feature.settings.components.SettingsSaveBar
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.SettingsRoutes
import top.azek431.hzzs.feature.settings.screens.AlgorithmSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AppearanceSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AutomationSettingsScreen
import top.azek431.hzzs.feature.settings.screens.CaptureSettingsScreen
import top.azek431.hzzs.feature.settings.screens.McpDeveloperSettingsScreen
import top.azek431.hzzs.feature.settings.screens.NetworkUpdateSettingsScreen
import top.azek431.hzzs.feature.settings.screens.OverlaySettingsScreen
import top.azek431.hzzs.feature.settings.screens.SettingsHomeScreen

/**
 * 设置模块入口：首页 + 分类子页共享同一 [SettingsViewModel]。
 * 仅在离开整个设置模块时询问保存/丢弃；子页返回首页保留草稿。
 * 宽屏左侧常驻首页目录，右侧为分类内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val config by vm.draft.collectAsState()
    val baseline by vm.baseline.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val algorithmState by vm.algorithmState.collectAsState()
    val dirty = config != baseline
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: SettingsRoutes.HOME
    val onHome = route == SettingsRoutes.HOME
    var confirmExit by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(vm) {
        onDispose { vm.discardSilently() }
    }

    fun requestExit() {
        if (dirty) confirmExit = true else vm.discard(onExit)
    }

    BackHandler {
        if (!onHome) {
            nav.popBackStack()
        } else {
            requestExit()
        }
    }

    val title = when (route) {
        SettingsRoutes.HOME -> "设置"
        SettingsCategory.APPEARANCE.route -> SettingsCategory.APPEARANCE.title
        SettingsCategory.ALGORITHM.route -> SettingsCategory.ALGORITHM.title
        SettingsCategory.CAPTURE.route -> SettingsCategory.CAPTURE.title
        SettingsCategory.OVERLAY.route -> SettingsCategory.OVERLAY.title
        SettingsCategory.AUTOMATION.route -> SettingsCategory.AUTOMATION.title
        SettingsCategory.NETWORK.route -> SettingsCategory.NETWORK.title
        SettingsCategory.MCP.route -> SettingsCategory.MCP.title
        else -> "设置"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (onHome) requestExit() else nav.popBackStack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            SettingsSaveBar(
                dirty = dirty,
                onCancel = { requestExit() },
                onSave = { vm.save(onExit) },
            )
        },
        snackbarHost = {
            message?.let { text ->
                LaunchedEffect(text) {
                    delay(3_000)
                    message = null
                }
                Snackbar { Text(text) }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .widthIn(max = 360.dp)
                            .weight(0.38f),
                    ) {
                        SettingsHomeScreen(
                            config = config,
                            algorithmState = algorithmState,
                            onOpen = { category ->
                                if (route != category.route) {
                                    nav.navigate(category.route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            selectedRoute = route,
                        )
                    }
                    SettingsNavHost(
                        nav = nav,
                        vm = vm,
                        config = config,
                        updateState = updateState,
                        algorithmState = algorithmState,
                        onMessage = { message = it },
                        modifier = Modifier.weight(1f),
                        startAtHome = false,
                    )
                }
            } else {
                SettingsNavHost(
                    nav = nav,
                    vm = vm,
                    config = config,
                    updateState = updateState,
                    algorithmState = algorithmState,
                    onMessage = { message = it },
                    modifier = Modifier.fillMaxSize(),
                    startAtHome = true,
                )
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("未保存的更改") },
            text = { Text("要保存当前设置，还是丢弃更改并离开？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        vm.save(onExit)
                    },
                ) { Text("保存并离开") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        vm.discard(onExit)
                    },
                ) { Text("丢弃") }
            },
        )
    }
}

/** 设置内嵌 NavHost：把共享草稿与即时任务回调分发给各分类屏。 */
@Composable
private fun SettingsNavHost(
    nav: NavHostController,
    vm: SettingsViewModel,
    config: top.azek431.hzzs.core.model.AppConfig,
    updateState: UpdateUiState,
    algorithmState: top.azek431.hzzs.core.algorithm.AlgorithmCatalogState,
    onMessage: (String) -> Unit,
    modifier: Modifier,
    startAtHome: Boolean,
) {
    NavHost(
        navController = nav,
        startDestination = if (startAtHome) SettingsRoutes.HOME else SettingsCategory.APPEARANCE.route,
        modifier = modifier,
    ) {
        composable(SettingsRoutes.HOME) {
            SettingsHomeScreen(
                config = config,
                algorithmState = algorithmState,
                onOpen = { category ->
                    nav.navigate(category.route) { launchSingleTop = true }
                },
            )
        }
        composable(SettingsCategory.APPEARANCE.route) {
            AppearanceSettingsScreen(
                config = config,
                update = vm::update,
                exportTheme = vm::exportTheme,
                importTheme = vm::importTheme,
                onMessage = onMessage,
            )
        }
        composable(SettingsCategory.ALGORITHM.route) {
            AlgorithmSettingsScreen(
                config = config,
                algorithmState = algorithmState,
                update = vm::update,
                onRefresh = vm::refreshAlgorithms,
                onDownload = vm::downloadAlgorithm,
                onCancelDownload = vm::cancelAlgorithmDownload,
                onSelect = vm::selectAlgorithm,
                onMessage = onMessage,
            )
        }
        composable(SettingsCategory.CAPTURE.route) {
            CaptureSettingsScreen(
                config = config,
                capabilities = vm.capabilities,
                update = vm::update,
            )
        }
        composable(SettingsCategory.OVERLAY.route) {
            OverlaySettingsScreen(config = config, update = vm::update)
        }
        composable(SettingsCategory.AUTOMATION.route) {
            AutomationSettingsScreen(config = config, update = vm::update)
        }
        composable(SettingsCategory.NETWORK.route) {
            NetworkUpdateSettingsScreen(
                config = config,
                updateState = updateState,
                algorithmState = algorithmState,
                update = vm::update,
                onCheckApp = vm::checkForUpdates,
                onDownloadApp = vm::downloadAvailableUpdate,
                onInstallApp = vm::installDownloadedUpdate,
                onIgnoreApp = vm::ignoreAvailableUpdate,
                onRefreshAlgorithms = vm::refreshAlgorithms,
            )
        }
        composable(SettingsCategory.MCP.route) {
            McpDeveloperSettingsScreen(config = config, update = vm::update)
        }
    }
}
