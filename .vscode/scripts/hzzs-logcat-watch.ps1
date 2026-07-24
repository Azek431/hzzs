#Requires -Version 5.1
# Long-running logcat filter. Stop via terminal trash / stop button.
# Do NOT put this into dependsOn compound tasks.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

Assert-HzzsAdbDevice | Out-Null
Write-Host 'Listening (stop via terminal stop button): HZZS + crash + Activity/Window'
Write-Host ''

# Stream to the task terminal. Continue so occasional native stderr does not kill the session.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & adb logcat -v time `
        HZZS:D `
        AndroidRuntime:E `
        System.err:E `
        ActivityManager:I `
        ActivityTaskManager:I `
        WindowManager:I `
        '*:S'
    $code = Get-HzzsNativeExitCode
}
finally {
    $ErrorActionPreference = $prevEap
}

if ($code -ne 0) {
    throw ("logcat exited (exit {0}). Device disconnected? Run adb devices." -f $code)
}
