# Shared Voice Ingress V2 Deployment Guide

`VOICE_INGRESS_CONTRACT_VERSION = 2` freezes only the Binder wire contract. Runtime package and
service component selection belongs to the Android host build, not to the shared AIDL artifact.

## Required APKs

- GenCards V2, declaring `com.flyme.auto.genagent.permission.REQUEST_EDIT_QUERY` as a used
  signature permission.
- A GenAgent V2 host exporting `IGenAgentEditQueryCapture` behind the same signature permission.
- Both APKs must be signed in the same trusted signing domain so Android grants the permission.

## Flows

```text
CREATE: GenAgent -> GenCards -> submitTextCreateQuery(query)
EDIT:   GenCards -> GenAgent -> requestEditQuery(requestId) -> callback(requestId, query) -> GenCards
```

`requestId` is ephemeral and opaque. `pairId`, accepted Card/Widget bytes, account identity, A2UI and
AtomicAbility state never cross EDIT Binder. Process death terminates the active acquisition; an old
request is never persisted or restored after restart.

## Build configuration

GenCards debug builds may target the sample Playground host. GenCards release builds must supply a
real production package and service component through the host build configuration. A missing or
Playground release target is a release-gate failure, not an automatic fallback to CREATE.

## Query payload note

The current CREATE/EDIT integration has no independent Binder-only max-length policy. EDIT reuses the
same host text input semantics and only trims leading/trailing whitespace before callback. Normal user
queries are far below Binder transaction limits, but a future product-wide input-size policy should be
shared by CREATE and EDIT rather than introducing a second EDIT-specific truncation rule.

## Compatibility

- GenCards V2 + GenAgent V1: EDIT capture is unavailable/fails closed; legacy CREATE remains usable.
- GenCards V1 + GenAgent V2: legacy CREATE remains usable; the V2 reverse EDIT service is unused.

## Release checklist

- [ ] GenCards V2 installed
- [ ] GenAgent V2 installed
- [ ] Same trusted signing domain
- [ ] EDIT capture service resolves explicitly
- [ ] Signature permission granted
- [ ] CREATE smoke test passes
- [ ] EDIT Binder smoke test passes
- [ ] Cancellation smoke test passes
- [ ] Concurrent EDIT R2 rejection passes
- [ ] GenAgent process-death test passes
- [ ] GenCards process-death test passes
- [ ] Activity recreation keeps active R1 within the same process
- [ ] Process restart does not restore old R1
- [ ] Release component verification passes
