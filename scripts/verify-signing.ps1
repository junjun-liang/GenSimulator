param(
    [Parameter(Mandatory=$true)][string]$GenCardsApk,
    [Parameter(Mandatory=$true)][string]$BridgeApk
)

$ErrorActionPreference = "Stop"
$apksigner = if ($env:APKSIGNER_PATH) { $env:APKSIGNER_PATH } elseif ($env:APKSIGNER) { $env:APKSIGNER } else { "apksigner" }

function Get-CertDigest([string]$Apk) {
    $output = & $apksigner verify --print-certs $Apk
    $line = $output | Select-String "Signer #1 certificate SHA-256 digest:" | Select-Object -First 1
    if (-not $line) { throw "Unable to read signer SHA-256 digest from $Apk" }
    return (($line.Line -split ": ", 2)[1]).Trim()
}

$genCardsDigest = Get-CertDigest $GenCardsApk
$bridgeDigest = Get-CertDigest $BridgeApk
if ($genCardsDigest -eq $bridgeDigest) {
    Write-Output "SIGNATURE_MATCH"
    exit 0
}
Write-Output "SIGNATURE_MISMATCH"
exit 1
