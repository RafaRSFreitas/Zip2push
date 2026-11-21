# ZipPush

Push archives and files to GitHub, straight from your phone — no PC, no Git command line, and no laptop required.

ZipPush is inspired by the workflow of "ZIP to GitHub Upload" (com.zip2git) but extends it with the most requested convenience: **your repositories are listed in a dropdown menu**, so you never have to paste a repo URL again. Branches are listed in a dropdown too.

## Features

- **Repository dropdown** — after connecting, all repositories your token can push to are loaded automatically (paginated, up to all repos). Just pick one; no URL pasting. Private repos are marked with 🔒.
- **Branch dropdown** — all branches of the selected repository, with the default branch preselected.
- **Create a new branch** — optionally type a new branch name; it is created from the selected branch and the push targets it.
- **Archive upload (RAR/ZIP/TAR only)** — the **Add Rar/Zip/Tar** button takes a `.rar`, `.zip` or `.tar` archive (format verified by magic bytes, not the file name) and its contents are pushed into the repository. If the archive has a single root folder, it is stripped automatically so files land where you expect. Compressed tarballs (`.tar.gz`/`.tgz`) are NOT accepted by this button — send them as ordinary files instead.
- **Any file upload** — the **Add file** button accepts any file type, one or several at a time, and pushes them all in a single commit. The **Add folder** button next to it keeps uploading a whole folder (recursively) as before.
- **Target folder Explorer** — the folder icon on the target-folder field opens a full-screen picker (v0.4.0) that browses the REAL folders of the selected repository/branch, Explorer-style: a fixed top bar with close and the `owner/repo · branch` subtitle, tappable breadcrumbs (`repo / folder / subfolder`), a dedicated level-up button to return to the parent folder, one-tap navigation into subfolders, a "New folder…" action (nested names supported) and a fixed "Use this folder" footer to confirm. The picker is restored when you rotate the phone (same folder, remote or freshly created). Free-typing a path still works; existing repo files are preserved.
- **Push progress with file counter** — while the push uploads, the status line shows how many files are going up ("Pushing to repo — 3 of 7 files…") and the bottom bar turns into a determinate progress bar that advances with each file.
- **Account switch refreshes the push card** — switching the GitHub account (or connecting a new token) automatically resets the repository, branch, target folder and the previous push's link, then loads the new account's repositories. Picked files stay; the auto-reconnect after a screen rotation never clears anything.
- **Custom commit messages** — or accept the default.
- **Private repository support** — works with any token that has access.
- **Token stays on device** — the PAT is stored encrypted with a hardware-backed Android Keystore key (AES/GCM). No backend, no analytics, no server storage of tokens or files.
- **Modern UI** — Material 3, dark theme, minimal and speed-focused.

## How to use

1. Create a GitHub Personal Access Token (classic with `repo` scope, or a fine-grained token with read/write contents permission) at **Settings → Developer settings → Tokens**.
2. Open ZipPush, paste the token, tap **Connect**.
3. Pick a repository from the dropdown.
4. Pick a branch (or type a new branch name).
5. Optionally set a target folder and commit message.
6. Tap **Add Rar/Zip/Tar** (archives only) or **Add file** (any file, several at once) / **Add folder**.
7. Tap **Push to GitHub**.

## What's new in v0.4.0

- The top picker button is now called **Add Rar/Zip/Tar** and accepts ONLY those three formats (`.rar`, `.zip`, `.tar`), validated by magic bytes at pick time. `tar.gz`/`.tgz` files are no longer taken by this button — pick them with **Add file** instead.
- The lower-left button is now called **Add file**: any file type, one or many at once (side-by-side with **Add folder**, unchanged).

## Building the APK

Requirements: JDK 17+, Android SDK with platform 34 + build-tools 34 (or just let Android Studio handle it).

### Android Studio (easiest)

1. Open this folder (**File → Open**).
2. Let Gradle sync (it downloads the Android Gradle Plugin, Kotlin, and AndroidX automatically).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**, or run directly on a device.

### Command line

```bash
./gradlew assembleRelease        # signed release APK (keystore included)
./gradlew assembleDebug          # debug APK
# output: app/build/outputs/apk/release/app-release.apk
```

The release signing keystore is at `keystore/zippush-release.keystore`

| Keystore value | Value |
| --- | --- |
| Store password | `zippushstore` |
| Key alias | `zippush` |
| Key password | `zippushstore` |

> If you publish this app or rebuild for production use, generate your own keystore with `keytool -genkeypair` and update `app/build.gradle`.

### Gradle wrapper

This project includes the Gradle wrapper (`gradlew`, `gradle/wrapper/*`), so you do not need to install Gradle. The wrapper downloads Gradle 8.9 automatically on first build. Note: the wrapper jar is a binary file that ships with the project. When you first run `./gradlew` it will fetch the distribution listed in `gradle/wrapper/gradle-wrapper.properties` over HTTPS from services.gradle.org.

## Project structure

```
ZipPush/
├── app/
│   ├── build.gradle                      # module config (signing, deps)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zippush/app/
│       │   ├── MainActivity.kt           # UI + workflow
│       │   ├── net/GitHubClient.kt       # GitHub REST API client
│       │   ├── engine/UploadEngine.kt    # ZIP → blobs → tree → commit
│       │   └── storage/TokenVault.kt     # encrypted token storage
│       └── res/                          # layouts, strings, icons
├── keystore/zippush-release.keystore     # release signing key
├── build.gradle                          # root build file
├── settings.gradle
└── gradle/ + gradlew                     # Gradle wrapper
```

## How a push works (under the hood)

For each push the app performs the GitHub Git Data API dance:

1. Resolve the target branch head SHA (creating a new branch if requested).
2. Read the head commit's tree (so existing files are preserved).
3. Create a blob for every file being pushed (base64, binary-safe).
4. Create a new tree based on the base tree with the pushed entries overlaid.
5. Create a commit pointing at the new tree, parented on the branch head.
6. Fast-forward the branch ref to the new commit.

Everything happens directly between your phone and `api.github.com`.

## Privacy & disclaimer

ZipPush is an independent tool, not affiliated with or endorsed by GitHub, Inc. The app has no backend; the token is stored only on your device (encrypted) and files are sent only to GitHub's API. Internet permission is used solely to talk to `api.github.com`.
