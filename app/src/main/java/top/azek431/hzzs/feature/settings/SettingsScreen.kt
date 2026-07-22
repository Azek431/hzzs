/**
 * 设置模块 Compose 入口与嵌套导航。
 *
 * 职责：首页 + 分类子页共享同一 [SettingsViewModel]；仅离开整个模块时询问保存/丢弃。
 * 数据流：订阅 draft/baseline/update/algorithm；子页经 [SettingsViewModel.update] 改草稿。
 * 边界：不直接 JNI/权限运行时；dispose 只清除临时预览，不替用户决定保存或丢弃。
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsBreakpoints
import top.azek431.hzzs.core.designsystem.LocalHzzsMotion
import top.azek431.hzzs.core.designsystem.sharedAxisXEnter
import top.azek431.hzzs.core.designsystem.sharedAxisXExit
import top.azek431.hzzs.core.designsystem.sharedAxisXPopEnter
import top.azek431.hzzs.core.designsystem.sharedAxisXPopExit
import top.azek431.hzzs.feature.settings.components.SettingsSaveBar
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.SettingsRoutes
import top.azek431.hzzs.feature.settings.screens.AlgorithmPipelineScreen
import top.azek431.hzzs.feature.settings.screens.AlgorithmSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AppearanceSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AutomationSettingsScreen
import top.azek431.hzzs.feature.settings.screens.CaptureSettingsScreen
import top.azek431.hzzs.feature.settings.screens.LogViewerScreen
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
    exitCoordinator: SettingsExitCoordinator? = null,
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
    var pendingExitAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val currentDirty by rememberUpdatedState(dirty)
    val currentOnExit by rememberUpdatedState(onExit)

    fun requestExit(action: () -> Unit = currentOnExit) {
        if (currentDirty) {
            pendingExitAction = action
            confirmExit = true
        } else {
            vm.discard(action)
        }
    }

    DisposableEffect(exitCoordinator, vm) {
        val registration = exitCoordinator?.attach(::requestExit)
        onDispose {
            registration?.dispose()
            vm.clearPreviewSilently()
        }
    }

    BackHandler {
        if (!onHome) {
            nav.popBackStack()
        } else {
            requestExit()
        }
    }

    val settingsLabel = stringResource(R.string.nav_settings)
    val title = when (route) {
        SettingsRoutes.HOME -> settingsLabel
        SettingsCategory.APPEARANCE.route -> stringResource(SettingsCategory.APPEARANCE.titleRes)
        SettingsCategory.ALGORITHM.route -> stringResource(SettingsCategory.ALGORITHM.titleRes)
        SettingsCategory.CAPTURE.route -> stringResource(SettingsCategory.CAPTURE.titleRes)
        SettingsCategory.OVERLAY.route -> stringResource(SettingsCategory.OVERLAY.titleRes)
        SettingsCategory.AUTOMATION.route -> stringResource(SettingsCategory.AUTOMATION.titleRes)
        SettingsCategory.NETWORK.route -> stringResource(SettingsCategory.NETWORK.titleRes)
        SettingsCategory.MCP.route -> stringResource(SettingsCategory.MCP.titleRes)
        SettingsRoutes.LOG_VIEWER -> "运行日志"
        SettingsRoutes.ALGORITHM_PIPELINE -> "算法流程"
        else -> settingsLabel
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
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
            val wide = maxWidth >= HzzsBreakpoints.SettingsTwoPane
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
            onDismissRequest = {
                confirmExit = false
                pendingExitAction = null
            },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pendingExitAction ?: currentOnExit
                        confirmExit = false
                        pendingExitAction = null
                        vm.save(action)
                    },
                ) { Text(stringResource(R.string.settings_save_and_leave)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val action = pendingExitAction ?: currentOnExit
                        confirmExit = false
                        pendingExitAction = null
                        vm.discard(action)
                    },
                ) { Text(stringResource(R.string.action_discard)) }
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
    val motion = LocalHzzsMotion.current
    NavHost(
        navController = nav,
        startDestination = if (startAtHome) SettingsRoutes.HOME else SettingsCategory.APPEARANCE.route,
        modifier = modifier,
        enterTransition = { motion.sharedAxisXEnter() },
        exitTransition = { motion.sharedAxisXExit() },
        popEnterTransition = { motion.sharedAxisXPopEnter() },
        popExitTransition = { motion.sharedAxisXPopExit() },
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
            val mcpState by vm.mcpState.collectAsState()
            val debugFrameCount by vm.debugFrameCount.collectAsState()
            val benchmark by vm.benchmark.collectAsState()
            McpDeveloperSettingsScreen(
                config = config,
                update = vm::update,
                mcpState = mcpState,
                debugFrameCount = debugFrameCount,
                benchmark = benchmark,
                onRefreshDebugFrames = vm::refreshDebugFrameCount,
                onClearDebugFrames = vm::clearDebugFrames,
                onRunBenchmark = vm::runNativeBenchmark,
                onBuildDiagnostics = vm::buildDiagnosticsReport,
                onOpenLogViewer = {
                    nav.navigate(SettingsRoutes.LOG_VIEWER) { launchSingleTop = true }
                },
                onOpenAlgorithmPipeline = {
                    nav.navigate(SettingsRoutes.ALGORITHM_PIPELINE) { launchSingleTop = true }
                },
                onMessage = onMessage,
            )
        }
        composable(SettingsRoutes.LOG_VIEWER) {
            LogViewerScreen(
                onBack = { nav.popBackStack() },
                onMessage = onMessage,
            )
        }
        composable(SettingsRoutes.ALGORITHM_PIPELINE) {
            AlgorithmPipelineScreen(
                onBack = { nav.popBackStack() },
                onMessage = onMessage,
            )
        }
    }
}
