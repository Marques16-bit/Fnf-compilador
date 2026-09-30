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

## V-Slice

FunkinCrew/Funkin is built with the `vslice` recipe. It has no Project.xml, only `project.hxp`, keeps its assets in git submodules and pins every library in `hmm.json`, including Funkin forks of Lime, OpenFL, hxcpp, haxelib and hmm. The workflow clones the submodules, installs Funkin's patched haxelib and hmm, runs `hmm install`, compiles hxcpp, rebuilds the native libraries for the target and builds with Haxe 4.3.7, `-release` and `-DGITHUB_BUILD`, the same as the upstream CI. Use the GitHub URL, because a ZIP does not contain the submodules.

For Android the workflow installs NDK 29.0.13113456 with JDK 17 and signs the APK with a throwaway key. Mobile ads and in app purchases are disabled with `-DNO_FEATURE_MOBILE_ADVERTISEMENTS` and `-DNO_FEATURE_MOBILE_IAP`, since they need credentials. The assets are around 4 GB and their license is proprietary, so read the Funkin.assets LICENSE before distributing a build.

## Mario's Madness

Dewott2501/Mario-Madness is a Psych Engine 0.6 era fork. It has no `hmm.json` and no `setup` folder, only `complations_preset.bat` with `haxelib set` lines. The workflow now reads the `haxelib` lines of any `.bat` or `.sh` in the project root or `setup`, installs each pinned version (flixel 5.3.1, lime 8.0.2, openfl 9.2.0 and others), then installs the libraries that Project.xml lists and nothing pinned, using the known git sources for `linc_luajit` and `discord_rpc` and `hscript` 2.5.0. Pins older than flixel 5.4 or lime 8.1 select the legacy toolchain, Haxe 4.2.5, on `ubuntu-22.04` and `macos-15-intel`.

The source includes `#include <windows.h>` in `Transparency.hx`, `Wallpaper.hx` and others without a platform guard, so it only compiles for Windows. The app detects that and warns when another platform is selected.

## Anti crash

- Every network call retries with backoff on connection errors, 5xx, 429 and rate limits. Tracking a build tolerates up to 20 failed polls in a row before giving up, and gives up after six hours.
- A started build is remembered. If the app is closed or killed, it offers Resume and reattaches to the same run.
- Cancel stops the local work and cancels the run on GitHub.
- Errors, including out of memory, become a message instead of a crash.
- An uncaught crash is written to a file on Android and desktop and to user defaults on iOS. The next launch shows a banner with the details.
- In the workflow, cloning, submodules, library installs and hmm are retried three times.

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

`Build.yml` runs on every push, pull request and manual dispatch. It runs the tests, then builds the Android APK, the Windows MSI and EXE, the macOS DMG, the Linux DEB and the iOS IPA.

`release.yml` runs only when started manually from the Actions tab. It asks for a version such as `1.2.3` (a leading `v` is accepted), a pre-release flag and optional notes. It checks that the tag `v1.2.3` does not exist yet, calls `Build.yml` with that version so the app, the APK version code and the IPA carry it, and publishes every file to a GitHub release named `v1.2.3`, with the platform and version in each file name.
