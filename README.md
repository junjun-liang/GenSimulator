# GenCards Voice/Text Simulator

跨平台研发测试工具：Desktop 输入文本，经 ADB 转发到仓库内置 Android Bridge，再通过 GenCards Voice Ingress V2 Binder 调用 `submitTextCreateQuery(query)`。

> `INGRESS_ACCEPTED` 只表示 **GenCards ingress 已将请求入队**，不表示生成页已打开，也不表示 DeepSeek / GenAgent / A2UI / Card / Widget 已完成。

## Architecture

```text
macOS / Windows Desktop
        │ POST /v1/create
        │ adb forward tcp:39091 tcp:39091
        ▼
GenSimulator Android Bridge
        │ 127.0.0.1:39091 only
        ▼
GenCardsBinderClient
        │ bounded bind + exact shared Voice Ingress V2 AIDL
        ▼
IGenCardsVoiceQueryIngress
        │ submitTextCreateQuery(query)
        ▼
GenCards VoiceQueryIngressService
        ▼
VoiceCreateCoordinator → account admission → Generation session
        ▼
GenCards Generation window (GenCards 自行打开)
```

Desktop 不直接操作 Binder；Bridge 与 genagent Host 集成方式一致，**只**调用 `submitTextCreateQuery(query)`，不启动任何 GenCards Activity（包括 `VoiceEntry` / Guide）。生成页由 GenCards 在请求准入后自行打开。Bridge 不调用 legacy `submitVoiceQuery(query, sceneType)`，也不实现 Generation/Card/Widget completion callback。

> GenCards 已将 `VOICE + CREATE_CARD` 外部入口降级为兼容 no-op；先拉起 `VoiceEntry` 不会打开生成页，反而可能因 background-activity-launch 限制失败并阻断 CREATE。

## Modules

```text
.
├── src/desktopMain/                 # Compose Desktop simulator
├── shared-voice-ingress-contract/   # mirrored authoritative GenCards V2 Binder ABI
└── android-bridge/                  # repo-owned localhost HTTP -> Binder bridge APK
```

`shared-voice-ingress-contract` 保留 GenCards 的 AIDL、Java constants 和 `contract-fingerprint.sha256`。`check` 会执行 ABI 与 fingerprint fail-fast 校验。

## Requirements

- JDK 17+
- Android SDK Platform 36（构建 Bridge）
- Android Platform Tools / `adb`
- 目标设备已安装 GenCards
- Bridge 与目标 GenCards **必须处于同一 trusted signing domain**，否则 GenCards 的 `com.flyme.auto.gencards.permission.SUBMIT_QUERY` signature permission 会拒绝 Binder bind
- 若 GenCards 在 Bridge 之后安装/重装，需重新安装 Bridge 以重新授予 signature permission
- GenCards 已登录账号，且当前没有进行中（含后台）的生成会话；否则 CREATE 会被拒绝（`INGRESS_REJECTED`）或停在账号准入流程

## Build

Desktop tests:

```bash
./gradlew desktopTest
```

Shared contract verification:

```bash
./gradlew :shared-voice-ingress-contract:check
```

Android Bridge:

```bash
./gradlew :android-bridge:testDebugUnitTest
./gradlew :android-bridge:assembleDebug
```

Bridge APK：

```text
android-bridge/build/outputs/apk/debug/android-bridge-debug.apk
```

## Signing

默认 debug build 使用标准 Android debug signing。若目标 GenCards 不是由同一 debug certificate 签名，使用以下环境变量配置 Bridge signing：

```text
GENCARDS_TRUSTED_KEYSTORE
GENCARDS_TRUSTED_KEY_ALIAS
GENCARDS_TRUSTED_STORE_PASSWORD
GENCARDS_TRUSTED_KEY_PASSWORD
```

不要把 keystore、private key 或 password 提交到仓库。

Host certificate verification：

```bash
./scripts/verify-signing.sh path/to/gencards.apk path/to/android-bridge-debug.apk
```

成功输出：

```text
SIGNATURE_MATCH
```

## Install Bridge

macOS/Linux:

```bash
./scripts/install-bridge.sh
```

Windows:

```powershell
.\scripts\install-bridge.ps1
```

多设备时必须设置 `ANDROID_SERIAL`。脚本不会随机选择设备。

Bridge 使用 exported 的可见 Debug bootstrap Activity 启动前台服务：

```bash
adb shell am start --user "$(adb shell am get-current-user)" \
  -n com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity
```

Activity 在前台期间启动 `BridgeRuntimeService`，后者通过可见通知保持前台运行。这避免了 API 26+ 设备拒绝从 stopped/background 状态冷启动普通 Service 的限制。该组件只绑定 `127.0.0.1:39091`，仍需 ADB port forward 才可访问。

## Desktop runtime configuration

Bridge 已成为仓库内置组件，因此 package/contract 都有默认值；环境变量仅用于 override/诊断。

| Environment | Default | Description |
|---|---|---|
| `GENCARDS_PACKAGE` | `com.flyme.auto.gencards` | GenCards package |
| `GENCARDS_BRIDGE_PACKAGE` | `com.flyme.auto.gensimulator.bridge` | Bridge package |
| `GENCARDS_BRIDGE_BOOTSTRAP_ACTIVITY_COMPONENT` | `com.flyme.auto.gensimulator.bridge/.BridgeBootstrapActivity` | Bridge bootstrap activity component |
| `GENCARDS_BRIDGE_PORT` | `39091` | Desktop localhost port; device Bridge remains fixed at `39091` |
| `GENCARDS_PROTOCOL_VERSION` | `1` | Desktop/Bridge debug HTTP protocol |
| `GENCARDS_CONTRACT_VERSION` | `2` | Shared Voice Ingress contract |
| `GENCARDS_MAX_QUERY_CHARS` | `60000` | Query validation limit |
| `ADB_PATH` | auto detect | Explicit adb executable |

在多用户车机上，模拟器会通过 `am get-current-user` 解析活动用户，并将该用户显式传给 GenCards/Bridge 的包检测及 Bridge bootstrap 启动命令；不再假定应用安装在用户 0。

Run Desktop：

```bash
./gradlew run
```

## Device Preflight

每次连接检查/发送执行：

```text
adb available
 ↓
selected device == device
 ↓
GenCards installed
 ↓
repo-owned Bridge installed
 ↓
adb forward tcp:39091 tcp:39091
 ↓
GET /health
 ├─ already reachable: reuse Bridge without opening an Activity
 └─ unreachable: adb am start BridgeBootstrapActivity
                  → foreground BridgeRuntimeService
                  → GET /health (bounded retry)
 ↓
 ↓
protocolVersion == 1
 ↓
contractVersion == 2
 ↓
genCardsInstalled == true
 ↓
sameSigner != false
 ↓
binderConnected == true
 ↓
READY
```

Bridge startup 与 HTTP server ready 之间允许有限重试；已运行的 Bridge 不会再次打开可见的 bootstrap Activity。

## Bridge protocol

### `GET /health`

```json
{
  "bridge": "ready",
  "genCardsInstalled": true,
  "binderConnected": true,
  "contractVersion": 2,
  "protocolVersion": 1,
  "sameSigner": true,
  "binderState": "CONNECTED",
  "lastErrorCode": null
}
```

新增 diagnostics 字段是 optional-compatible；旧核心字段语义不变。

### `POST /v1/create`

Request:

```json
{
  "protocolVersion": 1,
  "requestId": "UUID",
  "query": "打开主驾座椅加热"
}
```

Success:

```json
{
  "requestId": "UUID",
  "accepted": true,
  "code": "INGRESS_ACCEPTED"
}
```

Bridge 会原样 echo `requestId`，query 只做空值/长度校验，不截断；真正 CREATE Binder 方法只调用 `submitTextCreateQuery(query)`，每个请求只调用一次、失败不自动重放（GenCards 无幂等 ID）。

`INGRESS_REJECTED` 表示 GenCards 返回 `false`，常见原因是已有待处理请求或进行中/后台的生成会话。

主要错误码：

```text
BRIDGE_NOT_INSTALLED
BRIDGE_START_FAILED
BRIDGE_HEALTH_TIMEOUT
PROTOCOL_VERSION_MISMATCH
CONTRACT_VERSION_MISMATCH
GENCARDS_NOT_INSTALLED
GENCARDS_PERMISSION_DENIED
GENCARDS_BIND_FAILED
GENCARDS_BINDER_DIED
GENCARDS_CONNECT_TIMEOUT
INGRESS_REJECTED
QUERY_EMPTY
QUERY_TOO_LARGE
```

## Binder lifecycle

Bridge 显式处理：

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

并处理 `onServiceConnected`、`onServiceDisconnected`、`onBindingDied`、`onNullBinding`、`SecurityException`、`RemoteException`、`DeadObjectException` 与 `IBinder.DeathRecipient`。Binder death 后 Bridge 会进行有界延迟重新 bind，不要求重启 Desktop。

## Smoke test

先安装 GenCards 与 Bridge，然后：

```bash
./scripts/smoke-test.sh
```

Windows:

```powershell
.\scripts\smoke-test.ps1
```

Smoke test 验证：package、Bridge start、ADB forward、`/health`、protocol/contract/signature/Binder 状态，以及 `/v1/create` 的 requestId echo + `INGRESS_ACCEPTED`。

它**不验证** Card rendered、Widget rendered、DeepSeek completion 或 A2UI completion。

## Current boundary

当前 CREATE 主链是：

```text
Desktop
→ repo-owned Android Bridge
→ bounded Binder connect (5s)
→ exact Voice Ingress V2 AIDL
→ submitTextCreateQuery
→ GenCards ingress accepted
→ GenCards 自行打开 Generation window
```

设备上确认生成页是否打开：

```bash
adb logcat -c
# 发送一次 CREATE 后
adb logcat -d | grep -E "GenSimulatorBridge|VoiceCreate|GenCardsWindow"
```

出现 `GenCardsWindow` 的 `WINDOW_FIRST_FRAME_COMMITTED` 即生成页已显示；accepted 但无窗口日志时，优先检查 GenCards 登录状态与会话归属。

后续若需要 Generation/Card/Widget completion observability，应通过新的显式 contract 独立演进，不扩大 `INGRESS_ACCEPTED` 的现有语义。
