package io.graspfolio.app

/** Per-document chrome: reopening the menu resets a manually hidden writing toolbar. */
internal enum class ReaderChrome {
    READING, MENU, WRITING;

    fun onBack(): ReaderChrome = if (this == MENU) WRITING else MENU
}
