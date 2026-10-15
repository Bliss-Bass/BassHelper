# Bass Helper

Bass Helper gives Bass and Bliss apps, such as SmartDock, the system access they need on devices that are not running a Bass or Bliss ROM: retail Android tablets, Googlebooks and other stock devices, with or without root.

It runs a small privileged server as the shell user (the same identity `adb shell` has) or as root, and hands a binder to that server to a fixed list of Bass apps. Those apps then call system APIs through it — `IPackageManager`, `IActivityManager`, settings, `cmd` and so on — with shell privileges, without root and without each app asking the user for anything.

Bass Helper started as a fork of [Shizuku](https://github.com/RikkaApps/Shizuku) 13.6 and keeps Shizuku's protocol, so client apps use the standard Shizuku API library. It is maintained separately for the Bass and Bliss ecosystem.

## How it differs from Shizuku

- **Serves only Bass apps.** The server answers only the packages in [`BassAllowList`](server/src/main/java/rikka/shizuku/server/BassAllowList.java). They are allowed without a prompt; any other app gets no binder and its permission requests are refused. There is no per-app grant screen to manage.
- **Installs next to stock Shizuku.** Bass Helper has its own package (`com.blisscolabs.basshelper`), permissions, server process (`bass_helper_server`) and client provider authority (`<client package>.basshelper`), so a user's own Shizuku and the apps it serves are left alone.
- **Starts itself on boot without root.** On Android 13 and later it starts over wireless debugging with its own adb key; see [Starting the server](#starting-the-server).
- **Restarts itself.** The server runs under a supervisor and a watchdog; see [Keeping the server running](#keeping-the-server-running).
- **Works on desktop-style Android.** On headless system user devices (where user 0 is a background "System" user and apps live in the foreground user, as on Googlebooks) the server finds the app in whichever user has it, and the foreground user can start it.
- **Is deployed, not hand-configured.** A deployer with adb access installs it, starts it and pairs it for start on boot in one pass, with nothing to tap on the device.

## How it works

Apps reach system services over binder IPC: `PackageManager#getInstalledPackages()` is really a call into `PackageManagerService` in `system_server`, which checks the caller's uid before doing the work.

Bass Helper runs a server process as the shell user (uid 2000) or root. When an allow-listed app starts, the server sends it a binder through the app's `.basshelper` content provider. The app then sends its system API calls to the server, which makes them with its own identity and returns the results (see `transactRemote` in [`ShizukuService`](server/src/main/java/rikka/shizuku/server/ShizukuService.java)). To the app, it looks almost the same as calling the system API directly, but with the shell user's permissions.

The pieces:

| Part | What it is |
|------|------------|
| `manager/` | The Bass Helper app: starts the server, start on boot, settings |
| `manager/src/main/jni/starter.cpp` | `libbasshelper.so`, the native starter run by adb or root; it launches `bass_helper_server` through `app_process` under a supervisor |
| `server/` | The server (`rikka.shizuku.server.ShizukuService`), allow-list and watchdog |
| `api/` | The Shizuku API submodule, shared by server and clients |

## Starting the server

Any one of these starts it; each replaces a server that is already running.

- **From a computer.** `adb shell` into the device and run the starter from the app's library directory:

  ```sh
  adb shell 'dir=$(pm path --user current com.blisscolabs.basshelper | sed "s/^package://; s/base.apk$//"); $dir/lib/*/libbasshelper.so'
  ```

- **From the app.** On Android 11 and later, pair Bass Helper under Wireless debugging once, then start it from the app. On rooted devices it starts with root.
- **From a deployer.** A deployment script running as the adb shell installs Bass Helper for the current user (`pm install -r -g --user current`), grants it `android.permission.WRITE_SECURE_SETTINGS` (`pm grant`), runs the starter as above and pairs its adb key (below), so it starts on boot from then on.
- **On boot.** With start on boot enabled (the default) and `WRITE_SECURE_SETTINGS` granted (the deployer grants it), Bass Helper turns on wireless debugging after boot, finds adbd's port over mDNS and connects to it with its own key. Boot broadcasts arrive before Wi-Fi is up, so it waits for a network and retries with backoff. If that fails (no Wi-Fi, or a network where wireless debugging is refused) it posts a notification and tries again whenever the device joins a different Wi-Fi network, checked every 15 minutes. Rooted devices start with root instead.

  A deployer can run this path without rebooting: `content call --user <id> --uri content://com.blisscolabs.basshelper.shizuku --method scheduleStart` (shell user only).

  The adb key has to be paired with the device once. A deployer does that over adb with `content call --uri content://com.blisscolabs.basshelper.shizuku --method pairAdbKey` (only the shell user may call it); Bass Helper then accepts its own pairing request the same way Android Studio's QR pairing works, with nobody reading a code off the screen.

## Keeping the server running

- **Supervisor.** The starter does not hand the server to the system and exit; it leaves a small `bass_helper_supervisor` process that waits on the server and starts a new one 5 seconds after it crashes or is killed. A server that ran for at least a minute resets the count; after 5 failures in a row the supervisor gives up. Stop in the app (a clean exit) and an uninstalled app end supervision. After an app update the supervisor finds the new APK before restarting.
- **Watchdog.** Inside the server, `ServerWatchdog` exits the server if its main thread stops responding for a minute, or if a core system service (`package`, `activity`, `user`, `appops`) dies — a `system_server` restart would otherwise leave the server holding dead binders. The supervisor then starts a fresh server, which waits for those services to come back.

## Using Bass Helper from an app

1. Add the package to [`BassAllowList`](server/src/main/java/rikka/shizuku/server/BassAllowList.java).
2. Use the [Shizuku API](https://github.com/RikkaApps/Shizuku-API) library as you would for Shizuku.
3. Declare a second provider at `${applicationId}.basshelper` that accepts the binder. Stock `ShizukuProvider` keeps the first living binder it gets, so if the app may also see stock Shizuku, subclass it and let Bass Helper's binder replace that one:

   ```kotlin
   class BassHelperProvider : ShizukuProvider() {
       override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
           if (method != METHOD_SEND_BINDER || extras == null) return super.call(method, arg, extras)
           extras.classLoader = BinderContainer::class.java.classLoader
           val binder = extras.getParcelable<BinderContainer>("moe.shizuku.privileged.api.intent.extra.BINDER")?.binder
           if (binder == null || !binder.pingBinder()) return super.call(method, arg, extras)
           Shizuku.onBinderReceived(binder, context!!.packageName)
           return Bundle()
       }
   }
   ```

   ```xml
   <provider
       android:name=".BassHelperProvider"
       android:authorities="${applicationId}.basshelper"
       android:multiprocess="false"
       android:enabled="true"
       android:exported="true"
       android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
   ```

Things to keep in mind:

- The shell user's permissions are limited and vary by Android version; see the Shell app's [manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml). Use `Shizuku.getUid()` to tell shell from root, and `Shizuku.checkRemotePermission()` before relying on a permission.
- Hidden APIs are restricted for apps since Android 9; use something like [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).
- System interfaces and transaction codes change between Android versions. Prefer `ShizukuBinderWrapper` around the real AIDL interface over hand-written `transactRemote` calls.

## Building

- Clone with `git clone --recurse-submodules`
- Run `./gradlew :manager:assembleDebug` or `./gradlew :manager:assembleRelease`

The debug build has a debuggable server: attach a debugger to `bass_helper_server`. In Android Studio, check "Always install with package manager" under Run/Debug configurations so the server uses the latest code.

Server, supervisor and starter logs: `adb logcat -s Service BinderSender Shizuku ServerWatchdog`.

## Credits

Bass Helper is built on [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps and its contributors. It also adapts:

- The starter's server supervisor and the server watchdog from [Nightzuku](https://github.com/kerneldroid/Nightzuku) by kerneldroid (Apache-2.0).

## License

The code for this project is available under the Apache-2.0 license.

### Exceptions

These terms come from Shizuku and still apply to the parts derived from it.

* You are **FORBIDDEN** to use image files listed below in any way (unless for displaying Shizuku itself).

  ```
  manager/src/main/res/mipmap-hdpi/ic_launcher.png
  manager/src/main/res/mipmap-hdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-hdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png
  manager/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
  ```

  Bass Helper no longer ships these files; it has its own icon.

* For the project as a whole, it is not free.
You are **FORBIDDEN** to distribute the apk compiled by **you**
(including modified, e.g., rename app name "Shizuku" to something else)
to any store (IBNLT Google Play Store, F-Droid, Amazon Appstore etc.).
