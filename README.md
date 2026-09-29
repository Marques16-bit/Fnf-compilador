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

1. Pick the mod `.zip` or paste a public GitHub repository URL such as `https://github.com/ShadowMario/FNF-PsychEngine`. The app reads it, detects the engine, checks the dependency setup (`hmm.json` or `setup/unix.sh`) and scans the Haxe sources for code that breaks on mobile.
2. Choose a platform and press Compile. With a token the whole flow is automatic: the app reads the source, builds it and opens the download when it is ready. Without a token GitHub cannot be triggered from the app, so it opens the workflow page and shows the exact values to enter, then checks the result on demand.
3. For a ZIP the app uploads the archive to a release of the build repository. For a repository URL nothing is uploaded. It then dispatches `build-mod.yml`.
4. The workflow gets the source, patches `Project.xml`, installs the libraries, builds with Lime, publishes `result-<platform>` to the release and deletes the uploaded archive.
5. The app follows the run and offers the download link.

The build repository must be public so the result can be downloaded. The token is optional and needs `Contents` and `Actions` with read and write access when used.

## Psych Engine

Psych Engine has no `hmm.json`. The workflow reads the `haxelib` lines of `setup/unix.sh` and `setup/windows.bat` to install the exact pinned libraries, uses Haxe 4.3.4 and passes `-D officialBuild`, the same as the upstream CI. Use the GitHub URL for it because its ZIP is about 600 MB.

Desktop targets match the upstream setup. The upstream source has no touch controls and enables mods, Lua and HScript only on desktop, so Android and iOS builds start but are meant for sources with mobile support.

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
