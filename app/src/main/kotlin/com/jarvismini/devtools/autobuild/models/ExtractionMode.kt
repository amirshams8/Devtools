package com.jarvismini.devtools.autobuild.models

/**
 * How the app extracts code from Claude's response.
 *
 * CODE_BLOCK      — Claude typed code inside a code fence in chat.
 *                   Identified by HorizontalScrollView in the node tree.
 *                   App writes ai-output.txt directly.
 *
 * DOWNLOADED_FILE — Claude attached downloadable file(s).
 *                   App taps every visible download button.
 *                   Termux assembles ai-output.txt from new files in /sdcard/Download/.
 *
 * PLAIN_TEXT      — Claude pasted raw code with no code fence.
 *                   App grabs largest TextView, writes ai-output.txt directly.
 */
enum class ExtractionMode {
    CODE_BLOCK,
    DOWNLOADED_FILE,
    PLAIN_TEXT
}
