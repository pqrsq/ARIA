# Troubleshooting — read this first if the build fails

## The error you saw

```
property 'mainManifest' specifies file
  ...\workspace-01a1c197-...\ARIA\app\src\main\AndroidManifest.xml
which doesn't exist
```

This is **not** a code problem and **not** a phone problem. It means Gradle
started building a project whose `app/src/` folder was missing. Two causes:

1. **The wrong folder was opened.** If you open the folder *above* the project
   (or an exported/downloaded bundle), Android Studio looks for
   `…/app/src/main/AndroidManifest.xml` relative to *that* folder. The path in
   your error contained a `workspace-…` folder name, which is not part of the
   project — a sign Studio was pointed at a container folder instead of `ARIA`.
2. **The extracted copy was incomplete.** Files can be dropped by some unzip
   tools, macOS Archive Utility quirks, or a partial download.

**Rule: open the folder that contains `settings.gradle.kts`.**
That folder is called `ARIA` and it directly contains:

```
settings.gradle.kts   build.gradle.kts   gradle.properties   gradlew   app/
```

---

## Fix, step by step

1. Delete the broken copy you have (and, in Android Studio,
   *File ▸ Close Project*).
2. Extract **`ARIA-phase1.zip`** to a short, plain path — e.g.
   `C:\Users\Parsa\AndroidProjects\ARIA`.
   Avoid OneDrive/Dropbox-synced folders and paths with unusual characters.
3. Confirm the folder is complete before opening it. Open
   `ARIA\FILE_MANIFEST.txt` — it lists 39 files with checksums.
   The important ones must be present:
   - `app\src\main\AndroidManifest.xml`
   - `app\src\main\java\com\example\aria\MainActivity.kt` (+ 16 more `.kt` files)
   - `gradle\libs.versions.toml`, `gradle\wrapper\gradle-wrapper.jar`
   - `settings.gradle.kts`, `gradlew`, `gradlew.bat`
4. In Android Studio: *File ▸ Open* → select the **`ARIA`** folder itself →
   *Open*. When asked, choose **"Trust project"**.
5. Wait for *Gradle sync* to finish (bottom bar). If a banner offers to install
   an SDK Platform or Build Tools, click **Install**.

Then press ▶ (Run).

---

## Other messages you might see, and what they mean

| Message | Meaning | What to do |
|---|---|---|
| `SDK location not found` | Studio doesn't know where the Android SDK is | Click the offered link, or *File ▸ Settings ▸ Languages & Frameworks ▸ Android SDK* → set the path |
| `Unsupported class file major version` / `Unsupported Java` | Project needs JDK 17+ | *File ▸ Settings ▸ Build, Execution, Deployment ▸ Build Tools ▸ Gradle → Gradle JDK* → choose **JetBrains Runtime 17** (or 21) |
| `Could not resolve …` / dependency download errors | No/blocked network during first sync | Check your internet, proxy, or VPN; then *File ▸ Sync Project with Gradle Files* |
| `Plugin [id 'com.android.application', version '8.7.3'] was not found` | Offline mode is on | Turn off *Gradle ▸ Offline mode* in the Gradle panel |
| `No connected devices` | Phone not authorised | Replug USB, accept **Allow USB debugging**, check `adb devices` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch | An older ARIA build with a different key is installed | Uninstall the old ARIA app on the phone, then run again |
| `Deprecated Gradle features … incompatible with Gradle 10` | Harmless warning from Android Studio's own Gradle wrapper version | Ignore it; it is not an error |

---

## "Just let me see it running"

You don't need to build anything to try Phase 1. Copy
`artifacts/aria-phase1-debug.apk` to the phone (USB, Drive, Telegram — anything),
tap it, and allow *Install unknown apps* for that source. That APK is the exact
build that passed the tests.

---

## Notes specific to this project

- **`local.properties` is intentionally not included.** It stores the SDK path of
  whatever machine built the project; Android Studio creates it for you. If it is
  ever wrong you'll see `SDK location not found` — fix it via the SDK settings
  screen, or create the file by hand with one line:
  `sdk.dir=C\:\\Users\\Parsa\\AppData\\Local\\Android\\Sdk`
- **Gradle 8.9 is pinned** by the wrapper, so the project builds the same way on
  every machine. Do not "upgrade Gradle" unless a phase asks for it.
- **`minSdk 26`** (Android 8.0). Your phone almost certainly qualifies; if it is
  older, the install will refuse and I'd need to lower `minSdk`.
