package dev.nyandroid.terminal

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.nyandroid.terminal.view.ExtraKeysBar
import dev.nyandroid.terminal.view.TerminalView

/**
 * Single-screen host for the PoC terminal. Keeps the screen on and hands the
 * whole window to a [TerminalView].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysBar: ExtraKeysBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        terminalView = TerminalView(this)
        extraKeysBar = ExtraKeysBar(this, terminalView)
        terminalView.extraKeysBar = extraKeysBar

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            terminalView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        container.addView(
            extraKeysBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = maxOf(imeInsets.bottom, systemBars.bottom)
            view.setPadding(
                systemBars.left, systemBars.top, systemBars.right, bottomInset,
            )
            // Show extra keys bar only when keyboard is visible.
            extraKeysBar.visibility = if (imeInsets.bottom > 0) View.VISIBLE else View.GONE
            WindowInsetsCompat.CONSUMED
        }

        terminalView.requestFocus()
    }

    override fun onDestroy() {
        terminalView.shutdown()
        super.onDestroy()
    }
}
