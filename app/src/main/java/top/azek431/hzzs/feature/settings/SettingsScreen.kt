/**
 * 设置模块 Compose 入口与嵌套导航。
 *
 * 职责：首页 + 分类子页共享同一 [SettingsViewModel]；改动即时落盘。
 * 数据流：订阅 draft/update/algorithm；子页经 [SettingsViewModel.update] 改配置。
 * 边界：不直接 JNI/权限运行时；离开时 [SettingsViewModel.flushNow] 刷盘。
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
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
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.SettingsRoutes
import top.azek431.hzzs.feature.settings.screens.AlgorithmPipelineScreen
import top.azek431.hzzs.feature.settings.screens.AlgorithmSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AppearanceSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AutomationSettingsScreen
import top.azek431.hzzs.feature.settings.screens.CaptureSettingsScreen
import top.azek431.hzzs.feature.settings.screens.DeveloperSettingsScreen
import top.azek431.hzzs.feature.settings.screens.LogViewerScreen
import top.azek431.hzzs.feature.settings.screens.McpSettingsScreen
import top.azek431.hzzs.feature.settings.screens.NetworkUpdateSettingsScreen
import top.azek431.hzzs.feature.settings.screens.OverlaySettingsScreen
import top.azek431.hzzs.feature.settings.screens.SettingsHomeScreen

/**
 * 设置模块入口：首页 + 分类子页共享同一 [SettingsViewModel]。
 * 配置改动即时落盘；离开时刷盘。宽屏左侧常驻首页目录，右侧为分类内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    exitCoordinator: SettingsExitCoordinator? = null,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val config by vm.draft.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val algorithmState by vm.algorithmState.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: SettingsRoutes.HOME
    val onHome = route == SettingsRoutes.HOME
    var message by remember { mutableStateOf<String?>(null) }

    val currentOnExit by rememberUpdatedState(onExit)

    fun leave(action: () -> Unit = currentOnExit) {
        vm.flushNow(action)
    }

    DisposableEffect(exitCoordinator, vm) {
        val registration = exitCoordinator?.attach { onDone -> vm.flushNow(onDone) }
        onDispose {
            registration?.dispose()
            vm.onLeaveComposition()
        }
    }

    BackHandler {
        if (!onHome) {
            nav.popBackStack()
        } else {
            leave()
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
        SettingsCategory.DEVELOPER.route -> stringResource(SettingsCategory.DEVELOPER.titleRes)
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
                            if (onHome) leave() else nav.popBackStack()
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
}

/** 设置内嵌 NavHost：把共享配置与即时任务回调分发给各分类屏。 */
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
            McpSettingsScreen(
                config = config,
                update = vm::update,
                mcpState = mcpState,
                onMessage = onMessage,
            )
        }
        composable(SettingsCategory.DEVELOPER.route) {
            val debugFrameCount by vm.debugFrameCount.collectAsState()
            val benchmark by vm.benchmark.collectAsState()
            DeveloperSettingsScreen(
                developerEnabled = config.developer.enabled,
                config = config,
                update = vm::update,
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
