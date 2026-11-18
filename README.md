# ZipPush

Push ZIP archives and files to GitHub, straight from your phone — no PC, no Git command line, and no laptop required.

ZipPush is inspired by the workflow of "ZIP to GitHub Upload" (com.zip2git) but extends it with the most requested convenience: **your repositories are listed in a dropdown menu**, so you never have to paste a repo URL again. Branches are listed in a dropdown too.

## Features

- **Repository dropdown** — after connecting, all repositories your token can push to are loaded automatically (paginated, up to all repos). Just pick one; no URL pasting. Private repos are marked with 🔒.
- **Branch dropdown** — all branches of the selected repository, with the default branch preselected.
- **Create a new branch** — optionally type a new branch name; it is created from the selected branch and the push targets it.
- **ZIP upload** — pick a `.zip` and its contents are pushed into the repository. If the archive has a single root folder, it is stripped automatically so files land where you expect.
- **Multiple file upload** — pick any number of individual files and push them all in one commit.
- **Target folder** — optionally push into a subfolder of the repo (e.g. `assignments/week3`); existing repo files are preserved.
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
6. Tap **Add ZIP archive** or **Add individual files**.
7. Tap **Push to GitHub**.

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
