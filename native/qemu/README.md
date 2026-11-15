# QEMU for Maple (Android arm64-v8a)

`build.sh` cross-compiles QEMU with the Android NDK and installs it into the app:

| Output | What |
| --- | --- |
| `app/src/main/jniLibs/arm64-v8a/libqemu-system-x86_64.so` | `qemu-system-x86_64` (PIE executable) |
| `app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so` | `qemu-system-aarch64` (PIE executable) |
| `app/src/main/jniLibs/arm64-v8a/libqemu-img.so` | `qemu-img` (PIE executable) |
| `app/src/main/assets/qemu/` | firmware, option ROMs, `keymaps/` — pass with `-L <dir>` |

The binaries are executables named `lib*.so` so that Android extracts them to
`nativeLibraryDir` and allows `exec()`. They link dynamically only against
`libc`, `libm`, `libdl` and `libz`; everything else is static. They are stripped
and 16 KB page-size compatible.

## Rebuild

```sh
native/qemu/build.sh            # incremental; finished steps are skipped
native/qemu/build.sh --clean    # rebuild everything (keeps downloads + venv)
```

Requirements on the build host (macOS or Linux): Android NDK r28
(`ANDROID_NDK_HOME`, default `~/Library/Android/sdk/ndk/28.2.13676358`),
`python3`, `pkg-config`, `curl`, `make`, `patch`. meson and ninja are installed
into a private venv. Everything is kept under `native/qemu/build/` (git-ignore it).
A full build takes roughly 15-20 minutes on an 8-core Apple Silicon Mac.

Target: `aarch64-linux-android`, API 29 (`ANDROID_API` to override).

## Versions and upstream sources

All tarballs are SHA256-pinned in `build.sh`.

| Component | Version | Licence | Source |
| --- | --- | --- | --- |
| QEMU | 11.1.3 | GPL-2.0 (parts LGPL/BSD/MIT) | https://download.qemu.org/qemu-11.1.3.tar.xz |
| GLib | 2.90.1 | LGPL-2.1-or-later | https://download.gnome.org/sources/glib/2.90/glib-2.90.1.tar.xz |
| pixman | 0.46.4 | MIT | https://www.cairographics.org/releases/pixman-0.46.4.tar.xz |
| libslirp | 4.9.7 | BSD-3-Clause | https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.9.7/libslirp-v4.9.7.tar.bz2 |
| libffi | 3.8.1 | MIT | https://github.com/libffi/libffi/releases/download/v3.8.1/libffi-3.8.1.tar.gz |
| PCRE2 | 10.49 | BSD-3-Clause | https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.49/pcre2-10.49.tar.bz2 |
| proxy-libintl | 0.5 | LGPL-2.0-or-later | https://github.com/frida/proxy-libintl/archive/refs/tags/0.5.tar.gz |
| libfdt (dtc) | 1.8.1 | BSD-2-Clause / GPL-2.0-or-later (dual) | https://www.kernel.org/pub/software/utils/dtc/dtc-1.8.1.tar.xz |
| bionic setjmp | AOSP, via termux-packages | BSD-2-Clause | `patches/setjmp-aarch64/` |
| zlib | NDK sysroot (dynamic, `libz.so`) | Zlib | — |

Checksum provenance: GLib and pixman match the checksum files published next to
the tarballs; dtc matches kernel.org `sha256sums.asc`; QEMU matches the hash
pinned by termux-packages (the detached `.sig` was not verified — no gpg on the
build host). libffi, PCRE2, libslirp and proxy-libintl publish no checksum file;
their hashes are pinned from the first download (proxy-libintl matches the hash
in GLib's own wrap file).

### Licensing note for the app

QEMU is GPL-2.0. An APK that ships these binaries must carry the GPL-2.0
licence text and offer the corresponding source: the upstream tarballs listed
above **plus** the contents of `native/qemu/` (`build.sh` and `patches/`).
GLib and proxy-libintl are LGPL and statically linked into a GPL program, which
is permitted. The bundled firmware has its own licences: SeaBIOS/SeaVGABIOS
(LGPL-3.0), iPXE option ROMs (GPL-2.0), EDK2 (`edk2-licenses.txt` is copied
into the assets). The firmware sources are in the QEMU tarball under `roms/`.

## Patches

`patches/qemu/0001-android-portability.patch`
- `meson.build`: do not require `librt` (bionic has none); do not use
  `signalfd()` (QEMU's thread-based fallback is used, as Termux does); drop the
  ivshmem tools.
- `util/oslib-posix.c`: bionic has no `shm_open()`/`shm_unlink()`; emulate them
  with files under `$TMPDIR`.
- `block.c`: temporary images (`-snapshot`, `fat:rw:`) go to `$TMPDIR`; the
  hard-coded `/var/tmp` fallback becomes `/data/local/tmp`.
- `block/file-posix.c`: no `statx()` (bionic API 30+), and never issue the raw
  `copy_file_range` syscall (not on the app seccomp allow-list of older Android
  releases, would kill the process with SIGSYS).
- `system/vl.c`: disable bionic heap pointer tagging at start-up (QEMU crashes
  with tagged pointers on Android 12+; Termux patch 0016).

`patches/qemu/0002-maple-vnc-refresh-env.patch`
- `ui/vnc.c`: `VNC_REFRESH_INTERVAL_BASE` is read once from
  `MAPLE_VNC_REFRESH_MS` (integer ms, clamped to 4..100, default 30).
  The adaptive idle back-off ceiling `VNC_REFRESH_INTERVAL_MAX` is read from
  `MAPLE_VNC_REFRESH_MAX_MS` (clamped to base..3000, default 3000 = upstream).
  The VNC display listener's interval is what drives the console refresh timer
  and therefore `graphic_hw_update()` of the emulated adapter, so no other
  constant caps the guest display rate.

`patches/libslirp/0001-android-dns-resolv-conf.patch`
- Android has no `/etc/resolv.conf`. The path is taken from
  `SLIRP_RESOLV_CONF`; if that is unset/missing/empty, 8.8.8.8 and
  2001:4860:4860::8888 are used.

`patches/setjmp-aarch64/`
- bionic's arm64 `setjmp.S` without the cookie/checksum abort, linked
  statically so QEMU's sigaltstack coroutines do not go through libc's
  `siglongjmp()` (crashes on many arm64 devices, termux-packages #12261).

## Runtime contract for the launcher

- Always pass `-L <dir with the extracted assets>`.
- Set `TMPDIR` (and `HOME`) to app-private writable directories.
- Optional: `MAPLE_VNC_REFRESH_MS`, `MAPLE_VNC_REFRESH_MAX_MS`, `SLIRP_RESOLV_CONF`.
- The APK must extract native libs (`android:extractNativeLibs="true"` /
  `packaging.jniLibs.useLegacyPackaging = true`), otherwise the binaries are not
  on disk and cannot be exec'd.
