# OriGoogle

Google integrations for vivo X200 Ultra (PD2454, OriginOS 6 and 7 China, Android 16 and 17; checked on OriginOS 7.0 / launcher 17.0.0.381), modeled on
[Coogle](https://github.com/zopulus/coogle) for ColorOS 17. Everything is applied at runtime by an
LSPosed (libxposed API 102) module; no partition is modified.

## Runtime stack on the phone (KernelSU 3.3 LKM, Magica jailbreak)

| Piece | Why |
| --- | --- |
| NeoZygisk v2.4 (`/data/adb/modules/zygisksu`) + added `late-load.sh` | Loads Zygisk modules through the system linker. OnyxZygisk's CSOLoader is invisible to CFI, so vivo's CFI-hardened `libVivoJsonResourceManager_jni.so` crash-looped system_server under Vector. `late-load.sh` starts the ptrace monitor and restarts zygote once `lspd` is up, because Magica loads KernelSU after boot. |
| Vector-SR v1.2 (`zygisk_vector`) | libxposed API 102 framework; `cli` at `/data/adb/modules/zygisk_vector/cli`. |
| OriGoogle APK | This project. Scope: system, launcher, Interaction Cube (`com.vivo.upslide`). |

## Features

| Feature | Where | How |
| --- | --- | --- |
| China GMS restrictions removed (Location History/Timeline, Quick Share, …) | system_server | Drops `cn.google.services` and `services_updater` from SystemConfig's feature map. |
| Quick Share in share sheet | system_server | vivo's share panel pins `Secure.nearby_sharing_component` as its first app target; the module resolves Play services' Quick Share send activity at boot and on GMS updates and writes it there (only if unset or already a GMS component; cleared when switched off). |
| Circle to Search | system_server + launcher | Starts `ContextualSearchManagerService` with the Google app as provider, trusts launcher/SystemUI callers, adds `CONTEXTUAL_SEARCH`; launcher uses its overseas `GoogleCircleSearchInputConsumer` and the empty China stubs now call the service (`startContextualSearch(int)` on Android 16, `(int, ContextualSearchConfig)` on 17). |
| Corner swipe assistant | launcher + Interaction Cube (`com.vivo.upslide`) | Adds a **Google Assistant** option to the bottom-left and bottom-right corner choices in the Interaction Cube (System navigation), next to vivo's own options, which stay as they are. The cube's `LeftBottomCornerPanelBuilder` / `RightBottomCornerPanelBuilder` dialogs get one more entry before None (two options become three, three become a two-by-two grid) and reuse vivo's own action list. Picking it stores None in vivo's setting and flags the corner in `origoogle_corner_assistant_left/right` (secure), so every other reader sees no action. The flag only counts while vivo's setting still says None. The launcher forces `isOversea()` inside `getCornerConsumer` only for a flagged corner, selecting `AssistantTouchConsumer` there. OriginOS 7's launcher also clears its assistant-corner switch whenever the right corner opens vivo's multi-task view, so inside a flagged corner only the region and drag checks count. The added option's card takes its size from vivo's own cards (105 x 138 dp on OriginOS 6, 70 x 92 dp on 7) and follows dark mode. |
| Hold power → assistant | system_server | `VivoInputPolicy.startJoviVoiceByPowerKey` → `PhoneWindowManager.startGoogleAssist()`. |
| Google Discover feed | launcher + system_server | Points vivo's left-screen client (HiboardOverlayClient) at the Google app's DrawerOverlayService and adapts vivo's ILauncherOverlay to Google's launcherclient protocol (pixel scroll → 0..1 progress, finger-lift `onScrollEvent(2)` → `endScroll`). system_server drops only the global drag-and-drop interception flag from `GoogleDiscoverWindow`, which DisplayPolicy reserves for MANAGE_ACTIVITY_TASKS. |
| Control center tiles | module app | Google Wallet, Google Lens, Gemini `TileService`s (vivo's control center only hosts its own or third-party tiles). |

Not yet available: AirDrop interop in Quick Share. Google enables it per device model (it reached the
vivo X300 Ultra in April 2026) and it reportedly needs AWDL-capable Wi-Fi; the PD2454 China build's
qca_cld3 driver has no AWDL code. Quick Share's QR/cloud fallback for Apple devices works without it.

## Settings app

Apply actions are executed by the module inside system_server (signature-protected broadcast), so
the app needs no root.

## Debugging

OriginOS mutes logcat for its system apps. Each hooked process writes
`origoogle.log` in its data dir, e.g. `/data/user_de/0/com.bbk.launcher2/cache/origoogle.log`,
`/data/system/origoogle.log`.

## Build

`./gradlew :app:assembleRelease` (JDK 21, compileSdk 37). Derived work of Coogle/ONextBox: GPL-3.0.
