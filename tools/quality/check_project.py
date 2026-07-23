#!/usr/bin/env python3
"""Single-module architecture, Android safety, MCP and native invariants."""
from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ERRORS: list[str] = []
CHECKS: list[str] = []
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def check(value: bool, name: str, detail: str) -> None:
    (CHECKS if value else ERRORS).append(name if value else f"{name}: {detail}")


def read(path: str) -> str:
    target = ROOT / path
    check(target.exists(), f"file:{path}", "missing")
    return target.read_text(encoding="utf-8") if target.exists() else ""

settings = read("settings.gradle.kts")
modules = re.findall(r'include\("(:[^"]+)"\)', settings)
check(modules == [":app"], "architecture:single-app-module", f"declared modules: {modules}")
for legacy in ("core", "domain", "data", "feature", "service", "native"):
    legacy_root = ROOT / legacy
    legacy_files = list(legacy_root.rglob("*")) if legacy_root.exists() else []
    check(
        not any(path.is_file() for path in legacy_files),
        f"architecture:no-root-{legacy}",
        "legacy source module remains",
    )

app_build = read("app/build.gradle.kts")
for token in ("minSdk = 24", "compileSdk = 37", "externalNativeBuild"):
    check(token in app_build, f"gradle:{token}", "expected build configuration missing")
check("project(\"" not in app_build, "gradle:no-project-dependencies", "single module still depends on project modules")

for source in (ROOT / "app/src/main/java").rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    relative = source.relative_to(ROOT)
    if "LazyColumn(" in text:
        check("import androidx.compose.foundation.lazy.LazyColumn" in text, f"compose:{relative}:lazy-import", "missing import")
    check("androidx.hilt.navigation.compose.hiltViewModel" not in text, f"compose:{relative}:hilt-import", "deprecated import")
    check("top.azek431.hzzs.feature.about.R" not in text, f"resources:{relative}:single-R", "old module R reference")

manifest_root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
application = manifest_root.find("application")
check(application is not None, "manifest:application", "missing application")
if application is not None:
    check(application.attrib.get(ANDROID_NS + "usesCleartextTraffic") == "false", "manifest:https-only", "cleartext enabled")
    exported = []
    for tag in ("activity", "service", "receiver", "provider"):
        for node in application.findall(tag):
            if node.attrib.get(ANDROID_NS + "exported") == "true":
                exported.append(node.attrib.get(ANDROID_NS + "name", ""))
    allowed_exported = {".MainActivity", "rikka.shizuku.ShizukuProvider"}
    check(
        set(exported) <= allowed_exported and ".MainActivity" in exported,
        "manifest:minimal-exported-surface",
        f"exported={exported}",
    )
manifest_text = read("app/src/main/AndroidManifest.xml")
for token in (
    "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "FOREGROUND_SERVICE_SPECIAL_USE",
    'foregroundServiceType="specialUse"',
    "PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
    "McpForegroundService",
    "BIND_ACCESSIBILITY_SERVICE",
):
    check(token in manifest_text, f"manifest:{token}", "missing")

models = read("app/src/main/java/top/azek431/hzzs/core/model/AppModels.kt")
for token in ("minSdk",):
    pass
for token in ("ASK_EVERY_TIME", "FULL_ACCESS", "OverlayStyle", "PlayerReferenceMode", "disabledObstacles"):
    check(token in models, f"model:{token}", "configuration model missing")

settings_repo = read("app/src/main/java/top/azek431/hzzs/core/preferences/SettingsRepository.kt")
for token in (
    "preview.value = null",
    "disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION",
    ".intersect(AutomationConfig.DEFAULT_ALLOWED_PACKAGES)",
    "MAX_CONFIG_BYTES",
):
    check(token in settings_repo, f"settings:{token}", "safety invariant missing")

settings_ui = read("app/src/main/java/top/azek431/hzzs/feature/settings/SettingsScreen.kt")
settings_ui_dir = ROOT / "app/src/main/java/top/azek431/hzzs/feature/settings"
settings_ui_all = settings_ui
if settings_ui_dir.is_dir():
    for path in sorted(settings_ui_dir.rglob("*.kt")):
        settings_ui_all += "\n" + path.read_text(encoding="utf-8")
for token in ("DisposableEffect", "clearPreviewSilently", "SettingsExitCoordinator", "主题已临时预览", "请等待 ${remaining}s"):
    check(token in settings_ui_all, f"settings-ui:{token}", "preview/risk UI missing")
check(
    "discardSilently" not in settings_ui_all,
    "settings-ui:no-silent-draft-discard",
    "settings dispose must not silently discard drafts",
)

onboarding = read("app/src/main/java/top/azek431/hzzs/feature/onboarding/OnboardingScreen.kt")
for token in ("onboardingPageMetas", "acceptedDisclaimerVersion", "enabled = false", "onboarding_risk_wait"):
    check(token in onboarding, f"onboarding:{token}", "first-run invariant missing")
# 倒计时文案已资源化；源码不得再硬编码「请等待 ${remaining}s」绕过 stringResource。
check(
    "请等待 ${remaining}s" not in onboarding,
    "onboarding:no-hardcoded-wait",
    "onboarding risk wait must use stringResource",
)

mcp_dir = ROOT / "app/src/main/java/top/azek431/hzzs/mcp"
mcp = ""
if mcp_dir.is_dir():
    for path in sorted(mcp_dir.rglob("*.kt")):
        mcp += "\n" + path.read_text(encoding="utf-8")
else:
    mcp = read("app/src/main/java/top/azek431/hzzs/mcp/McpService.kt")
for token in (
    "InetAddress.getLoopbackAddress()",
    "Authorization",
    "ASK_EVERY_TIME",
    "requestApproval",
    "FULL_ACCESS",
    "navigate",
    "MAX_BODY_BYTES",
    "isAllowedLoopbackOrigin",
    "MessageDigest.isEqual",
    "settings.snapshot().mcp.permissionLevel",
    "list_debug_frames",
    "clear_debug_frames",
    "Mcp-Session-Id",
    "notifications/initialized",
    "TRUSTED_SESSION",
    "rejectPendingApproval",
    "MAX_CONCURRENT_CONNECTIONS",
    "additionalProperties",
):
    check(token in mcp, f"mcp:{token}", "MCP control/safety invariant missing")
check("0.0.0.0" not in mcp, "mcp:no-lan-listener", "MCP must remain loopback-only")
check(
    'put("additionalProperties", true)' not in mcp
    and "put('additionalProperties', true)" not in mcp,
    "mcp:no-open-tool-schema",
    "tool inputSchema must not use additionalProperties:true",
)

capture = read("app/src/main/java/top/azek431/hzzs/service/capture/CaptureSources.kt")
check(
    "import android.os.Process\n" not in capture,
    "capture:android-process-alias",
    "android.os.Process shadows java.lang.Process used by ProcessBuilder",
)
check(
    "private suspend fun waitForExit(process: java.lang.Process" in capture
    and "private fun java.lang.Process.destroyCompat()" in capture,
    "capture:java-process-contract",
    "root command helpers must use java.lang.Process explicitly",
)
auto = capture.split("class AutoFrameSource", 1)[1].split("class MediaProjectionFrameSource", 1)[0] if "class AutoFrameSource" in capture else ""
check("root" not in auto.lower() and "shizuku" not in auto.lower(), "capture:auto-low-permission", "AUTO escalates")
for token in (
    "MAX_FRAME_DIMENSION = 4_096",
    "MAX_FRAME_PIXELS = 8_388_608L",
    "process.errorStream",
    "ACCESSIBILITY_CALLBACK_TIMEOUT_MS",
    "createOrResizeDisplay",
    "waitForExit(process, timeoutMs)",
    "destroyCompat()",
):
    check(token in capture, f"capture:{token}", "capture bound missing")


check(
    "ActivityResultContracts.StartActivityForResult()" in capture
    and "startActivityForResult" not in capture
    and "override fun onActivityResult" not in capture,
    "capture:activity-result-api",
    "screen-capture consent must use the Activity Result API",
)

frame_capture = read("app/src/main/java/top/azek431/hzzs/service/capture/FrameCapture.kt")
frame_test = read("app/src/test/java/top/azek431/hzzs/service/capture/FrameSequenceTest.kt")
check(
    "private val clockNanos: () -> Long" in frame_capture
    and "FrameSequencer(clockNanos =" in frame_test,
    "capture:jvm-safe-frame-clock",
    "local JVM tests must not call Android SystemClock directly",
)

theme_test = read("app/src/test/java/top/azek431/hzzs/core/theme/ThemePackageTest.kt")
check(
    'sanitized.has("script")' in theme_test
    and 'reencoded.contains("script")' not in theme_test,
    "theme:json-key-sanitization-test",
    "theme security tests must inspect JSON keys rather than substrings",
)

for context_file in (
    "app/src/main/java/top/azek431/hzzs/core/preferences/SettingsRepository.kt",
    "app/src/main/java/top/azek431/hzzs/core/update/UpdateModels.kt",
    "app/src/main/java/top/azek431/hzzs/platform/compat/CaptureCapabilities.kt",
    "app/src/main/java/top/azek431/hzzs/service/capture/CaptureSources.kt",
    "app/src/main/java/top/azek431/hzzs/service/overlay/OverlayController.kt",
):
    context_text = read(context_file)
    check(
        "@param:ApplicationContext" in context_text
        and "\n    @ApplicationContext" not in context_text,
        f"kotlin:application-context-target:{context_file}",
        "Hilt qualifier must use an explicit Kotlin parameter target",
    )

gitignore = read(".gitignore")
check(
    ".hzzs-test-results/" in gitignore,
    "gitignore:test-results",
    "generated test results must remain outside Git",
)

check(
    "androidTestImplementation(platform(libs.compose.bom))"
    in app_build,
    "gradle:android-test-compose-bom",
    "Compose Android-test dependencies require the BOM",
)

debug_frame_recorder = read(
    "app/src/main/java/top/azek431/hzzs/"
    "data/vision/DebugFrameRecorder.kt"
)
check(
    "@ApplicationContext context: Context"
    in debug_frame_recorder,
    "kotlin:debug-recorder-context-target",
    "non-property constructor parameter uses direct qualifier",
)

runtime = read("app/src/main/java/top/azek431/hzzs/data/vision/VisionRuntimeController.kt")
for token in (
    "restart()",
    "fixedPlayerReference",
    "detectedPlayerReference",
    "disclaimerAcceptedVersion",
    "effectiveCaptureBackend",
):
    check(token in runtime, f"runtime:{token}", "runtime behavior missing")


debug_recorder = read("app/src/main/java/top/azek431/hzzs/data/vision/DebugFrameRecorder.kt")
for token in (
    "MIN_INTERVAL_NANOS",
    "MAX_FILES = 20",
    "context.filesDir",
    "pixels.copyOf()",
):
    check(token in debug_recorder, f"debug-frame:{token}", "bounded private debug capture invariant missing")

benchmark = read("app/src/main/java/top/azek431/hzzs/data/vision/NativeBenchmarkRunner.kt")
for token in ("NativeVision.analyze", "requestedIterations.coerceIn(10, 1_000)", "p95Ms"):
    check(token in benchmark, f"native-benchmark:{token}", "on-device native benchmark invariant missing")

native_boundary = read("app/src/main/java/top/azek431/hzzs/nativevision/NativeVision.kt")
for token in ("isAvailable", "loadFailureMessage", "runCatching"):
    check(token in native_boundary, f"native-loader:{token}", "native linker failure is not contained")

native_kt = read("app/src/main/java/top/azek431/hzzs/data/vision/NativeVisionEngine.kt")
for token in ("enabledKindMask", "detectPlayer", "fixedPlayerXRatio", "NativeVision.isAvailable"):
    check(token in native_kt, f"native-bridge:{token}", "native option not forwarded")

jni = read("app/src/main/cpp/jni_bridge.cpp")
engine = read("app/src/main/cpp/vision_engine.cpp")
for token in (
    "kMaximumDimension = 4096",
    "kMaximumPixels",
    "enabled_kind_mask",
    "detect_player",
    "GetPrimitiveArrayCritical",
    "CriticalIntArray",
):
    check(token in jni or token in engine, f"native:{token}", "native safety/feature missing")

for rules in ("app/src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml"):
    text = read(rules)
    check("hzzs_settings_v5.preferences_pb" in text, f"backup:{rules}:v5", "current DataStore file is not covered")
    check("hzzs_settings_v3.preferences_pb" not in text, f"backup:{rules}:no-v3", "stale DataStore backup rule remains")

for name in ("README.md", "CLAUDE.md", "app/README.md", "app/CLAUDE.md", "app/src/main/cpp/README.md", "app/src/main/cpp/CLAUDE.md"):
    check((ROOT / name).exists(), f"docs:{name}", "AI-readable documentation missing")

build_workflow = read(".github/workflows/build.yml")
for token in ("check_project.py", "run_native_sanitizers.sh", "testDebugUnitTest", "lintDebug", "assembleDebug"):
    check(token in build_workflow, f"ci:{token}", "quality gate missing")

# 宿主机 g++ 脚本必须与 CMakeLists 同源：含 legacy_main 主检测路径与对应 include。
cmake_native = read("app/src/main/cpp/CMakeLists.txt")
for host_script in ("tools/vision/run_native_sanitizers.sh", "tools/vision/build_host.sh"):
    host_text = read(host_script)
    for token in (
        "legacy_main/vision2",
        "legacy_main/vision_bamboo",
        "HzzsVisionCore.cpp",
        "BambooVisionCore.cpp",
        "BambooVisionEngine.cpp",
    ):
        check(token in host_text, f"host-native:{host_script}:{token}", "host build diverged from CMake")
    check(
        "legacy_main/vision2" in cmake_native and "legacy_main/vision_bamboo" in cmake_native,
        "host-native:cmake-legacy-main",
        "CMake missing legacy_main sources",
    )

result = {"status": "PASS" if not ERRORS else "FAIL", "checks": len(CHECKS), "errors": ERRORS}
print(json.dumps(result, ensure_ascii=False, indent=2))
if ERRORS:
    raise SystemExit(1)
