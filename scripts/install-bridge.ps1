$ErrorActionPreference = "Stop"
$adb = if ($env:ADB_PATH) { $env:ADB_PATH } else { "adb" }
$serial = $env:ANDROID_SERIAL
$bootstrapActivity = if ($env:GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT) { $env:GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT } else { "com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity" }
$root = Resolve-Path (Join-Path $PSScriptRoot "..")

if (-not $serial) {
    $devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -ne 1) { throw "Expected exactly one connected device or set ANDROID_SERIAL." }
    $serial = $devices[0]
}

Push-Location $root
try {
    & .\gradlew.bat :android-bridge:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Bridge build failed" }
    $apk = Join-Path $root "android-bridge\build\outputs\apk\debug\android-bridge-debug.apk"
    if (-not (Test-Path $apk)) { throw "Bridge APK not found: $apk" }
    $userId = (& $adb -s $serial shell am get-current-user).Trim()
    if ($userId -notmatch '^\d+$') { throw "Unable to determine active Android user" }
    & $adb -s $serial install -r --user $userId $apk
    if ($LASTEXITCODE -ne 0) { throw "Bridge install failed" }
    $startOutput = (& $adb -s $serial shell am start --user $userId -n $bootstrapActivity | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $startOutput -match '(?m)^Error:') { throw "Bridge bootstrap activity failed: $startOutput" }
    Write-Output "BRIDGE_INSTALLED serial=$serial apk=$apk"
} finally {
    Pop-Location
}
