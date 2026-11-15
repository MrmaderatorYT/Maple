#!/usr/bin/env bash
#
# Cross-compiles QEMU (x86_64-softmmu, aarch64-softmmu, qemu-img) for
# Android arm64-v8a with the NDK and installs the results into the Maple app:
#
#   app/src/main/jniLibs/arm64-v8a/libqemu-system-x86_64.so
#   app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so
#   app/src/main/jniLibs/arm64-v8a/libqemu-img.so
#   app/src/main/assets/qemu/            (firmware + keymaps, for `-L`)
#
# Usage:  native/qemu/build.sh [--clean] [--jobs N]
#
# Everything (downloads, source trees, build trees, python venv) lives in
# native/qemu/build/.  The script is idempotent: finished steps are skipped
# until their version, patches or this script's build flags change.
#
# Environment overrides:
#   ANDROID_NDK_HOME   NDK root (default ~/Library/Android/sdk/ndk/28.2.13676358)
#   ANDROID_API        target API level (default 29)
#   JOBS               parallel jobs (default: number of CPUs)

set -euo pipefail

###############################################################################
# Versions and pinned SHA256 checksums
###############################################################################

QEMU_VER=11.1.3
QEMU_URL="https://download.qemu.org/qemu-${QEMU_VER}.tar.xz"
QEMU_SHA=5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22

GLIB_VER=2.90.1
GLIB_URL="https://download.gnome.org/sources/glib/${GLIB_VER%.*}/glib-${GLIB_VER}.tar.xz"
GLIB_SHA=93c941aa17d5eb1d53fe838365f29a8b4e539c222a256d974ec8f30fc413e396

PIXMAN_VER=0.46.4
PIXMAN_URL="https://www.cairographics.org/releases/pixman-${PIXMAN_VER}.tar.xz"
PIXMAN_SHA=a098c33924754ad43f981b740f6d576c70f9ed1006e12221b1845431ebce1239

LIBFFI_VER=3.8.1
LIBFFI_URL="https://github.com/libffi/libffi/releases/download/v${LIBFFI_VER}/libffi-${LIBFFI_VER}.tar.gz"
LIBFFI_SHA=81e879c094cc5ec3186c48510c6385ac6b3e866192e8bc71a35245703757ee40

PCRE2_VER=10.49
PCRE2_URL="https://github.com/PCRE2Project/pcre2/releases/download/pcre2-${PCRE2_VER}/pcre2-${PCRE2_VER}.tar.bz2"
PCRE2_SHA=53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384

LIBSLIRP_VER=4.9.7
LIBSLIRP_URL="https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v${LIBSLIRP_VER}/libslirp-v${LIBSLIRP_VER}.tar.bz2"
LIBSLIRP_SHA=363dfacf2821758c52388303b691093030b43ce8fd63142a55e878cbeb9a889c

# gettext stub used by glib (bionic has no libintl).
INTL_VER=0.5
INTL_URL="https://github.com/frida/proxy-libintl/archive/refs/tags/${INTL_VER}.tar.gz"
INTL_SHA=f7a1cbd7579baaf575c66f9d99fb6295e9b0684a28b095967cfda17857595303

# libfdt, required by the aarch64 "virt" machine.
DTC_VER=1.8.1
DTC_URL="https://www.kernel.org/pub/software/utils/dtc/dtc-${DTC_VER}.tar.xz"
DTC_SHA=23526015a6f1550e0541a53fe7acea1b5a11e3697cdf3a3bdc076abc38f6045d

# Host-side build tools installed into the private python venv.
MESON_VER=1.12.1
NINJA_VER=1.13.0

###############################################################################
# Paths and options
###############################################################################

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$HERE/build"
DL="$BUILD/dl"
WORK="$BUILD/work"          # extracted + patched source trees, build trees
PREFIX="$BUILD/prefix"      # static dependency install prefix
TOOLS="$BUILD/tools"        # generated wrappers / cross file
STAMPS="$BUILD/stamps"
VENV="$BUILD/venv"
STAGE="$BUILD/stage"        # `meson install` DESTDIR for QEMU
PATCHES="$HERE/patches"

OUT_LIBS="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
OUT_ASSETS="$PROJECT_ROOT/app/src/main/assets/qemu"

ANDROID_API="${ANDROID_API:-29}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
TRIPLE=aarch64-linux-android

if command -v sysctl >/dev/null 2>&1 && sysctl -n hw.ncpu >/dev/null 2>&1; then
    JOBS="${JOBS:-$(sysctl -n hw.ncpu)}"
else
    JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
fi

while [ $# -gt 0 ]; do
    case "$1" in
        --clean) rm -rf "$WORK" "$PREFIX" "$TOOLS" "$STAMPS" "$STAGE"; shift ;;
        --jobs)  JOBS="$2"; shift 2 ;;
        -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

log()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

###############################################################################
# Toolchain
###############################################################################

case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;   # universal binaries, also on arm64
    Linux)  NDK_HOST=linux-x86_64 ;;
    *) die "unsupported build host $(uname -s)" ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST"
SYSROOT="$TOOLCHAIN/sysroot"
[ -d "$TOOLCHAIN" ] || die "NDK toolchain not found at $TOOLCHAIN (set ANDROID_NDK_HOME)"

CC="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/${TRIPLE}${ANDROID_API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
NM="$TOOLCHAIN/bin/llvm-nm"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
READELF="$TOOLCHAIN/bin/llvm-readelf"
[ -x "$CC" ] || die "compiler not found: $CC"

# 16 KB page-size compatible binaries (required from Android 15 on).
PAGE_LDFLAGS="-Wl,-z,max-page-size=16384"
# Keep absolute build-host paths (assert()/__FILE__ strings) out of the binaries.
PATH_MAP_CFLAGS="-ffile-prefix-map=$BUILD/=/maple/"
DEP_CFLAGS="-O2 -fPIC $PATH_MAP_CFLAGS -I$PREFIX/include"
DEP_LDFLAGS="-L$PREFIX/lib $PAGE_LDFLAGS"

HOST_PKG_CONFIG="$(command -v pkg-config || command -v pkgconf || true)"
[ -n "$HOST_PKG_CONFIG" ] || die "pkg-config (or pkgconf) is required on the build host"
for t in python3 curl tar make patch shasum; do
    command -v "$t" >/dev/null 2>&1 || die "required host tool missing: $t"
done

mkdir -p "$DL" "$WORK" "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$TOOLS" "$STAMPS"

###############################################################################
# Helpers
###############################################################################

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

# fetch URL SHA256 -> prints nothing, leaves $DL/<basename>
fetch() {
    local url="$1" sha="$2" name="${3:-$(basename "$1")}" out
    out="$DL/$name"
    if [ -f "$out" ] && [ "$(sha256_of "$out")" = "$sha" ]; then
        info "have $name"
        return
    fi
    info "downloading $url"
    curl -fL --retry 3 --connect-timeout 30 -o "$out.part" "$url"
    local got; got="$(sha256_of "$out.part")"
    if [ "$got" != "$sha" ]; then
        rm -f "$out.part"
        die "SHA256 mismatch for $name: expected $sha, got $got"
    fi
    mv "$out.part" "$out"
}

# extract TARBALL DIRNAME -> fresh tree at $WORK/DIRNAME
extract() {
    local tarball="$1" dir="$2"
    rm -rf "${WORK:?}/$dir" "$WORK/.x"
    mkdir -p "$WORK/.x"
    tar -xf "$DL/$tarball" -C "$WORK/.x"
    mv "$WORK/.x"/* "$WORK/$dir"
    rmdir "$WORK/.x"
}

apply_patches() {
    local tree="$1" pdir="$2" p
    [ -d "$pdir" ] || return 0
    for p in "$pdir"/*.patch; do
        [ -f "$p" ] || continue
        info "applying $(basename "$p")"
        patch -d "$tree" -p1 -N -s < "$p"
    done
}

# A step is re-run when its key (version + patches + relevant flags) changes.
BUILD_KEY_COMMON="api=$ANDROID_API ndk=$(basename "$ANDROID_NDK_HOME") cflags=$DEP_CFLAGS ldflags=$DEP_LDFLAGS"
step_key() {
    local name="$1"; shift
    {
        echo "$BUILD_KEY_COMMON"
        echo "$@"
        if [ -d "$PATCHES/$name" ]; then
            find "$PATCHES/$name" -type f | LC_ALL=C sort | while read -r f; do sha256_of "$f"; done
        fi
    } | shasum -a 256 | awk '{print $1}'
}
step_done()  { [ -f "$STAMPS/$1" ] && [ "$(cat "$STAMPS/$1")" = "$2" ]; }
step_mark()  { echo "$2" > "$STAMPS/$1"; }

###############################################################################
# 0. Host tools: meson + ninja in a private venv, pkg-config wrapper, cross file
###############################################################################

log "Host tools"
if [ ! -x "$VENV/bin/meson" ] || [ ! -x "$VENV/bin/ninja" ] \
   || [ "$("$VENV/bin/meson" --version 2>/dev/null)" != "$MESON_VER" ]; then
    info "creating python venv with meson $MESON_VER / ninja $NINJA_VER"
    rm -rf "$VENV"
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --quiet --disable-pip-version-check \
        "meson==$MESON_VER" "ninja==$NINJA_VER" packaging
fi
MESON="$VENV/bin/meson"
NINJA="$VENV/bin/ninja"
# meson locates ninja through PATH (or $NINJA); make the venv's copy visible.
export PATH="$VENV/bin:$PATH"
export NINJA
info "meson $("$MESON" --version), ninja $("$NINJA" --version), python $("$VENV/bin/python3" -V | awk '{print $2}')"

# pkg-config that only sees our static prefix and always resolves private
# (static) dependencies, so that glib & co. are linked statically into QEMU.
cat > "$TOOLS/pkg-config" <<EOF
#!/bin/sh
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
unset PKG_CONFIG_PATH PKG_CONFIG_SYSROOT_DIR
exec "$HOST_PKG_CONFIG" --static "\$@"
EOF
chmod +x "$TOOLS/pkg-config"

# zlib comes from the NDK sysroot (libz.so is a public Android system library).
cat > "$PREFIX/lib/pkgconfig/zlib.pc" <<EOF
prefix=$SYSROOT/usr
Name: zlib
Description: zlib from the Android NDK sysroot
Version: 1.3.1
Libs: -lz
Cflags:
EOF

meson_list() { local out="" a; for a in "$@"; do out="$out'$a', "; done; echo "[${out%, }]"; }
# shellcheck disable=SC2086
cat > "$TOOLS/android-arm64.cross" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
nm = '$NM'
ranlib = '$RANLIB'
strip = '$STRIP'
objcopy = '$OBJCOPY'
pkg-config = '$TOOLS/pkg-config'

[built-in options]
c_args = $(meson_list $DEP_CFLAGS)
c_link_args = $(meson_list $DEP_LDFLAGS)
cpp_args = $(meson_list $DEP_CFLAGS)
cpp_link_args = $(meson_list $DEP_LDFLAGS)

[properties]
needs_exe_wrapper = true
# glib run-time checks that cannot be executed when cross compiling
have_c99_vsnprintf = true
have_c99_snprintf = true
have_unix98_printf = true
have_strlcpy = true
growing_stack = false
va_val_copy = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

meson_dep() {   # meson_dep SRCDIR [options...]
    local src="$1"; shift
    rm -rf "$src/_build"
    "$MESON" setup "$src/_build" "$src" \
        --cross-file "$TOOLS/android-arm64.cross" \
        --prefix "$PREFIX" --libdir lib \
        --buildtype release --default-library static \
        --wrap-mode nodownload "$@" > "$src/_meson-setup.log" 2>&1 \
        || { tail -40 "$src/_meson-setup.log" >&2; die "meson setup failed for $src"; }
    "$NINJA" -C "$src/_build" -j "$JOBS" > "$src/_ninja.log" 2>&1 \
        || { tail -60 "$src/_ninja.log" >&2; die "build failed for $src (see $src/_ninja.log)"; }
    "$MESON" install -C "$src/_build" --no-rebuild > "$src/_install.log" 2>&1 \
        || { tail -40 "$src/_install.log" >&2; die "install failed for $src"; }
}

autotools_dep() {   # autotools_dep SRCDIR [configure options...]
    local src="$1"; shift
    ( cd "$src"
      ./configure --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
          --disable-shared --enable-static \
          CC="$CC" CXX="$CXX" AR="$AR" NM="$NM" RANLIB="$RANLIB" STRIP="$STRIP" \
          CFLAGS="$DEP_CFLAGS" LDFLAGS="$DEP_LDFLAGS" "$@" > _configure.log 2>&1 \
          || { tail -40 _configure.log >&2; die "configure failed for $src"; }
      make -j "$JOBS" > _make.log 2>&1 || { tail -60 _make.log >&2; die "make failed for $src"; }
      make install > _install.log 2>&1 || { tail -40 _install.log >&2; die "install failed for $src"; }
    )
}

###############################################################################
# 1. Downloads
###############################################################################

log "Sources"
fetch "$QEMU_URL"     "$QEMU_SHA"
fetch "$GLIB_URL"     "$GLIB_SHA"
fetch "$PIXMAN_URL"   "$PIXMAN_SHA"
fetch "$LIBFFI_URL"   "$LIBFFI_SHA"
fetch "$PCRE2_URL"    "$PCRE2_SHA"
fetch "$LIBSLIRP_URL" "$LIBSLIRP_SHA"
fetch "$INTL_URL"     "$INTL_SHA" "proxy-libintl-${INTL_VER}.tar.gz"
fetch "$DTC_URL"      "$DTC_SHA"

###############################################################################
# 2. Static dependencies
###############################################################################

key="$(step_key libffi "$LIBFFI_VER")"
if step_done libffi "$key"; then log "libffi $LIBFFI_VER (cached)"; else
    log "libffi $LIBFFI_VER"
    extract "libffi-${LIBFFI_VER}.tar.gz" libffi
    autotools_dep "$WORK/libffi" --disable-docs --disable-multi-os-directory
    step_mark libffi "$key"
fi

key="$(step_key pcre2 "$PCRE2_VER")"
if step_done pcre2 "$key"; then log "pcre2 $PCRE2_VER (cached)"; else
    log "pcre2 $PCRE2_VER"
    extract "pcre2-${PCRE2_VER}.tar.bz2" pcre2
    autotools_dep "$WORK/pcre2" --enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32 \
        --enable-unicode --disable-jit --disable-pcre2grep-libz --disable-pcre2grep-libbz2 \
        --disable-pcre2test-libreadline --disable-pcre2test-libedit
    step_mark pcre2 "$key"
fi

key="$(step_key proxy-libintl "$INTL_VER")"
if step_done proxy-libintl "$key"; then log "proxy-libintl $INTL_VER (cached)"; else
    log "proxy-libintl $INTL_VER"
    extract "proxy-libintl-${INTL_VER}.tar.gz" proxy-libintl
    meson_dep "$WORK/proxy-libintl"
    step_mark proxy-libintl "$key"
fi

GLIB_OPTS_REV=2   # bump when the glib meson options below change
key="$(step_key glib "$GLIB_VER" "$LIBFFI_VER" "$PCRE2_VER" "$INTL_VER" "$GLIB_OPTS_REV")"
GLIB_KEY="$key"
if step_done glib "$key"; then log "glib $GLIB_VER (cached)"; else
    log "glib $GLIB_VER"
    extract "glib-${GLIB_VER}.tar.xz" glib
    apply_patches "$WORK/glib" "$PATCHES/glib"
    meson_dep "$WORK/glib" \
        -Dtests=false -Dinstalled_tests=false -Dnls=disabled -Dselinux=disabled \
        -Dxattr=false -Dlibmount=disabled -Dman-pages=disabled -Ddocumentation=false \
        -Ddtrace=disabled -Dsystemtap=disabled -Dsysprof=disabled -Dlibelf=disabled \
        -Dintrospection=disabled -Dglib_debug=disabled -Dglib_assert=false \
        -Dglib_checks=false -Dfile_monitor_backend=auto \
        --localedir=/usr/share/locale -Dcharsetalias_dir=/usr/lib   # keep build-host paths out of libglib
    step_mark glib "$key"
fi

key="$(step_key pixman "$PIXMAN_VER")"
if step_done pixman "$key"; then log "pixman $PIXMAN_VER (cached)"; else
    log "pixman $PIXMAN_VER"
    extract "pixman-${PIXMAN_VER}.tar.xz" pixman
    meson_dep "$WORK/pixman" -Dtests=disabled -Ddemos=disabled -Dgtk=disabled \
        -Dlibpng=disabled -Dopenmp=disabled -Dgnuplot=false
    step_mark pixman "$key"
fi

key="$(step_key libslirp "$LIBSLIRP_VER" "$GLIB_KEY")"
LIBSLIRP_KEY="$key"
if step_done libslirp "$key"; then log "libslirp $LIBSLIRP_VER (cached)"; else
    log "libslirp $LIBSLIRP_VER"
    extract "libslirp-v${LIBSLIRP_VER}.tar.bz2" libslirp
    apply_patches "$WORK/libslirp" "$PATCHES/libslirp"
    meson_dep "$WORK/libslirp"
    step_mark libslirp "$key"
fi

key="$(step_key dtc "$DTC_VER")"
if step_done dtc "$key"; then log "libfdt (dtc $DTC_VER) (cached)"; else
    log "libfdt (dtc $DTC_VER)"
    extract "dtc-${DTC_VER}.tar.xz" dtc
    meson_dep "$WORK/dtc" -Dtools=false -Dyaml=disabled -Dvalgrind=disabled \
        -Dpython=disabled -Dtests=false
    step_mark dtc "$key"
fi

# setjmp/longjmp replacement for the sigaltstack coroutine backend.  bionic's
# own siglongjmp() validates a per-process cookie / PAC-signed frame and
# aborts (SIGILL/SIGABRT) on arm64 devices when QEMU switches coroutine
# stacks (termux-packages issue #12261).  Source: bionic, via termux-packages.
key="$(step_key setjmp-aarch64 1)"
if step_done setjmp-aarch64 "$key"; then log "android-setjmp (cached)"; else
    log "android-setjmp"
    rm -rf "$WORK/setjmp"; mkdir -p "$WORK/setjmp/private"
    cp "$PATCHES/setjmp-aarch64/setjmp.S" "$WORK/setjmp/"
    for h in bionic_asm.h bionic_asm_arm64.h bionic_constants.h; do
        cp "$PATCHES/setjmp-aarch64/private-$h" "$WORK/setjmp/private/$h"
    done
    ( cd "$WORK/setjmp"
      "$CC" -O2 -fPIC -I. -c setjmp.S -o setjmp.o
      rm -f "$PREFIX/lib/libandroid-setjmp.a"
      "$AR" crs "$PREFIX/lib/libandroid-setjmp.a" setjmp.o )
    step_mark setjmp-aarch64 "$key"
fi

###############################################################################
# 3. QEMU
###############################################################################

QEMU_SRC="$WORK/qemu"
QEMU_BUILD="$WORK/qemu-build"

QEMU_CONFIGURE_ARGS=(
    --prefix=/usr
    --target-list=x86_64-softmmu,aarch64-softmmu
    --cross-prefix="${TRIPLE}-"
    --cc="$CC"
    --cxx="$CXX"
    --host-cc=cc
    --cpu=aarch64
    --python="$VENV/bin/python3"
    --ninja="$NINJA"
    --extra-cflags="-O2 $PATH_MAP_CFLAGS -I$PREFIX/include"
    --extra-ldflags="-L$PREFIX/lib $PAGE_LDFLAGS -l:libandroid-setjmp.a"
    --without-default-features
    --enable-system
    --enable-tcg
    --enable-tools
    --enable-pie
    --enable-slirp
    --enable-vnc
    --enable-pixman
    --enable-fdt=system
    --enable-bochs
    --enable-dmg
    --enable-parallels
    --enable-qcow1
    --enable-qed
    --enable-vdi
    --enable-vhdx
    --enable-vmdk
    --enable-vpc
    --enable-vvfat
    --enable-stack-protector
    --with-coroutine=sigaltstack
    --enable-trace-backends=nop
    --audio-drv-list=
    --disable-debug-info
    --disable-qom-cast-debug
    --disable-werror
    --disable-docs
    --disable-kvm
    --disable-hvf
    --disable-xen
    --disable-sdl
    --disable-gtk
    --disable-opengl
    --disable-virglrenderer
    --disable-spice
    --disable-vde
    --disable-curl
    --disable-gnutls
    --disable-nettle
    --disable-gcrypt
    --disable-capstone
    --disable-guest-agent
    --disable-linux-user
    --disable-bsd-user
    --disable-libusb
    --disable-usb-redir
    --disable-selinux
    --disable-vnc-sasl
    --disable-vnc-jpeg
    --disable-png
    --disable-rust
    --disable-plugins
    --disable-modules
)

key="$(step_key qemu "$QEMU_VER" "$GLIB_KEY" "$PIXMAN_VER" "$LIBSLIRP_KEY" "$DTC_VER" \
        "${QEMU_CONFIGURE_ARGS[*]}" "$(step_key setjmp-aarch64 1)")"
if step_done qemu "$key"; then log "QEMU $QEMU_VER (cached)"; else
    log "QEMU $QEMU_VER: extract + patch"
    extract "qemu-${QEMU_VER}.tar.xz" qemu
    apply_patches "$QEMU_SRC" "$PATCHES/qemu"

    log "QEMU $QEMU_VER: configure"
    rm -rf "$QEMU_BUILD"; mkdir -p "$QEMU_BUILD"
    ( cd "$QEMU_BUILD"
      env AR="$AR" NM="$NM" RANLIB="$RANLIB" STRIP="$STRIP" OBJCOPY="$OBJCOPY" \
          PKG_CONFIG="$TOOLS/pkg-config" \
          "$QEMU_SRC/configure" "${QEMU_CONFIGURE_ARGS[@]}" > configure.log 2>&1 \
          || { tail -60 configure.log >&2; die "QEMU configure failed (see $QEMU_BUILD/configure.log)"; }
    )
    sed -n '/^qemu /,$p' "$QEMU_BUILD/configure.log" > "$BUILD/qemu-configure-summary.txt" || true

    log "QEMU $QEMU_VER: build (-j$JOBS, this takes a while)"
    # macOS ships GNU make 3.81, too old for QEMU's Makefile; drive ninja directly.
    "$NINJA" -C "$QEMU_BUILD" -j "$JOBS" > "$QEMU_BUILD/ninja.log" 2>&1 \
        || { grep -B2 -A30 -m3 'FAILED' "$QEMU_BUILD/ninja.log" >&2 || tail -60 "$QEMU_BUILD/ninja.log" >&2
             die "QEMU build failed (see $QEMU_BUILD/ninja.log)"; }

    log "QEMU $QEMU_VER: stage install"
    rm -rf "$STAGE"
    DESTDIR="$STAGE" "$QEMU_BUILD/pyvenv/bin/meson" install -C "$QEMU_BUILD" --no-rebuild \
        > "$QEMU_BUILD/install.log" 2>&1 \
        || { tail -40 "$QEMU_BUILD/install.log" >&2; die "QEMU install failed"; }
    step_mark qemu "$key"
fi

###############################################################################
# 4. Install into the app
###############################################################################

log "Installing binaries -> ${OUT_LIBS#"$PROJECT_ROOT"/}"
mkdir -p "$OUT_LIBS"
install_bin() {   # install_bin STAGED_NAME OUTPUT_NAME
    local src="$STAGE/usr/bin/$1" dst="$OUT_LIBS/$2"
    [ -f "$src" ] || die "missing build output $src"
    "$STRIP" --strip-all -o "$dst.tmp" "$src"
    chmod 755 "$dst.tmp"
    mv -f "$dst.tmp" "$dst"
}
install_bin qemu-system-x86_64  libqemu-system-x86_64.so
install_bin qemu-system-aarch64 libqemu-system-aarch64.so
install_bin qemu-img            libqemu-img.so

log "Installing firmware -> ${OUT_ASSETS#"$PROJECT_ROOT"/}"
SHARE="$STAGE/usr/share/qemu"
[ -d "$SHARE" ] || die "missing staged data dir $SHARE"
rm -rf "$OUT_ASSETS"
mkdir -p "$OUT_ASSETS"
FIRMWARE=(
    bios-256k.bin bios.bin
    # QEMU >= 11 no longer ships linuxboot.bin / multiboot.bin (only the *_dma variants)
    kvmvapic.bin linuxboot_dma.bin multiboot_dma.bin pvh.bin
    efi-e1000.rom efi-e1000e.rom efi-rtl8139.rom efi-pcnet.rom efi-ne2k_pci.rom efi-virtio.rom
    edk2-x86_64-code.fd edk2-i386-vars.fd edk2-aarch64-code.fd edk2-arm-vars.fd
    edk2-licenses.txt
)
for f in "${FIRMWARE[@]}"; do
    [ -f "$SHARE/$f" ] || die "firmware file missing from QEMU install: $f"
    cp "$SHARE/$f" "$OUT_ASSETS/$f"
done
cp "$SHARE"/vgabios*.bin "$OUT_ASSETS/"
cp -R "$SHARE/keymaps" "$OUT_ASSETS/keymaps"
chmod -R u+rw,go+r "$OUT_ASSETS"

###############################################################################
# 5. Static verification of the outputs
###############################################################################

log "Verifying outputs"
ALLOWED_NEEDED=" libc.so libm.so libdl.so liblog.so libz.so "
for b in libqemu-system-x86_64.so libqemu-system-aarch64.so libqemu-img.so; do
    f="$OUT_LIBS/$b"
    # Capture first: `readelf | grep -q` trips pipefail via SIGPIPE.
    hdr="$("$READELF" -h "$f")"
    dyn="$("$READELF" -d "$f")"
    segs="$("$READELF" -lW "$f")"
    syms="$("$READELF" --dyn-syms -W "$f")"
    grep -q 'Machine:.*AArch64' <<<"$hdr"   || die "$b: not an AArch64 ELF"
    grep -q 'Type:.*DYN' <<<"$hdr"          || die "$b: not a PIE (ET_DYN)"
    grep -q 'FLAGS_1.*PIE' <<<"$dyn"        || die "$b: DF_1_PIE not set"
    grep -q 'INTERP' <<<"$segs"             || die "$b: no PT_INTERP (not an executable)"
    needed="$(sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' <<<"$dyn" | tr '\n' ' ')"
    for n in $needed; do
        case "$ALLOWED_NEEDED" in *" $n "*) ;; *) die "$b: unexpected dynamic dependency $n" ;; esac
    done
    awk '$1=="LOAD" && $NF!="0x4000" {bad=1} END {exit bad}' <<<"$segs" \
        || die "$b: LOAD segment not aligned to 16 KB"
    if grep -Eq ' UND (sig)?(_?setjmp|_?longjmp)' <<<"$syms"; then
        die "$b: setjmp/longjmp still imported from libc"
    fi
    size="$(wc -c < "$f" | tr -d ' ')"
    printf '    %-28s %9d bytes  NEEDED: %s\n' "$b" "$size" "$needed"
done
info "assets: $(find "$OUT_ASSETS" -type f | wc -l | tr -d ' ') files, $(du -sh "$OUT_ASSETS" | awk '{print $1}')"

log "Done. QEMU $QEMU_VER for Android arm64-v8a (API $ANDROID_API) is installed."
