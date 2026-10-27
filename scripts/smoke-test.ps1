param(
    [string]$Query = "打开主驾座椅加热"
)

$ErrorActionPreference = "Stop"
$adb = if ($env:ADB_PATH) { $env:ADB_PATH } else { "adb" }
$genCardsPackage = if ($env:GENCARDS_PACKAGE) { $env:GENCARDS_PACKAGE } else { "com.flyme.auto.gencards" }
$bridgePackage = if ($env:GENCARDS_BRIDGE_PACKAGE) { $env:GENCARDS_BRIDGE_PACKAGE } else { "com.flyme.auto.gensimulator.bridge" }
$bridgeBootstrapActivity = if ($env:GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT) { $env:GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT } else { "com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity" }
$port = if ($env:GENCARDS_BRIDGE_PORT) { [int]$env:GENCARDS_BRIDGE_PORT } else { 39091 }
$expectedProtocol = if ($env:GENCARDS_PROTOCOL_VERSION) { [int]$env:GENCARDS_PROTOCOL_VERSION } else { 1 }
$expectedContract = if ($env:GENCARDS_CONTRACT_VERSION) { [int]$env:GENCARDS_CONTRACT_VERSION } else { 2 }
$serial = $env:ANDROID_SERIAL

if (-not $serial) {
    $devices = @(& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -ne 1) { throw "Expected exactly one connected device or set ANDROID_SERIAL." }
    $serial = $devices[0]
}

if (-not (& $adb -s $serial shell pm path $genCardsPackage | Select-String "^package:")) { throw "GenCards is not installed" }
if (-not (& $adb -s $serial shell pm path $bridgePackage | Select-String "^package:")) { throw "GenSimulator Bridge is not installed" }

$userId = (& $adb -s $serial shell am get-current-user).Trim()
if ($userId -notmatch '^\d+$') { throw "Unable to determine active Android user" }
$startOutput = (& $adb -s $serial shell am start --user $userId -n $bridgeBootstrapActivity | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $startOutput -match '(?m)^Error:') { throw "Bridge bootstrap activity failed: $startOutput" }
& $adb -s $serial forward "tcp:$port" "tcp:39091" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "adb forward failed" }

$health = $null
for ($i = 0; $i -lt 15 -and -not $health; $i++) {
    try { $health = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/health" }
    catch { Start-Sleep -Milliseconds 200 }
}
if (-not $health) { throw "Bridge health timeout" }
$health | ConvertTo-Json -Depth 5
if ($health.bridge -ne "ready") { throw "Bridge not ready" }
if (-not $health.genCardsInstalled) { throw "GenCards missing" }
if ($health.sameSigner -eq $false) { throw "SIGNATURE_MISMATCH" }
if (-not $health.binderConnected) { throw "Binder not connected: $($health.binderState) $($health.lastErrorCode)" }
if ($health.protocolVersion -ne $expectedProtocol) { throw "Protocol mismatch" }
if ($health.contractVersion -ne $expectedContract) { throw "Contract mismatch" }
Write-Output "HEALTH_ASSERT_PASS"

$requestId = [guid]::NewGuid().ToString()
$body = @{ protocolVersion = 1; requestId = $requestId; query = $Query } | ConvertTo-Json -Compress
$response = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/v1/create" -ContentType "application/json" -Body $body
$response | ConvertTo-Json -Depth 5
if ($response.requestId -ne $requestId) { throw "REQUEST_ID_MISMATCH" }
if (-not $response.accepted -or $response.code -ne "INGRESS_ACCEPTED") { throw "CREATE_ASSERT_FAILED" }
Write-Output "CREATE_ASSERT_PASS"
