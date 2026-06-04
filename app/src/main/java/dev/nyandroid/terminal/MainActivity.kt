package dev.nyandroid.terminal

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import dev.nyandroid.terminal.view.TerminalView

/**
 * Single-screen host for the PoC terminal. Keeps the screen on and hands the
 * whole window to a [TerminalView].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        terminalView = TerminalView(this)
        setContentView(terminalView)

        terminalView.requestFocus()
    }

    override fun onDestroy() {
        terminalView.shutdown()
        super.onDestroy()
    }
}
