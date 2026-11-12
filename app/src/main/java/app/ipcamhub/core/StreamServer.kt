package app.ipcamhub.core

import app.ipcamhub.R
import app.ipcamhub.Strings
import fi.iki.elonen.NanoHTTPD

/**
 * Small HTTP server on the phone. A TV or PC browser opens the page, which plays the
 * fragmented-MP4 stream through Media Source Extensions with very low delay.
 * Every request needs the secret key (?k=...) shown in the app.
 */
class StreamServer(port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val key = session.parameters["k"]?.firstOrNull()
        // The secret key is never logged.
        android.util.Log.d("StreamServer", "${session.method} ${session.uri} from ${session.remoteIpAddress} ua=${session.headers["user-agent"]} range=${session.headers["range"]}")
        if (key != Hub.token) return text(Response.Status.FORBIDDEN, "forbidden")
        val uri = session.uri
        return when {
            uri == "/" -> index(key)
            uri.startsWith("/watch/") -> html(watchPage())
            // TVs probe with HEAD first; answer without opening a viewer that would never be read.
            uri.startsWith("/stream/") && session.method == Method.HEAD ->
                streamHead(uri.removePrefix("/stream/").removeSuffix(".mp4").removeSuffix(".ts"), uri.endsWith(".ts"))
            uri.startsWith("/stream/") -> stream(uri.removePrefix("/stream/").removeSuffix(".mp4").removeSuffix(".ts"), uri.endsWith(".ts"))
            uri.startsWith("/hls/") -> hls(uri.removePrefix("/hls/"), key)
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun index(key: String): Response {
        val items = Hub.sharedCameras().joinToString("") {
            "<li><a href=\"/watch/${it.id}?k=$key\">${escape(it.name)}</a> " +
                "<small><a href=\"/hls/${it.id}/index.m3u8?k=$key\">HLS</a></small></li>"
        }.ifEmpty { "<li>${escape(Strings.get(R.string.web_no_cameras))}</li>" }
        return html(
            "<!doctype html><html dir=${pageDir()}><head><meta charset=utf-8>" +
                "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Camora</title>" +
                "<style>body{font:24px sans-serif;background:#111;color:#eee;padding:24px}a{color:#8cf}li{margin:16px 0}</style>" +
                "</head><body><h1>Camora</h1><ul>$items</ul></body></html>"
        )
    }

    private fun stream(id: String, ts: Boolean = false): Response {
        val client = Hub.attachViewer(id, ts) ?: return text(Response.Status.NOT_FOUND, "no such camera")
        val live = Hub.liveOf(id)
        var waited = 0
        while (live != null && live.initSegment == null && waited < 15_000) {
            Thread.sleep(100)
            waited += 100
        }
        if (live == null || live.initSegment == null) {
            client.close()
            return text(Response.Status.SERVICE_UNAVAILABLE, "camera not ready")
        }
        val response = newChunkedResponse(Response.Status.OK, if (ts) "video/mpeg" else "video/mp4", client)
        response.addHeader("X-Codec", live.codec)
        response.addHeader("Cache-Control", "no-store")
        dlnaHeaders(response, ts, live.hevc)
        return response
    }

    private fun streamHead(id: String, ts: Boolean = false): Response {
        // The live stream only starts when a viewer attaches, so just check that the camera is shared.
        if (Hub.sharedCameras().none { it.id == id }) return text(Response.Status.NOT_FOUND, "no such camera")
        val response = newFixedLengthResponse(Response.Status.OK, if (ts) "video/mpeg" else "video/mp4", java.io.ByteArrayInputStream(ByteArray(0)), Int.MAX_VALUE.toLong())
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Content-Length", Int.MAX_VALUE.toString())
        dlnaHeaders(response, ts, Hub.sharedCameras().firstOrNull { it.id == id }?.hevc == true)
        return response
    }

    /** Headers that DLNA renderers (Samsung and others) look for before playing a live stream. */
    private fun dlnaHeaders(r: Response, ts: Boolean = false, hevc: Boolean = false) {
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader("contentFeatures.dlna.org", DlnaText.features(if (ts) "video/mpeg" else "video/mp4", hevc))
        r.addHeader("Accept-Ranges", "none")
    }

    /** HLS for players that cannot use the browser page: /hls/<camera>/index.m3u8, init.mp4, seg<N>.m4s */
    private fun hls(path: String, key: String): Response {
        val parts = path.split('/')
        if (parts.size != 2) return text(Response.Status.NOT_FOUND, "not found")
        val (id, file) = parts
        val packager = Hub.hlsPackager(id) ?: return cors(text(Response.Status.NOT_FOUND, "no such camera or not ready"))
        val live = Hub.liveOf(id)
        return cors(
            when {
                file == "index.m3u8" -> {
                    // Players start a few segments behind the newest one; with fewer than four they keep running dry and show a loading bar.
                    var waited = 0
                    while (packager.segmentCount() < 4 && waited < 12_000) {
                        Thread.sleep(100)
                        waited += 100
                    }
                    packager.lastAccessMs = System.currentTimeMillis()
                    newFixedLengthResponse(
                        Response.Status.OK, "application/vnd.apple.mpegurl", packager.playlist("?k=$key")
                    ).also { it.addHeader("Cache-Control", "no-cache") }
                }
                file.startsWith("seg") && file.endsWith(".ts") -> {
                    val index = file.removePrefix("seg").removeSuffix(".ts").toIntOrNull()
                    val segment = index?.let { packager.segment(it) }
                    packager.lastAccessMs = System.currentTimeMillis()
                    if (segment == null) text(Response.Status.NOT_FOUND, "no such segment") else bytes("video/mp2t", segment.data)
                }
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        )
    }

    private fun bytes(mime: String, data: ByteArray): Response =
        newFixedLengthResponse(Response.Status.OK, mime, java.io.ByteArrayInputStream(data), data.size.toLong())

    /** Cast receivers and web players on another origin need CORS headers. */
    private fun cors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun text(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun html(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun pageDir() = if (android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"

    private fun watchPage(): String {
        fun js(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
        return WATCH_PAGE
            .replace("@@dir@@", pageDir())
            .replace("@@connecting@@", Strings.get(R.string.connecting))
            .replace("@@sound_on@@", Strings.get(R.string.web_sound_on))
            .replace("@@no_mse@@", js(Strings.get(R.string.web_no_mse)))
            .replace("@@error_code@@", js(Strings.get(R.string.web_error_code)))
            .replace("@@error_colon@@", js(Strings.get(R.string.web_error_colon)))
            .replace("@@stopped@@", js(Strings.get(R.string.web_stream_stopped)))
            .replace("@@conn_error@@", js(Strings.get(R.string.web_connection_error)))
    }

    private companion object {
        val WATCH_PAGE = """
<!doctype html><html dir=@@dir@@><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Camora</title>
<style>html,body{margin:0;height:100%;background:#000;color:#ccc;font-family:sans-serif}
video{width:100%;height:100%;object-fit:contain;background:#000}
#m{position:fixed;top:8px;left:8px;font-size:18px}
#u{position:fixed;top:8px;right:8px;font-size:20px;padding:8px 16px;border:0;border-radius:8px;background:#444;color:#fff}
#u:focus{outline:3px solid #8cf}</style></head>
<body><video id="v" autoplay muted playsinline></video><div id="m">@@connecting@@</div>
<button id="u" autofocus>@@sound_on@@</button>
<script>
// Browsers only allow sound after a click or a remote-control press.
var ub = document.getElementById('u');
ub.onclick = function () { var vv = document.getElementById('v'); vv.muted = false; vv.play(); ub.style.display = 'none'; };
</script>
<script>
(function () {
  var id = location.pathname.split('/').pop();
  var v = document.getElementById('v'), m = document.getElementById('m');
  function say(t) { m.textContent = t; }
  if (!window.MediaSource) { say('@@no_mse@@'); return; }
  fetch('/stream/' + id + '.mp4' + location.search).then(function (res) {
    if (!res.ok) { say('@@error_code@@' + res.status); return; }
    var codec = res.headers.get('X-Codec');
    var ms = new MediaSource();
    v.src = URL.createObjectURL(ms);
    ms.addEventListener('sourceopen', function () {
      var sb = ms.addSourceBuffer('video/mp4; codecs="' + codec + '"');
      var q = [];
      function pump() {
        if (sb.updating || !q.length) return;
        try { sb.appendBuffer(q.shift()); } catch (e) { say('@@error_colon@@' + e.message); }
      }
      sb.addEventListener('updateend', function () {
        var b = v.buffered;
        if (b.length) {
          var end = b.end(b.length - 1);
          if (end - v.currentTime > 1.0) { v.currentTime = end - 0.2; }
          if (v.currentTime - b.start(0) > 15 && !sb.updating) {
            try { sb.remove(b.start(0), v.currentTime - 5); } catch (e) {}
            return;
          }
        }
        pump();
      });
      var reader = res.body.getReader();
      function read() {
        reader.read().then(function (r) {
          if (r.done) { say('@@stopped@@'); return; }
          q.push(r.value);
          pump();
          if (v.paused) { v.play().catch(function () {}); }
          if (m.textContent) { say(''); }
          read();
        });
      }
      read();
    });
  }).catch(function () { say('@@conn_error@@'); });
})();
</script></body></html>
""".trimIndent()
    }
}
