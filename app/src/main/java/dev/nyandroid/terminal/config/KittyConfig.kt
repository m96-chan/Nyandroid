package dev.nyandroid.terminal.config

import android.content.Context
import dev.nyandroid.terminal.emulator.ColorScheme
import dev.nyandroid.terminal.emulator.TerminalColors
import java.io.File

/**
 * Parses a kitty.conf-compatible configuration file.
 *
 * Supports key-value pairs, comments (#), and a subset of kitty settings:
 * fonts, cursor, scrollback, colors, and bell.
 */
class KittyConfig private constructor(
    private val entries: Map<String, String>,
    /** Key bindings parsed from `map` directives. */
    val keyBindings: List<KeyBinding> = emptyList(),
) {

    // --- Font ----------------------------------------------------------------
    val fontFamily: String get() = get("font_family", "JetBrains Mono Nerd Font")
    val fontSize: Float get() = get("font_size", "14").toFloatOrNull() ?: 14f
    val boldFont: String get() = get("bold_font", "auto")
    val italicFont: String get() = get("italic_font", "auto")

    // --- Cursor --------------------------------------------------------------
    val cursorShape: String get() = get("cursor_shape", "block")
    val cursorBlinkInterval: Float get() = get("cursor_blink_interval", "0.5").toFloatOrNull() ?: 0.5f

    // --- Scrollback ----------------------------------------------------------
    val scrollbackLines: Int get() = get("scrollback_lines", "10000").toIntOrNull() ?: 10000

    // --- Bell ----------------------------------------------------------------
    val enableAudioBell: Boolean get() = get("enable_audio_bell", "no") == "yes"
    val visualBellDuration: Float get() = get("visual_bell_duration", "0").toFloatOrNull() ?: 0f
    val visualBellColor: String? get() = entries["visual_bell_color"]

    // --- Window --------------------------------------------------------------
    val windowPaddingWidth: Int get() = get("window_padding_width", "0").toIntOrNull() ?: 0

    // --- Tab bar -------------------------------------------------------------
    val tabBarEdge: String get() = get("tab_bar_edge", "top")
    val tabBarStyle: String get() = get("tab_bar_style", "separator")

    // --- Window / rendering --------------------------------------------------
    /** kitty `background_opacity` (0.0-1.0). */
    val backgroundOpacity: Float get() = get("background_opacity", "1.0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    /** kitty `background_blur` (0 = off, >0 = blur radius). */
    val backgroundBlur: Int get() = get("background_blur", "0").toIntOrNull() ?: 0
    /** kitty `disable_ligatures` (never / always / cursor). */
    val disableLigatures: String get() = get("disable_ligatures", "never")
    val ligaturesEnabled: Boolean get() = disableLigatures != "always"

    // --- Advanced ------------------------------------------------------------
    val term: String get() = get("term", "xterm-256color")
    val shellIntegration: String get() = get("shell_integration", "enabled")

    // --- Colors --------------------------------------------------------------
    /** Builds a ColorScheme from any color* settings in the config. */
    fun colorScheme(): ColorScheme {
        val sb = StringBuilder()
        for ((k, v) in entries) {
            if (k.startsWith("color") || k == "foreground" || k == "background" || k == "cursor") {
                sb.appendLine("$k $v")
            }
        }
        return if (sb.isEmpty()) ColorScheme.DEFAULT
        else ColorScheme.parse("Custom", sb.toString())
    }

    private fun get(key: String, default: String): String = entries[key] ?: default

    companion object {
        private const val CONFIG_FILENAME = "kitty.conf"
        private const val MAX_INCLUDE_DEPTH = 10

        /** Load config from app's files directory, or return defaults. */
        fun load(context: Context): KittyConfig {
            val file = File(context.filesDir, CONFIG_FILENAME)
            return if (file.exists()) parse(file.readText(), file.parentFile) else {
                KittyConfig(emptyMap(), defaultKeyBindings())
            }
        }

        /**
         * Parse a kitty.conf string into a KittyConfig. `include <path>`
         * directives are resolved relative to [baseDir] (recursively).
         */
        fun parse(text: String, baseDir: File? = null): KittyConfig {
            val entries = mutableMapOf<String, String>()
            val mapLines = StringBuilder()
            parseInto(text, baseDir, entries, mapLines, depth = 0)
            val bindings = KeyBinding.parseAll(mapLines.toString()).ifEmpty { defaultKeyBindings() }
            return KittyConfig(entries, bindings)
        }

        private fun parseInto(
            text: String, baseDir: File?,
            entries: MutableMap<String, String>, mapLines: StringBuilder, depth: Int,
        ) {
            if (depth > MAX_INCLUDE_DEPTH) return
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                if (trimmed.startsWith("include ")) {
                    val path = trimmed.removePrefix("include ").trim()
                    val incFile = if (baseDir != null) File(baseDir, path) else File(path)
                    if (incFile.exists()) {
                        parseInto(incFile.readText(), incFile.parentFile, entries, mapLines, depth + 1)
                    }
                    continue
                }
                if (trimmed.startsWith("map ")) {
                    mapLines.appendLine(trimmed)
                    continue
                }
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) entries[parts[0]] = parts[1]
            }
        }

        /** Apply the config's color scheme to the global palette. */
        fun applyColors(config: KittyConfig) {
            TerminalColors.applyScheme(config.colorScheme())
        }

        /** Default key bindings matching kitty defaults. */
        private fun defaultKeyBindings(): List<KeyBinding> = KeyBinding.parseAll("""
            map ctrl+shift+c copy_to_clipboard
            map ctrl+shift+v paste_from_clipboard
            map ctrl+shift+t new_tab
            map ctrl+shift+w close_tab
            map ctrl+shift+right next_tab
            map ctrl+shift+left previous_tab
            map ctrl+shift+equal change_font_size all +1.0
            map ctrl+shift+minus change_font_size all -1.0
            map ctrl+shift+0 change_font_size all 0
            map ctrl+shift+enter split_horizontal
            map ctrl+shift+d split_vertical
            map ctrl+shift+l next_layout
            map ctrl+shift+z scroll_to_prompt
            map ctrl+shift+slash show_scrollback
            map ctrl+shift+f5 load_config
        """.trimIndent())
    }
}
