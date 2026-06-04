# Nyandroid

GPU-accelerated terminal emulator for Android — an Android take on
[kitty](https://github.com/kovidgoyal/kitty). This repository is a
**hypothesis-validation PoC** that is built to grow into production code, not
to be thrown away.

## Hypotheses under test

1. **Android can host a kitty-style terminal.** Android 16 ships a built-in
   Linux Terminal (a full Debian VM on the Android Virtualization Framework),
   which proves the platform appetite. A *non-privileged* third-party app can't
   spin up an AVF VM (that needs `MANAGE_VIRTUAL_MACHINE`), so the PoC instead
   runs a local PTY (Termux-style) behind a swappable backend seam.
2. **Glyphs can be rendered on the GPU**, kitty-style: rasterise each unique
   glyph once into a texture atlas, then draw the whole screen as one instanced
   draw call where each cell references its sprite.
3. **Pixel is enough for now.** We target arm64 Pixel devices on Android 16;
   broadening device/ABI support is a later concern.

## Architecture

```
 PTY (C/JNI)  ──►  TerminalBackend  ──►  TerminalEmulator  ──►  GpuBackend
 forkpty()        (LocalPtyBackend)      (VtParser+Grid)        (GlesGpuBackend)
                        ▲                      │                      ▲
                   swappable:            FrameSnapshot          swappable:
                   SSH / AVF VM         (lock-free read)        Vulkan backend
```

Three deliberate seams keep the design open:

| Seam | Interface | PoC implementation | Future |
|------|-----------|--------------------|--------|
| Backend | `TerminalBackend` | `LocalPtyBackend` (forkpty + `/system/bin/sh`) | SSH, Android 16 AVF VM (`AvfVmBackend` stub) |
| Rendering | `GpuBackend` | `GlesGpuBackend` (OpenGL ES 3.2) | Vulkan |
| Emulation | `TerminalEmulator` | `VtParser` + `TerminalGrid` | wide chars, sixel/kitty graphics |

### Rendering pipeline (kitty-inspired)

- `GlyphRasterizer` rasterises a `(codepoint, bold, italic)` into a one-cell
  coverage bitmap using Android's text stack.
- `GlyphAtlas` caches each sprite in a single `GL_R8` texture. Because the grid
  is monospace, the atlas is a fixed grid of slots — no rectangle packer.
- `GlesGpuBackend` emits one instanced unit quad per cell carrying grid
  position, fg/bg colour and the glyph UV rect. A single
  `glDrawArraysInstanced` paints the screen; the fragment shader blends glyph
  coverage over the cell background in one pass.
- `EglCore` owns EGL by hand (not `GLSurfaceView`) so the Vulkan backend can own
  its surface the same way.

### Threading

- **PTY reader thread** → `TerminalEmulator.feed()` (parses under a lock).
- **Render thread** (`RenderThread`, a `HandlerThread`) → pulls a
  `FrameSnapshot` and calls `GpuBackend.renderFrame()`. Render requests are
  coalesced.
- **UI thread** → input events → `KeyEncoder` → backend.

## Terminal features implemented

C0 controls; UTF-8 (incremental); CSI cursor move / erase (ED/EL) / insert &
delete lines & chars / scroll regions (DECSTBM); SGR (16/256/truecolour, bold,
italic, underline, reverse); DEC private modes for cursor visibility (DECTCEM)
and the alternate screen; OSC strings (consumed); DSR / cursor-position report.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the longer tour and the
roadmap toward kitty parity.

## Building

Requires the Android SDK (API 36) + NDK and a Pixel-class arm64 device/emulator
on Android 16.

```bash
./gradlew :app:assembleDebug      # build the dev APK
./gradlew :app:test               # run the JVM emulator tests
```

> The emulator core (`dev.nyandroid.terminal.emulator`) is pure Kotlin/JVM and
> is unit-tested without a device; the GPU/PTY layers need real hardware.

### (Future) Android 16 Linux VM backend

The PoC runs a local PTY and needs no special permissions. The groundwork for a
VM backend is in place (`AvfVmBackend` stub + manifest declaration). On a
personal device the management permission can be granted without platform
signing because it carries the `development` protection flag:

```bash
adb shell pm grant dev.nyandroid.terminal android.permission.MANAGE_VIRTUAL_MACHINE
```

Caveats: booting a *custom* full-Linux VM (own kernel/rootfs) also needs
`USE_CUSTOM_VIRTUAL_MACHINE`, which is restricted to debuggable/rooted builds,
and the AVF API (`android.system.virtualmachine.*`) is a `@SystemApi`. The
lower-friction path is to attach to the VM the stock Terminal already runs
(SSH / vsock) — see `docs/ARCHITECTURE.md`.

## Status

PoC. The VT core is verified by JVM tests; the GPU and PTY layers are written
for an on-device build (they require the Android SDK/NDK, which CI/dev machines
must provide).
