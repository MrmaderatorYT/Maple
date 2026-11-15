# Maple

A PC emulator for Android built on QEMU. Runs full x86-64 and ARM64 virtual machines on a
phone: Linux ISOs, Android-x86 / BlissOS, Windows installers (XP to 11, x86 and ARM), BSDs, DOS,
ReactOS, Haiku. No root, no accounts, no ads, no tracking, no paid tier — every feature is free.

Written in **Java only** (no Kotlin, no AndroidX — the APK has no Kotlin runtime in it) plus a
small C library and the QEMU binaries built with the NDK.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../model` | `VmConfig` (one VM), `VmStore` (JSON on disk), `OsType` |
| `app/src/main/java/.../qemu` | `QemuCommandBuilder` (per-OS virtual hardware), `VmSession` (process, QMP, vCPU pinning, saved states), `VmService` (foreground service, heat and battery guard), `QmpClient`, `NativeBridge` |
| `app/src/main/java/.../display` | `RfbClient` (VNC over a unix socket, with QEMU's scancode, relative-pointer and audio extensions), `VmDisplayView` (rendering, trackpad / direct touch / mouse, keyboard) |
| `app/src/main/java/.../iso` | `IsoInspector` — reads the ISO label, El Torito boot catalog and `/EFI/BOOT` to pick OS, architecture and firmware |
| `app/src/main/java/.../library` | OS library: catalog, resumable HTTPS download, checksum verification, unpacking |
| `app/src/main/java/.../ui` | VM list, VM editor, OS library, VM screen |
| `app/src/main/cpp` | `libmaple.so`: fork/exec with inherited file descriptors, thread affinity, framebuffer blit |
| `app/src/main/assets/os_catalog.json` | OS library entries (official URLs + SHA-256/512) |
| `native/qemu` | Script that cross-compiles QEMU for Android and installs it into the app |

## How it works

QEMU runs as a child process started from the app's native library directory (the binaries are
packaged as `lib*.so` so Android extracts them and allows them to be executed). The app talks to
it over two unix sockets: VNC for display, input and sound, and QMP for control (pause, network
cable, saved states, power button). Files picked with the system file chooser are handed to
QEMU as inherited file descriptors (`-add-fd` / `/dev/fdset`), so disk images are used in place
without copying.

## Build

```bash
native/qemu/build.sh
```

```bash
./gradlew assembleDebug
```

The first command builds QEMU and its firmware into `app/src/main/jniLibs/arm64-v8a` and
`app/src/main/assets/qemu` (see `native/qemu/README.md`). It is only needed once. The APK is
written to `app/build/outputs/apk/debug/app-debug.apk`. Requirements: JDK 17, Android SDK
platform 36, NDK 28.2, CMake 3.22.

Target: Android 10+ (API 29), arm64-v8a.

## Licensing

QEMU is licensed under the GNU GPL version 2. The exact upstream sources and patches used for
the bundled binaries are listed in `native/qemu/README.md`.
