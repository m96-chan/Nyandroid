package dev.nyandroid.terminal.backend

import android.content.Context
import java.io.File

/**
 * kitty-style shell integration (#13). Provides a bash/zsh snippet that emits
 * OSC 133 prompt marks and OSC 7 CWD reports, and deploys it both as a file in
 * app storage and by injecting it into the running shell on connect.
 */
object ShellIntegration {

    private const val SCRIPT_NAME = "nyandroid-shell-integration.sh"

    /**
     * A compact snippet (bash) that reports:
     *  - OSC 133 ;D (last command end + exit code), ;A (prompt start), ;C (cmd start)
     *  - OSC 7 (current working directory)
     */
    val snippet: String = """
        __nyan_pc(){ local e=${'$'}?; printf '\033]133;D;%s\007\033]133;A\007\033]7;file://%s%s\007' "${'$'}e" "${'$'}{HOSTNAME:-localhost}" "${'$'}PWD"; }
        PROMPT_COMMAND=__nyan_pc
        PS0=${'$'}'\033]133;C\007'
    """.trimIndent()

    /** Writes the snippet to app storage (so it can be sourced manually too). */
    fun deployScript(context: Context): File {
        val file = File(context.filesDir, SCRIPT_NAME)
        runCatching { file.writeText(snippet + "\n") }
        return file
    }

    /** The bytes to inject into a live shell to enable integration immediately. */
    fun injectionBytes(): ByteArray =
        (snippet.replace('\n', ';') + "\r").toByteArray(Charsets.US_ASCII)
}
