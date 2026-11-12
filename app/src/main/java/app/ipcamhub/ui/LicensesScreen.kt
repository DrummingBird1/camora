package app.ipcamhub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ipcamhub.R
import app.ipcamhub.Strings

/** A third-party component shipped in the app. Names and licences are not translated. */
private data class Component(val name: String, val owner: String, val license: String, val url: String)

private val COMPONENTS = listOf(
    Component("AndroidX (Core, SplashScreen, Activity, Lifecycle, DataStore)", "The Android Open Source Project", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Component("Jetpack Compose, Material 3", "The Android Open Source Project", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Component("AndroidX Media3 (ExoPlayer, RTSP)", "The Android Open Source Project", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Component("Kotlin standard library, kotlinx.coroutines", "JetBrains s.r.o.", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Component("UVCAndroid", "herohan", "Apache License 2.0", "https://github.com/shiyinghan/UVCAndroid"),
    Component("UVCCamera", "saki t_saki", "Apache License 2.0", "https://github.com/saki4510t/UVCCamera"),
    Component("libuvc", "Ken Tossell", "BSD 3-Clause License", "https://github.com/libuvc/libuvc"),
    Component("libusb", "libusb contributors", "GNU LGPL 2.1 (linked as a separate shared library, libusb1.0.so)", "https://github.com/libusb/libusb"),
    Component("libjpeg-turbo", "libjpeg-turbo project, Independent JPEG Group", "IJG License, BSD 3-Clause, zlib License", "https://github.com/libjpeg-turbo/libjpeg-turbo"),
    Component("libyuv", "The LibYuv Project Authors", "BSD 3-Clause License", "https://chromium.googlesource.com/libyuv/libyuv"),
    Component("ZXing (QR codes)", "ZXing authors", "Apache License 2.0", "https://github.com/zxing/zxing"),
    Component("NanoHTTPD", "NanoHTTPD contributors", "BSD 3-Clause License", "https://github.com/NanoHttpd/nanohttpd"),
    Component("Google Play services Cast framework", "Google LLC", "Android Software Development Kit License", "https://developer.android.com/studio/terms"),
)

/** Open-source components and their licences. Tapping one opens the licence or source page. */
@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(Strings.get(R.string.licenses_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(Strings.get(R.string.licenses_note), style = MaterialTheme.typography.bodySmall) }
        items(COMPONENTS) { c ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)))
                }
            ) {
                // Names and licences are English: keep their punctuation in order inside a right-to-left layout.
                val ltr = androidx.compose.ui.text.style.TextDirection.Ltr
                Column(Modifier.padding(12.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall.copy(textDirection = ltr))
                    Text("© ${c.owner}", style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr))
                    Text(c.license, style = MaterialTheme.typography.bodySmall.copy(textDirection = ltr))
                }
            }
        }
    }
}
