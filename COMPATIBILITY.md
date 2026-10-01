# Compatibility report — OpenCode on 32-bit ARM Android (armeabi-v7a)

Researched 2026-10-01 against live upstream sources. Every claim below was verified
against the cited artifact.

## Summary matrix

| Component | Finding | Source | Verdict |
|---|---|---|---|
| OpenCode CLI v1.18.34 | Binaries shipped: linux-x64, linux-arm64 (+musl/baseline), darwin-x64/arm64, windows-x64/arm64. **No linux-armv7 asset.** npm `optionalDependencies` (opencode-ai@1.18.34) and GitHub release assets both confirm. | `https://registry.npmjs.org/opencode-ai/latest`, `https://api.github.com/repos/anomalyco/opencode/releases/latest` | ❌ Blocker A |
| Bun runtime | OpenCode is Bun-compiled. Bun does not build for ARMv7; Android use requires glibc-runner/proot on ARM64 only (oven-sh/bun#8685). | oven-sh/bun#8685 | ❌ Blocker A |
| Node.js for Android | nodejs/unofficial-builds has no android target; Node BUILDING.md: "Android is not a supported platform". Historical Node-on-Android projects are unmaintained and ARM64-era. | nodejs/node BUILDING.md, unofficial-builds repo | ❌ Blocker B |
| Android 10+ W^X | At targetSdk ≥ 29 (here 34), `exec()` of files extracted from APK assets into app storage is blocked by SELinux (`untrusted_app` execute_no_trans). Only `nativeLibraryDir` / `/system` paths are executable. | Android developer docs (SELinux / W^X restrictions) | ❌ Blocker C |
| Git / ripgrep on-device | Only realistic source is Termux packaging; embedding its binaries violates its terms and hits Blocker C anyway. | termux-packages | ❌ Blocker D |
| PTY APIs | `posix_openpt`, `grantpt`, `unlockpt`, `fork`, `TIOCSCTTY` available since API 21 in bionic. Used in `NativeTerminalJNI.c`. | NDK libc headers | ✅ |
| OpenCode server protocol | Fully documented HTTP API: `GET /global/health`, `POST /session`, `POST /session/:id/message`, `GET /event` (SSE); Basic auth via `OPENCODE_SERVER_PASSWORD` / `OPENCODE_SERVER_USERNAME`. | https://opencode.ai/docs/server, /docs/cli | ✅ |
| Target device RAM | Galaxy M02: 2–3 GB, MT6739 (4×A53 @1.5 GHz, ARMv7). A hypothetical local OpenCode+Node process would exceed practical budgets. | device specs | ⚠️ |

## Blocker details

**A — No ARMv7 upstream binary.** `opencode-ai@1.18.34` optionalDependencies enumerate
`opencode-linux-x64`, `opencode-linux-arm64`, `…-musl`, `…-x64-baseline` etc. ARMv7
(`linux-armv7`) is absent from both npm and GitHub release assets. Cross-compiling Bun
itself for ARMv7/bionic is not supported upstream.

**B — No Node.js for Android bionic.** Without a JS runtime there is no way to run the
TypeScript CLI source directly, even if one wanted to sidestep the compiled binary.

**C — Cannot exec extracted files.** Even if an ARMv7 binary existed, at targetSdk 34 an
app cannot `exec()` it from `filesDir/` on Android 10+. The supported path is packaging
as a native shared library executed from `nativeLibraryDir` — which still requires a
bionic-compatible ARMv7 build (does not exist, Blocker A/B).

**D — Git/ripgrep.** Server-side OpenCode handles file operations on *its* filesystem;
the Android device does not need git/rg. Local shell has toybox `sh` built in.

## Consequence (implemented design)

- Local OpenCode execution on ARMv7: **not possible**; the app says so via
  `RuntimeValidator` / `NodeExecutor` instead of pretending.
- Supported mode: **remote OpenCode server** over the documented API — this is a
  first-class OpenCode feature (`opencode attach` exists for exactly this topology).
- Local shell terminal is provided as a genuine, process-backed fallback.

## How to produce a local runtime (future work)

If an ARMv7 Android (bionic) Node.js or Bun build is ever produced:
1. Package it as `libnode.so` in `app/src/main/jniLibs/armeabi-v7a/` (W^X-compliant).
2. Add its SHA-256, size, version and license to `assets/runtime-manifest.json`.
3. `AssetExtractor` + `NodeExecutor` already validate and launch such a candidate
   (ELF `e_machine` check included) — no code changes needed.
