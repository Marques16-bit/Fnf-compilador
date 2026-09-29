# FNF Compiler

Kotlin Multiplatform app that turns a Friday Night Funkin mod source archive into an installable build.

| Platform | Output |
| --- | --- |
| Android | APK |
| iOS | IPA (unsigned) |
| Windows | ZIP with the EXE |
| macOS | ZIP with the APP |
| Linux | ZIP with the binary |

The compiler app itself runs on Android, iOS, Windows, macOS and Linux. There is no web target.

## How it works

1. Pick the mod `.zip`. The app reads it locally, detects the engine, checks `hmm.json` and scans the Haxe sources for code that breaks on mobile.
2. Choose a platform and press Compile.
3. The app uploads the archive to a release of the build repository and dispatches `build-mod.yml`.
4. The workflow patches `Project.xml`, builds with Lime, publishes `result-<platform>` to the release and deletes the uploaded archive.
5. The app follows the run and offers the download link.

The build repository must be public so the result can be downloaded, and the token needs `Contents` and `Actions` with read and write access.

## Project layout

```text
composeApp/src/commonMain   shared UI, archive reader, analyzer, GitHub client
composeApp/src/androidMain  Android activity, manifest, icons
composeApp/src/desktopMain  Windows, macOS and Linux entry point
composeApp/src/iosMain      iOS view controller
composeApp/icons            ico, icns and png icons for desktop packaging
iosApp                      Xcode project definition for xcodegen
.github/workflows/Build.yml       builds every executable of this app
.github/workflows/build-mod.yml   builds a mod for the chosen platform
```

## Building locally

Requires JDK 17.

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:assembleRelease
./gradlew :composeApp:packageDistributionForCurrentOS
./gradlew :composeApp:run
```

The iOS build needs macOS with Xcode and xcodegen:

```bash
cd iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Release -sdk iphoneos -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

## CI

`Build.yml` runs on every push, pull request and manual dispatch. It runs the tests, then builds the Android APK, the Windows MSI and EXE, the macOS DMG, the Linux DEB and the iOS IPA. Pushing a tag that starts with `v` also publishes all files to a GitHub release.
