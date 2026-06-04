# Architecture

This document explains how Nyandroid is put together and why, and tracks the
road from PoC toward kitty parity.

## Goals & non-goals (PoC)

**Goals**
- Prove a kitty-style GPU rendering pipeline works on Android (Pixel / Android 16).
- Run a real interactive shell through a proper VT emulator.
- Keep the backend (what runs), the renderer (which GPU API) and the emulator
  cleanly separated so none of the three forces a rewrite of the others.

**Non-goals (for now)**
- Privileged AVF VM integration (needs `MANAGE_VIRTUAL_MACHINE`).
- Non-Pixel devices / ABIs other than `arm64-v8a`.
- Wide (CJK double-width) cells, ligatures, sixel / kitty graphics protocol.

## Layered design

### 1. Backend — `TerminalBackend`

Source/sink of raw bytes. The PoC's `LocalPtyBackend` calls into a tiny C
helper (`app/src/main/cpp/pty.c`) that uses bionic's `forkpty()` to spawn
`/system/bin/sh` on a PTY, then streams bytes on a dedicated reader thread.

The interface is intentionally byte-oriented and transport-agnostic so the same
emulator/renderer can later sit on top of:
- an **SSH** connection, or
- the **Android 16 Linux VM** (Debian on AVF) — the most "kitty on a real
  Linux" experience, gated behind privileged APIs.

### 2. Emulator — `TerminalEmulator` (`VtParser` + `TerminalGrid`)

Pure Kotlin, no Android dependencies (so it is unit-testable on the JVM).

- `TerminalGrid` is the screen model: parallel primitive arrays
  (`row*cols+col`) of codepoint / fg / bg / style. It implements deferred wrap,
  scroll regions, and a primary + alternate screen.
- `VtParser` is a byte-driven state machine (GROUND / ESCAPE / CSI / OSC …) with
  incremental UTF-8 decoding.
- `TerminalEmulator` owns the single lock that serialises writes (from the PTY
  thread) against `snapshot()` reads (from the render thread), and produces a
  `FrameSnapshot` with reverse-video and the block cursor already baked in.

### 3. Renderer — `GpuBackend`

`GlesGpuBackend` (OpenGL ES 3.2) implements the kitty sprite model:

1. `GlyphRasterizer` draws a glyph into a one-cell `ALPHA_8` bitmap.
2. `GlyphAtlas` uploads it into a `GL_R8` texture once and caches the UV rect.
3. Each frame, every cell becomes one instance `{gridPos, uvRect, fg, bg}`; the
   whole screen is a single `glDrawArraysInstanced` over a unit quad.
4. The fragment shader computes `mix(bg, fg, coverage)` — background fill and
   glyph in one pass.

`EglCore` sets up EGL/GLES by hand against a raw `Surface`. This is the key to
GPU-API swappability: a `VulkanGpuBackend` implements the same `GpuBackend`
interface against the same `Surface`, and nothing above changes.

### Threading model

| Thread | Responsibility |
|--------|----------------|
| PTY reader | `nativeRead` → `TerminalEmulator.feed()` |
| Render (`HandlerThread`) | owns the GL context; `snapshot()` → `renderFrame()` |
| UI | surface lifecycle + input → `KeyEncoder` → backend |

Render requests are coalesced (`AtomicBoolean`), so a burst of shell output
results in at most one queued frame — output throughput never floods the GPU.

## Why a raw SurfaceView (not GLSurfaceView)

`GLSurfaceView` hard-wires OpenGL. Using a plain `SurfaceView` + our own
`EglCore` + render thread means the rendering API lives entirely behind
`GpuBackend`, satisfying the "make Vulkan swap-in possible" requirement.

## Roadmap to kitty parity

Rough order of value:

1. **Scrollback buffer** + touch/fling scrolling.
2. **Wide characters** (CJK width 2) and combining marks.
3. **Cursor styles** (beam/underline, blink) and selection / clipboard.
4. **Ligatures** and a bundled Nerd Font.
5. **Graphics**: sixel, then the kitty graphics protocol.
6. **Vulkan backend** behind `GpuBackend` for a perf comparison.
7. **AVF VM backend** for a full Linux userland on supported Pixels.

## Verification

`./gradlew :app:test` runs `TerminalEmulatorTest` (CRLF, cursor addressing,
SGR, erase, deferred wrap, DSR, cross-feed UTF-8). The emulator core was also
exercised standalone during bring-up; the GPU/PTY layers require on-device runs.
