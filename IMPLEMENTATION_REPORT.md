# Implementation Report — GenSimulator Bridge V1

## Scope

Implemented the repository-owned Android Test Bridge required to close the CREATE text ingress path without modifying GenCards:

```text
GenSimulator Desktop
  → adb startservice + adb forward tcp:39091
  → GenSimulator Android Bridge
  → exact GenCards Voice Ingress V2 AIDL
  → IGenCardsVoiceQueryIngress.submitTextCreateQuery(query)
  → GenCards ingress accepted/rejected
```

`INGRESS_ACCEPTED` remains an ingress acknowledgement only. This patch does not add Generation/Card/Widget completion callbacks, polling, screenshots, DeepSeek configuration, GenAgent configuration, or legacy numeric `sceneType` fallback.

## Modules added

### `shared-voice-ingress-contract`

Mirrored the current GenCards authoritative contract sources:

- `IGenCardsVoiceQueryIngress.aidl`
- `IGenCardsEditQueryCallback.aidl`
- `IGenAgentEditQueryCapture.aidl`
- `GenCardsVoiceIngressContract.java`
- `GenAgentEditCaptureContract.java`
- `GenCardsEntryContract.java`
- contract tests and `contract-fingerprint.sha256`

The gensimulator build keeps ABI-order verification and source fingerprint verification. Current shared metadata is:

```text
VOICE_INGRESS_CONTRACT_VERSION = 2
GENCARDS_PACKAGE = com.flyme.auto.gencards
QUERY_SERVICE_COMPONENT = com.flyme.auto.gencards.voice.VoiceQueryIngressService
PERMISSION_SUBMIT_QUERY = com.flyme.auto.gencards.permission.SUBMIT_QUERY
```

### `android-bridge`

Added a standalone Android application module:

```text
applicationId = com.flyme.auto.gensimulator.bridge
compileSdk = 36
targetSdk = 36
minSdk = 23
```

Runtime components:

```text
BridgeRuntimeService
├── LocalHttpBridgeServer
└── GenCardsBinderClient
```

The service is an exported **debug/test ordinary service** started explicitly through ADB. This intentionally avoids inventing a production foreground-service type/notification contract. The HTTP server binds only to `127.0.0.1:39091`.

## HTTP protocol

Preserved the Desktop protocol:

```text
GET  /health
POST /v1/create
protocolVersion = 1
```

`/health` keeps the existing core fields and adds optional diagnostics:

```text
sameSigner
binderState
lastErrorCode
```

`/v1/create` validates protocol version, requestId, non-blank query and the 60,000-character limit. It never truncates the query and echoes the original requestId.

## Binder behavior

`GenCardsBinderClient` binds with the exact shared component constants and production CREATE only calls:

```text
submitTextCreateQuery(query)
```

No runtime code calls `submitVoiceQuery(query, sceneType)`.

CREATE never starts a GenCards Activity (no `VoiceEntry` / Guide pre-launch), matching the genagent Host integration (`docs/host_integration.md`). GenCards opens its own Generation window after admitting the request; its `VOICE + CREATE_CARD` external entry is a compatibility no-op, and a pre-launch could be blocked by background-activity-launch policy and gate the Binder call. The Bridge therefore does not request `OPEN_EXTERNAL_ENTRY`. Binder connect wait is bounded to 5 s; each CREATE is submitted exactly once and never replayed.

Handled Binder states:

```text
DISCONNECTED
CONNECTING
CONNECTED
GENCARDS_NOT_INSTALLED
PERMISSION_DENIED
BINDER_DIED
BIND_FAILED
NULL_BINDING
```

Handled lifecycle/error paths:

- `onServiceConnected`
- `onServiceDisconnected`
- `onBindingDied`
- `onNullBinding`
- `bindService == false`
- `SecurityException`
- `RemoteException`
- `DeadObjectException`
- `IBinder.DeathRecipient`
- delayed rebind after transient binder loss

## Signature behavior

The Bridge requests GenCards' existing signature permission:

```text
com.flyme.auto.gencards.permission.SUBMIT_QUERY
```

Runtime health exposes `PackageManager.checkSignatures(...)` as `sameSigner`; this is diagnostics only and does not bypass Android permission enforcement.

Bridge debug signing defaults to normal Android debug signing. A trusted signing configuration can be supplied through:

```text
GENCARDS_TRUSTED_KEYSTORE
GENCARDS_TRUSTED_KEY_ALIAS
GENCARDS_TRUSTED_STORE_PASSWORD
GENCARDS_TRUSTED_KEY_PASSWORD
```

No keystore/private key/password is included in the repository.

## Desktop changes

`SimulatorConfig` now owns repo defaults:

```text
bridgePackage = com.flyme.auto.gensimulator.bridge
bridgeServiceComponent = com.flyme.auto.gensimulator.bridge/.BridgeRuntimeService
expectedProtocolVersion = 1
expectedContractVersion = 2
```

`AdbClient` now exposes `startService(...)` and `ProcessAdbClient` executes an argument-safe:

```text
adb -s <serial> shell am startservice -n <component>
```

Preflight is now:

```text
adb ready
→ device ready
→ GenCards installed
→ Bridge installed
→ start BridgeRuntimeService
→ adb forward
→ bounded /health retry
→ protocol V1
→ contract V2
→ GenCards installed
→ same signer not false
→ Binder connected
→ READY
```

New explicit errors include:

```text
BRIDGE_START_FAILED
BRIDGE_HEALTH_TIMEOUT
```

The existing UI was not redesigned; it now additionally displays protocol, signature and Binder state diagnostics.

## Scripts

Added/updated:

```text
scripts/install-bridge.sh
scripts/install-bridge.ps1
scripts/verify-signing.sh
scripts/verify-signing.ps1
scripts/smoke-test.sh
scripts/smoke-test.ps1
```

Smoke validation is intentionally limited to ingress acceptance and does not claim Card/Widget generation completion.

## Verification actually executed

| Verification | Result |
|---|---|
| Mirrored contract source SHA-256/fingerprint recomputation | **PASS** — `0da9f1330192827f9b560c70bab42ea5c94bc3c205e4c29834b37a7afdeea193` matches checked-in fingerprint |
| Pure Kotlin compile: `BridgeProtocol` + `BinderConnectionState` | **PASS** |
| Pure Kotlin compile: `LocalHttpBridgeServer` + protocol | **PASS** |
| Kotlin compile of `DevicePreflight`/config/ADB contracts using minimal dependency stubs | **PASS** |
| Kotlin compile of Android Bridge source using minimal Android/AIDL compile stubs | **PASS** |
| Local runtime HTTP test with fake ingress (`/health` + `/v1/create`) | **PASS** |
| `bash -n scripts/install-bridge.sh scripts/verify-signing.sh scripts/smoke-test.sh` | **PASS** |
| grep/static check: runtime legacy `submitVoiceQuery` usage | **PASS** — no runtime call; only README/authoritative AIDL mentions it |
| grep/static check: HTTP bind address | **PASS** — `127.0.0.1` only |

Local runtime HTTP test returned:

```json
{"bridge":"ready","genCardsInstalled":true,"binderConnected":true,"contractVersion":2,"protocolVersion":1,"sameSigner":true,"binderState":"CONNECTED","lastErrorCode":null}
```

and:

```json
{"requestId":"runtime-1","accepted":true,"code":"INGRESS_ACCEPTED"}
```

## Gradle verification attempted but blocked by environment

The following required commands were actually attempted:

```bash
./gradlew desktopTest
./gradlew :shared-voice-ingress-contract:check
./gradlew :android-bridge:testDebugUnitTest
./gradlew :android-bridge:assembleDebug
```

All four stopped before project configuration because the Gradle wrapper distribution is not cached and this execution environment cannot resolve `services.gradle.org`:

```text
java.net.UnknownHostException: services.gradle.org
```

Therefore this report does **not** claim Gradle tests or APK assembly passed.

## Device verification not executed

No Android SDK/ADB device is available in this execution environment, so these were not executed here:

```text
Bridge APK install
same-signer check against a real GenCards APK
adb startservice on target device
real Binder bind to VoiceQueryIngressService
real /v1/create → GenCards ingress acceptance
```

Run on a networked Android development machine:

```bash
./gradlew desktopTest
./gradlew :shared-voice-ingress-contract:check
./gradlew :android-bridge:testDebugUnitTest
./gradlew :android-bridge:assembleDebug
./scripts/install-bridge.sh
./scripts/smoke-test.sh
```

## Known limitation / deferred scope

This patch deliberately has **no Generation completion callback**. `INGRESS_ACCEPTED` means only that `submitTextCreateQuery(query)` returned `true`; it must not be interpreted as Card or Widget generation success.
