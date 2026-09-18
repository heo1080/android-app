# DolphinAssistant Next

DolphinAssistant Next is a clean application foundation under `next/`.

## Hard rules

- Same package id and same release signing key so the currently installed DolphinAssistant can update in place.
- No dependency on the v30/v31 MainActivity, XML screens, patch scripts, settings hierarchy, or UI shell.
- UI never calls BYD HAL directly.
- Vehicle integration is isolated behind `VehicleGateway`.
- Signals carry source, timestamp and confidence; stale data is explicitly detectable.
- A single setting has a single owner screen.
- VERIFIED/BETA/LAB is part of the architecture.
- Unverified setters are never automatically scanned.
- Successful builds are published as GitHub Releases so the in-app updater can install them.

## Data path

```
BYD HAL / Broadcast / Feature ID
        ↓
SignalSource / VehicleGateway
        ↓
SignalResolver + capability cache
        ↓
VehicleState (timestamp + confidence + stale)
        ↓
Event Engine
        ↓
Audio / Automation / UI
```

## Migration policy

VERIFIED: front seat heating, steering-wheel heating; 2-way split queued for clean migration.

BETA: BSD, NORMAL/AutoHold calibration, leading-vehicle departure.

LAB: interior lamp actuator, reverse/down mirror, seat memory absolute position, cluster/CAN/TBT writes, radar tracks, third-party app driver-only audio, DPI/logical-display experiments, 3/4 split.

The latest cluster research treats the Korean 5-inch cluster as a separate Qt/CAN system. The AmapService AUTONAVI_STANDARD_BROADCAST_SEND route remains a research bridge; direct BYDAutoInstrumentDevice access is not treated as production capability until permissions and real-car behavior are proven.
