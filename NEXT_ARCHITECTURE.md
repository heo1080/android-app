# DolphinAssistant Next

This branch is a clean rebuild. The legacy `app/` module is intentionally not included in
`settings.gradle.kts`; only `nextapp/` is built.

## Architecture

```
BYD HAL / public getters
        ↓
BydVehicleGateway
        ↓
VehicleStateStore (single state snapshot)
        ↓
VehicleEventEngine
        ↓
AlertEngine / future automation / future display controllers
        ↓
Compose UI
```

The UI never calls raw BYD reflection directly. All vehicle access is owned by the Gateway.

## Feature gates

### Production / verified paths
- Gear and speed read path
- EPB read path
- Front driver/passenger seat heating
- Steering-wheel heating
- AC power
- DolphinAssistant-owned audio on BYD legacy stream 14
- Signed GitHub Release updater

### BETA
- Drive mode raw mapping (1/2/3 => ECO/NORMAL/SPORT) until the Korean Dolphin NORMAL
  transition is re-captured in Next diagnostics
- AutoHold holding inference
- BSD raw transition + matching turn direction
- Front-object departure using parking sensor areas 7/8

### LAB only
- Interior-light actuator
- Reverse/down mirror actuator
- Memory-seat position setter
- Korean 5-inch cluster theme/render path
- Cluster CAN/TBT correlation / 0xAA00020F
- Forward radar track candidates
- Arbitrary third-party app driver-only audio routing
- 3/4 split via VirtualDisplay

## UI ownership

One setting has one owner:
- Drive = status/feature state only
- Vehicle = verified vehicle controls
- Audio/Alerts = all BEEP/TTS settings
- Navigation/Display = screen integrations
- Automation = rules/startup/media
- LAB/Diagnostics = unverified research and capture

No legacy XML screen, Spinner menu, v30/v31 patch script, or legacy SettingsManager is used
by the Next module.

## Legacy cleanup gate

Do not delete the legacy module, branches, workflows, or older releases until:
1. Next Release installs over the current signed DolphinAssistant.
2. Next launches on the Korean Dolphin.
3. Verified vehicle controls still work.
4. Next diagnostics can be exported.
5. At least one drive/audio real-car session is captured.

After these gates pass, legacy GitHub content can be removed as requested.
