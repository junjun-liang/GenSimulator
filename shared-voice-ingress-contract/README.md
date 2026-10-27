# GenCards / GenAgent Voice Ingress Contract

Status: authoritative cross-app IPC source for Shared Voice Ingress Contract V2.

This shared module is intentionally outside Android application roots. Both repositories consume an
identical copy so AIDL and stable Binder constants remain reviewable as one compatibility boundary.

Owned here:

- `IGenCardsVoiceQueryIngress.aidl`, preserving the V1 CREATE/legacy Voice method order.
- `IGenAgentEditQueryCapture.aidl` for GenCards -> GenAgent correlated EDIT acquisition.
- `IGenCardsEditQueryCallback.aidl` for GenAgent -> GenCards terminal EDIT results.
- `VOICE_INGRESS_CONTRACT_VERSION = 2` release metadata.
- Stable signature permission and callback error codes for GenAgent EDIT capture.
- Protected external-entry action/extras/version/components plus the Voice/Create string values.

Direction and ownership:

```text
CREATE: GenAgent -> GenCards (submitTextCreateQuery)
EDIT acquisition: GenCards -> GenAgent -> callback -> GenCards
```

`requestId` is an opaque correlation token. `pairId`, account identity, accepted Card/Widget raw
payloads, A2UI, AtomicAbility and grounded capability state never cross the EDIT capture Binder
contract. GenCards resolves `requestId -> pairId` and reloads the exact accepted Card itself before
entering its existing `CardGenerationRequest.Edit` lifecycle.

Not owned here: Composer, GenerationSessionRuntime, Agent provider, A2UI, AtomicAbility, Activity or
Service implementation. `submitTextCreateQuery` returning `true` means ingress accepted only;
Card/Widget completion remains in GenCards. `requestEditQuery` returning `true` means GenAgent
accepted acquisition only; the terminal result is delivered exactly once through the callback.

EDIT capture is an ephemeral process/session contract. Neither side persists a callback or request id
for restart recovery: GenAgent death fails the pending GenCards request closed, GenCards death clears
the GenAgent callback through Binder death, and a later process restart starts from a new request id.

`verifyVoiceIngressContractFingerprint` is wired into this module's `check` task. It hashes every
checked-in AIDL/Java contract source and compares it with `contract-fingerprint.sha256`, so a local
mirror cannot silently drift. The same fingerprint file must be copied with the same contract sources
into both repositories. `verifyVoiceIngressAidlAbi` independently freezes the legacy CREATE method
order and the V2 EDIT signatures.

Deployment note: package/service component names are intentionally **not** part of this wire contract.
GenCards supplies them per build variant. Debug may target the Playground host; release must be wired
to a real production GenAgent host and is expected to fail release verification while that host is
unresolved. This separation keeps the V2 wire ABI stable across product flavors.
