package dev.nyandroid.terminal.view

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.regex.Pattern

/**
 * Detects URLs in terminal screen text and opens them in the browser.
 */
object UrlDetector {

    private val URL_PATTERN: Pattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE,
    )

    data class UrlMatch(
        val url: String,
        val startCol: Int,
        val endCol: Int,
        val row: Int,
    )

    /**
     * Finds all URLs in a single line of text.
     */
    fun findUrls(text: String, row: Int): List<UrlMatch> {
        val matches = mutableListOf<UrlMatch>()
        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            matches.add(UrlMatch(matcher.group(), matcher.start(), matcher.end() - 1, row))
        }
        return matches
    }

    /**
     * Checks if (row, col) falls within a URL and returns it.
     */
    fun urlAt(lines: List<String>, row: Int, col: Int): String? {
        if (row !in lines.indices) return null
        val urls = findUrls(lines[row], row)
        return urls.firstOrNull { col in it.startCol..it.endCol }?.url
    }

    /**
     * Opens a URL in the default browser.
     */
    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // No browser available or invalid URL.
        }
    }
}
