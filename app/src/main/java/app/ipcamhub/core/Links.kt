package app.ipcamhub.core

import java.util.Locale

/** Public pages about the app. Keep in step with assets/brand/brand.json. */
object Links {
    const val WEBSITE = "https://drummingbird1.github.io/camora/"
    const val SOURCE = "https://github.com/DrummingBird1/camora"
    const val ISSUES = "https://github.com/DrummingBird1/camora/issues"

    /** Name and address of each donation page. */
    val DONATE = listOf(
        "GitHub Sponsors" to "https://github.com/sponsors/DrummingBird1",
        "Patreon" to "https://www.patreon.com/c/Solvaris",
        "Ko-fi" to "https://ko-fi.com/solvaris1",
        "Buy Me a Coffee" to "https://buymeacoffee.com/solvaris",
    )

    /** The privacy policy exists in English and Hebrew. */
    fun privacy(): String =
        if (Locale.getDefault().language in setOf("he", "iw")) "${WEBSITE}he/privacy.html" else "${WEBSITE}privacy.html"
}
