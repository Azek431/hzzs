# 代理经验摘录

跨会话可复用的**工程经验**（短条）。硬约束仍以根目录 `CLAUDE.md` 与源码为准。  
会话级偏好写入 Claude 项目记忆；此处只记对仓库协作者也有用的条目。

## 2026-07-24

- **VS Code 任务 + adb + PowerShell Stop**：原生 `adb` 写 stderr 时，在 `Continue=Stop` 下会变 NativeCommandError。统一 `Invoke-HzzsAdb`；`--remove` 无转发时用 `-IgnoreFailure`。嵌套脚本用 `return` 勿 `exit`。脚本用 **UTF-8 BOM** 兼容 Windows PowerShell 5.1；`Set-StrictMode` 下勿盲读未赋值 `0`。

- **MCP 默认免鉴权 + 持久 Token**：`requireAuth` 默认 false；开启时 `authToken` 落盘，**不**在每次启动 `randomToken()`。RikkaHub「配对令牌无效」常见因旧版每次启动轮换后客户端仍持旧 Token——现应稳定；用户要换令牌时点设置页「轮换 Token」并重新导入 JSON。
- **酱油多点找色移植要点**：脚本无绘制；设计分辨率 **1272×2772**（非注释 1080）；颜色 Java 有符号 `0xAARRGGBB`；region 约 left0.23/top0.44/right1/bottom0.88；阈值默认 10。HZZS 只走 `Detection.bounds` 通用 HUD；「复活」不进算法包动作。
- **场景必须匹配包**：`sea-salt-living-room-v1` 只声明海盐；钉选后若场景仍是竹影则找色不会跑。现手动选**仅单赛季**包会自动切赛季；多赛季包仍须用户自选场景。
- **海盐触发距离默认 5.0 玩家宽**：FIXED 玩家宽约 0.05 时 1.4 仅 ~0.07 屏宽，酱油较远点击约 0.25+ 屏宽 → 默认 5.0；`validated` 上限 8。调参看 `algo.decision` 的 `nearGap`/`trigDist`。
- **触发距离可自调**：`autoAdjustTriggerDistance` 默认 true；`TriggerDistanceAutoTuner` 在 no_candidate+近障碍时缓升、plan 成功过近时向基线缓降；落盘节流。用户拖滑条会清缓存。

## 2026-07-23

- **MCP 同机连 RikkaHub**：服务必须绑 IPv4 `127.0.0.1`（`getLoopbackAddress()` 常为 `::1`，与 URL 中的 `127.0.0.1` 在部分 ROM 不通）；传输选 Streamable HTTP 不要 SSE；默认免鉴权（可开 `requireAuth` + 持久 Token）；`initialize` 后会话即就绪；HTTP keep-alive 多请求 + `/mcp/` 路径归一；GET `/mcp`→405 合法。
- **算法调参看 DEBUG 帧轨迹**：`AlgorithmRuntimeTrace` 保留最近 32 帧（无像素）；AppLog 标签 `algo.frame` / `algo.det` / `algo.track` / `algo.decision`。须开发者开启且 `logLevel=DEBUG`；状态变化或约每 12 帧写一条。诊断导出含 pipeline + 最近帧。INFO 下仍只有会话 start/stop。
- **编程版八荣八耻已装入 CLAUDE**：根 `CLAUDE.md` 与用户级 `~/.claude/CLAUDE.md` 的 `Core Philosophy`；第 7 条原文「为菜」= 诚实无知、不装懂。硬约束全文仍以 `CLAUDE.md` 为准。
- **host 构建脚本无 +x**：Windows 检出常使 `tools/vision/*.sh` 为 `100644`；CI 上 `python subprocess([str(build_host.sh)])` 会 `PermissionError`。应 `bash script.sh` / `powershell -File script.ps1`，统一走 `tools/vision/host_build.py`。
- **Serena / 多 KLS 内存（已固化）**：Kotlin LS 默认大堆；多 Claude/Serena + VS Code fwcd + JetBrains 会叠堆 → `initialize` 超时 / 工具不进会话 / Gradle daemon 被 stop。永久配置：`.serena/project.yml` 与 `~/.serena/serena_config.yml` 的 `ls_specific_settings.kotlin.jvm_options=-Xmx768m`；项目默认仅 `languages: [kotlin]`；`.vscode` 关 `kotlin.languageServer.enabled`（fwcd）。救急：`tools/dev/repair_serena.ps1 -ClearCache`（可选 `-AlsoStopFwcd`）。工具仍不可用时回退 Read/Grep，勿阻塞交付。

## 2026-07-22

- **内置算法首版号是 0.1.0**：runtime `builtin.hzzs.base` + Catalog `builtin-hzzs-base-0.1.0`；不要再写 `2.0.0`（那是误标）。与外装包 `official-bamboo-baseline` 0.1.0 语义独立。
- **诊断时间用本地时区+偏移**：`DiagnosticsExporter` / 算法「最近检查」用 `yyyy-MM-dd HH:mm:ss.SSSXXX`，禁止再标假 `Z`。
- **拉不到新算法的两道门**：① 远端 `release-index` 上 `algorithms/{channel}.json` 尚不存在（双源 404）→ 检查失败但内置/捆绑可用；② 公钥列表被清空 → 即使有目录也拒绝外装下载。修体验≠已发布包。
- **APK 捆绑 vs 远端验签**：`assets/algorithms/<id>/` 经 `BundledAlgorithmInstaller` 幂等预装，**不经** Ed25519；远端 `.hzzsalg` 仍须信任锚。设置「算法库」可切换内置/捆绑/远端；「检测参数」单独页。
- **analysisRunning 必须双写**：`VisionRuntimeController` 须调 `AlgorithmCatalogController.setAnalysisRunning`（其内再调 Activation），只写 Activation 会导致分析中下载半热激活。
- **应用内悬浮窗开关 ≠ 系统权限**：`OverlayConfig.enabled` 只控制是否尝试加窗；真正门闩是 `Settings.canDrawOverlays`。缺权限时帧循环可正常、芯片「无悬浮窗」，须引导用户进系统设置。
- **信任锚 fail-closed**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 列表为空时外装「官方」算法包应被拒绝；内置与 APK 捆绑声明式包仍可用。
- **算法发布无 tag**：包与目录都在 `release-index`（`algorithms/packages/` + `algorithms/{channel}.json`）；客户端不读 `releases/download`。用户说「上传到 GitHub 就能检测」= 更新该分支目录/包，不是 push main，也不是发 alg tag。
- **AI 可代发（能力保留，现阶段可搁置）**：流程写在根 `CLAUDE.md`「算法包网络更新」。用户**再次明确**要网络发布时才 `--execute`；缺 token/私钥则停下。2026-07-24 用户反馈签名发布偏重，**日常开发先不走 release-index**，以捆绑 `assets/algorithms` + 清 installed/数据为主；设置「检查算法」404 属预期。
- **算法版本与通道**：包 `manifest.version` 用 semver，**首版 `0.1.0`**（补丁 +0.0.1 / 次要 +0.1.0 / 主要 +1.0.0）；**门禁全过才 bump**；`beta` vs `stable` 用户自选，未验证不上 stable。多包发布时通道跟各包 `manifest.channel`。
- **清空数据 ≠ 网络更新**：清存储只重种 bundled；`release-index` 404 时检查算法永远失败。正式更新靠目录+包体，不靠卸 App。
- **捆绑按 version 升级**：assets 更高 `versionCode` 且本地为 bundled/无 origin 时覆盖；网络包装 `originTag=network` 不被冲。开发：升 `manifest.version` → 同步 assets → 装 APK 即可，不必清数据。仅 push GitHub 源文件手机不会变，除非 CI 发到 `release-index` 或用户装了含新 assets 的 APK。
- **默认赛季单一真相**：只改 `AppConfig.DEFAULT_SELECTED_SCENE`；文档禁止写死赛季中文名/枚举。
- **提交隔离**：UI/动效、算法网络、本机构建、IDE 脚本分提交；合 main 前可用 stash 隔开无关 WIP。
- **日常开发分支**：默认在 `main` 直接迭代（用户偏好）；除非明确要求再开 feature 分支。
- **文档同步**：硬约束/对外能力变更时同一任务更新 `CLAUDE.md` 与 `README.md`；**禁止改动 Star History**；也不得无故删除徽章、免责、版本表、构建/签名、MCP 边界、仓库链与许可证等关键信息。
- **本机测试**：全量 unit test 可能 OOM；优先相关单测 + compile，再视情况 assemble。
- **构建慢/IC 损坏**：VS Code+Claude+Serena+Kotlin LS 常驻且空闲内存不足时易换页；症状为 `shrunk-classpath-snapshot.bin` FileNotFound、IC 回退全量、daemon stop command。先 `gradlew --stop` + `tools/dev/repair_gradle_kotlin_cache.ps1`；Hilt 已迁 KSP（勿回 legacy-kapt）；日常 `hzzs.native.abis=arm64-v8a` + `CMAKE_BUILD_PARALLEL_LEVEL=2`。
- **配置缓存被用户级关掉**：`%GRADLE_USER_HOME%\gradle.properties` 里的 `org.gradle.configuration-cache=false` 会覆盖项目 true，导致「几乎全 UP-TO-DATE 的 installDebug」仍要数分钟（冷配置+装机）。应删该行；仓库 wrapper 默认 `-D` 强制开回。
- **Motion**：`animationScale`/`reduceMotion`/系统 animator 经 `HzzsMotionPolicy` 统一消费；禁止用动画倍率当业务超时。
- **几何**：动作与 Tracker 只读 `Detection.bounds`；`displayContour` 仅 HUD。
- **截图后端 API 门闩**：`ACCESSIBILITY` 仅 API 30+；Android 10 上强制/选择无障碍须经 `resolveEffectiveCaptureBackend` fail-soft 回退 MediaProjection/用户主配置，避免启动即 Failed。诊断字段 `capture.requested/effective/fallbackReason` 可对账。
- **targetSdk 34+ FGS type**：`startForeground` 必须带 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` / `SPECIAL_USE`，Manifest 声明不够。
- **MCP/导入 harden**：外部 JSON 相对 baseline 不得静默开自动操作、自提 `FULL_ACCESS`、升权截图后端；用 `hardenedForExternalIngest`。
- **自动操作 fail-closed 须 abort 在飞动作**：会话 arm 已移除；配置变更/停分析时调用 `cancelActions()`（`actionJob.cancel`），规划路径仍重检 enabled + 免责声明版本。
- **analysisRunning 与帧循环对称**：`runLoop` 异常 finally 也要 `setAnalysisRunning(false)`，否则算法切换永久 pending。
- **JNI 赛季闸门须跟 `kSceneCount`**：引擎/宿主/Kotlin 已支持海盐（scene=2）时，`jni_bridge` 若仍 `scene > 1` 会真机 `invalid scene` 连败；扩赛季时同步 JNI、CMake、`run_native_sanitizers.sh` 源列表与 native 边界测。
