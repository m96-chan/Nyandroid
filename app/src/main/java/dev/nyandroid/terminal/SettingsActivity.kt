package dev.nyandroid.terminal

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.nyandroid.terminal.config.KittyConfig
import java.io.File

/**
 * Simple settings screen that edits kitty.conf values.
 * All changes are written directly to the config file.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var configFile: File
    private val entries = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        configFile = File(filesDir, "kitty.conf")
        if (configFile.exists()) {
            for (line in configFile.readText().lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith("map ")) continue
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) entries[parts[0]] = parts[1]
            }
        }

        val dp = resources.displayMetrics.density
        val config = KittyConfig.load(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG_COLOR)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, (60 * dp).toInt(), pad, pad)
        }
        scroll.addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Title
        root.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            val pad = (8 * dp).toInt()
            setPadding(0, 0, 0, pad)
        })

        // Font size
        root.addView(sectionHeader("Font"))
        root.addView(sliderRow("font_size", "Font Size", config.fontSize, 8f, 32f, 1f))

        // Scrollback
        root.addView(sectionHeader("Scrollback"))
        root.addView(numberRow("scrollback_lines", "Scrollback Lines", config.scrollbackLines))

        // Colors
        root.addView(sectionHeader("Colors"))
        root.addView(themeRow())
        root.addView(colorRow("foreground", "Foreground", entries["foreground"] ?: "#D0D0D0"))
        root.addView(colorRow("background", "Background", entries["background"] ?: "#0B0B0B"))

        // Bell
        root.addView(sectionHeader("Bell"))
        root.addView(switchRow("enable_audio_bell", "Audio Bell", config.enableAudioBell))

        // Save button
        root.addView(TextView(this).apply {
            text = "Save & Restart"
            setTextColor(Color.BLACK)
            setBackgroundColor(0xFF33CC66.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = (24 * dp).toInt()
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { saveAndRestart() }
        })

        setContentView(scroll)
    }

    /** A built-in theme picker; selecting one writes its colours into [entries]. */
    private fun themeRow(): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(0, pad, 0, pad)
        }
        row.addView(TextView(this).apply {
            text = "Theme"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val schemes = dev.nyandroid.terminal.emulator.ColorScheme.BUILTIN_SCHEMES
        val names = schemes.map { it.name }
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, names,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    applyTheme(schemes[pos])
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams((160 * dp).toInt(), WRAP_CONTENT))
        return row
    }

    /** Writes a scheme's colours into the pending config entries. */
    private fun applyTheme(scheme: dev.nyandroid.terminal.emulator.ColorScheme) {
        entries["foreground"] = "#%06X".format(scheme.foreground)
        entries["background"] = "#%06X".format(scheme.background)
        entries["cursor"] = "#%06X".format(scheme.cursor)
        for (i in scheme.base16.indices) {
            entries["color$i"] = "#%06X".format(scheme.base16[i])
        }
    }

    private fun sectionHeader(title: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = title
            setTextColor(0xFF33CC66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = (16 * dp).toInt()
            lp.bottomMargin = (4 * dp).toInt()
            layoutParams = lp
        }
    }

    private fun sliderRow(key: String, label: String, current: Float, min: Float, max: Float, step: Float): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(0, pad, 0, pad)
        }
        val valueText = TextView(this).apply {
            text = "$label: ${current.toInt()}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(valueText)
        val steps = ((max - min) / step).toInt()
        val seekBar = SeekBar(this).apply {
            this.max = steps
            progress = ((current - min) / step).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    valueText.text = "$label: ${value.toInt()}"
                    entries[key] = value.toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(seekBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return row
    }

    private fun numberRow(key: String, label: String, current: Int): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(0, pad, 0, pad)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(EditText(this).apply {
            setText(current.toString())
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2A2A3E.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams((100 * dp).toInt(), WRAP_CONTENT)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    entries[key] = s.toString()
                }
            })
        })
        return row
    }

    private fun colorRow(key: String, label: String, current: String): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * dp).toInt()
            setPadding(0, pad, 0, pad)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(EditText(this).apply {
            setText(current)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2A2A3E.toInt())
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams((120 * dp).toInt(), WRAP_CONTENT)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    entries[key] = s.toString()
                }
            })
        })
        return row
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(key: String, label: String, current: Boolean): Switch {
        val dp = resources.displayMetrics.density
        return Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = current
            val pad = (8 * dp).toInt()
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, checked ->
                entries[key] = if (checked) "yes" else "no"
            }
        }
    }

    private fun saveAndRestart() {
        // Preserve map lines from existing config.
        val mapLines = if (configFile.exists()) {
            configFile.readText().lines().filter { it.trim().startsWith("map ") }
        } else emptyList()

        val sb = StringBuilder("# Nyandroid kitty.conf\n")
        for ((k, v) in entries.toSortedMap()) {
            sb.appendLine("$k $v")
        }
        for (line in mapLines) {
            sb.appendLine(line)
        }
        configFile.writeText(sb.toString())

        // Restart the main activity.
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val BG_COLOR = 0xFF0E0E1E.toInt()

        fun launch(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
