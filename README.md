# OriGoogle

Brings the global Google experience to the China build of vivo OriginOS. That includes Circle to Search, a corner swipe or power-key hold for Gemini, Quick Share in the share sheet, Google Discover on the home screen, and Play services with its China restrictions lifted.

OriGoogle is an [LSPosed](https://github.com/LSPosed/LSPosed)-style module built on the modern
[libxposed API 102](https://github.com/libxposed/api). Everything happens at runtime inside the
processes it hooks. No system partition is modified, and turning the module off restores stock behaviour.

Non-Google fixes for OriginOS live in the companion module
**[OriTweaks](https://github.com/kailashrs/OriTweaks)**.

> [!WARNING]
> These hooks target specific OriginOS classes and are only tested on one phone. Use them at your own risk, and keep a way to recover the phone (safe mode, or disabling the module from recovery) before you install anything that hooks `system_server`.

## Compatibility

| | |
| --- | --- |
| Device | vivo X200 Ultra (PD2454), China firmware |
| OS | OriginOS 6 (Android 16) and OriginOS 7 (Android 17, checked on 17.2.11.5 / launcher 17.0.0.402) |
| Framework | Any libxposed API 102 framework; developed on Vector |
| Google apps | Google Play services, Google app, Gemini (for the assistant features) |

The module checks `ro.vivo.os.build.display.id` and does nothing on other ROMs. Other vivo phones on
OriginOS 6/7 share most of this code and may work, but they are untested.

## Features

Each feature can be switched on or off in the OriGoogle app.

| Feature | Default | What you get |
| --- | --- | --- |
| **Global Google services** | On | Play services stops treating the phone as a China device, so Timeline, Quick Share, Find Hub and other global features turn on. |
| **Quick Share in share sheet** | On | Quick Share is pinned as the first app target in vivo's share panel. |
| **Circle to Search** | On | Long-press the gesture handle to search anything on screen with Google Lens. |
| **Corner swipe for assistant** | On | Adds a **Google Assistant** choice to the bottom-left and bottom-right corner options in Settings › System navigation (Interaction Cube). Swiping up from that corner opens Gemini. Your other corner actions are kept. |
| **Hold power for assistant** | On | Holding the power button opens Gemini instead of Jovi. |
| **Google Discover** *(experimental)* | Off | Shows Google Discover on the leftmost home screen instead of vivo's left screen. **Smart Launcher** must also be turned on in the launcher settings. |
| **Control center tiles** | Always | Google Wallet, Google Lens and Gemini tiles you can add when editing the control center. |

Not possible yet: AirDrop interop in Quick Share. Google enables it per device model (it reached the vivo X300 Ultra in April 2026), and it reportedly needs Wi-Fi that supports AWDL. The PD2454 China build's `qca_cld3` driver has no AWDL code. Quick Share's QR-code and cloud fallback for Apple devices works without it.

## Installation

1. Root the phone and install a Zygisk implementation and a libxposed API 102 framework (see [Tested setup](#tested-setup)).
2. Install the APK. vivo blocks `adb install` with a USB prompt, so install it as root:
   ```bash
   adb push app-release.apk /data/local/tmp/origoogle.apk
   adb shell su -c pm install -r /data/local/tmp/origoogle.apk
   ```
3. Enable **OriGoogle** in your framework's manager and keep the recommended scope:

   | Scope | Used for |
   | --- | --- |
   | `system` (system_server) | Play services features, Circle to Search service, power key, Quick Share, Discover window |
   | `com.bbk.launcher2` | Circle to Search gesture, corner assistant, Discover feed |
   | `com.vivo.upslide` | Google Assistant option in the Interaction Cube |

4. Reboot, or soft-reboot from the app's **Apply** button.

> [!NOTE]
> Vector does not re-apply the module's `scope.list` when you update the APK. If you are upgrading from a version that had fewer scopes, add any missing ones by hand. For example: `/data/adb/modules/zygisk_vector/cli scope add io.github.kailashrs.origoogle com.vivo.upslide/0`.

## Using the app

The app shows whether the module is active and lists every feature with a switch. When you change a switch, an **Apply** button appears:

- **Restart launcher**: for launcher-only changes.
- **Soft reboot**: for anything that hooks `system_server`. The Android framework restarts, but root and the phone itself stay up.

The module runs the Apply actions itself from inside `system_server`, through a broadcast protected by a signature permission. The app therefore needs no root.

To use the corner assistant, open **Settings › System navigation**, tap a bottom corner and choose **Google Assistant**.

## How it works

| Feature | Process | Mechanism |
| --- | --- | --- |
| Global Google services | system_server | Removes `cn.google.services` and `services_updater` from SystemConfig's available-features map. Play services reads these to decide whether it runs in China mode. |
| Quick Share in share sheet | system_server | vivo's share panel pins `Secure.nearby_sharing_component` as its first target. The module finds Play services' Quick Share send activity at boot and whenever GMS updates, and writes it there. It only does this if the setting is empty or already points at GMS, and it clears the setting when the feature is switched off. |
| Circle to Search | system_server + launcher | Starts `ContextualSearchManagerService` with the Google app as provider, trusts launcher and SystemUI callers, and adds the `CONTEXTUAL_SEARCH` feature. In the launcher, the long-press uses the overseas `GoogleCircleSearchInputConsumer`. The empty China stubs (`invokeOmni`, `SystemUiProxy.startCircleSearch`) call the service directly: `startContextualSearch(int)` on Android 16 and `(int, ContextualSearchConfig)` on 17. |
| Corner swipe for assistant | launcher + `com.vivo.upslide` | The Interaction Cube's `LeftBottomCornerPanelBuilder` / `RightBottomCornerPanelBuilder` dialogs get one more entry before *None*. Two options become three, and three become a 2×2 grid, reusing vivo's own action list. Picking it stores *None* in vivo's setting and flags the corner in `Secure.origoogle_corner_assistant_left/right`, so everything else sees no action. The flag only counts while vivo's setting still says *None*. In the launcher, `FeatureUtils.isOversea()` is forced only inside `getCornerConsumer` for a flagged corner, which selects `AssistantTouchConsumer`. The OriginOS 7 launcher also clears its assistant-corner switch whenever the right corner opens vivo's multi-task view. So inside a flagged corner, `canTriggerAssistantAction` only checks the region and the drag state. The option's card takes its size from vivo's own cards (105×138 dp on OriginOS 6, 70×92 dp on 7) and follows dark mode. |
| Hold power for assistant | system_server | `VivoInputPolicy.startJoviVoiceByPowerKey` is redirected to `PhoneWindowManager.startGoogleAssist()`. |
| Google Discover | launcher + system_server | vivo's left-screen client (`HiboardOverlayClient`) is pointed at the Google app's `DrawerOverlayService`. vivo's `ILauncherOverlay` calls are adapted to Google's launcherclient protocol: the pixel scroll position becomes 0..1 progress, and the finger-lift `onScrollEvent(2)` becomes `endScroll`. system_server drops only the global drag-and-drop interception flag from `GoogleDiscoverWindow`, which `DisplayPolicy` reserves for `MANAGE_ACTIVITY_TASKS`. |
| Control center tiles | module app | Plain `TileService`s, because vivo's control center only hosts its own tiles or third-party ones. |

### Source layout

```
app/src/main/kotlin/io/github/kailashrs/origoogle/
├── OriGoogleModule.kt      entry point, dispatches hooks per process
├── core/                   hook helpers, feature switches, logging, corner flags
├── system/                 system_server hooks (GMS, Quick Share, Circle to Search, power key, Discover window, control receiver)
├── launcher/               com.bbk.launcher2 hooks (Circle to Search, corner assistant, Discover feed)
├── interaction/            com.vivo.upslide Interaction Cube option
└── ui/                     settings app and control center tiles
```

## Tested setup

The reference phone runs this stack:

| Piece | Notes |
| --- | --- |
| KernelSU 3.3 (LKM) via the Magica jailbreak | Magica loads KernelSU after boot. |
| [NeoZygisk](https://github.com/JingMatrix/NeoZygisk) v2.4 plus a `late-load.sh` | Loads Zygisk modules through the system linker. OnyxZygisk's CSOLoader is invisible to CFI. Under Vector, that made vivo's CFI-hardened `libVivoJsonResourceManager_jni.so` crash-loop system_server. Because KernelSU arrives late, `late-load.sh` starts the ptrace monitor and restarts zygote once `lspd` is up. |
| Vector (Vector-SR v1.2) | libxposed API 102 framework. Its CLI is at `/data/adb/modules/zygisk_vector/cli`. |

## Troubleshooting

OriginOS mutes logcat for its system apps, so each hooked process writes its own log:

| Process | Log |
| --- | --- |
| system_server | `/data/system/origoogle.log` |
| launcher | `/data/user_de/0/com.bbk.launcher2/cache/origoogle.log` |
| Interaction Cube | `/data/user_de/0/com.vivo.upslide/cache/origoogle.log` |

Every hook logs whether it installed. Common problems:

- **The Play services and system features do nothing, but launcher features work.** The module was not loaded into `system_server`. This happened once on the first boot after an OTA with late-loaded Zygisk. Check with `adb shell pm list features | grep cn.google`: if it prints anything, the module is not active there. Restart zygote (soft reboot) once.
- **No Google Assistant option in the Interaction Cube.** `com.vivo.upslide` is missing from the scope (see the note under [Installation](#installation)). The app is persistent, so `am force-stop` will not restart it. Kill its process instead, or reboot.
- **Corner swipe does nothing.** Make sure the corner is set to *Google Assistant* in System navigation, and that Gemini or the Google app is set as the default digital assistant.

## Building

Requires JDK 21. The Android Gradle Plugin's jlink step fails on newer JDKs.

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. Release builds are minified and signed with your local debug key. If you install a build signed with a different key, uninstall the old app first.

## Credits and license

The structure is modelled on [Coogle](https://github.com/zopulus/coogle), which does the same job for ColorOS. Being derived from Coogle/ONextBox, OriGoogle is licensed under the **GPL-3.0** (see [LICENSE](LICENSE)).

Not affiliated with vivo or Google. All trademarks belong to their owners.
