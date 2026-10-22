package one.njk.sao.extension.googlephotos

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import one.njk.sao.source.ExtensionFailure
import org.json.JSONArray
import org.json.JSONObject

/** Only the small read-only RPC subset used by this source. See third_party/xob0t. */
internal object GooglePhotosProtocol {
    data class Request(val rpc: String, val data: JSONArray, val id: String = "sao")
    data class Globals(val token: String, val session: String, val build: String, val path: String, val account: String?)
    data class Row(val key: String, val thumbnail: String, val taken: Long, val uploaded: Long,
        val video: Boolean, val description: String)
    data class Page(val rows: List<Row>, val next: String?)
    data class Album(val key: String, val name: String, val count: Int)
    data class Info(val key: String, val filename: String, val description: String)
    private val keyPattern = Regex("[A-Za-z0-9_-]{1,512}")
    fun validKey(key: String) = keyPattern.matches(key)
    fun array(vararg values: Any?) = JSONArray(values.map { it ?: JSONObject.NULL })

    fun globals(html: String): Globals {
        val marker = "window.WIZ_global_data"
        val assignment = html.indexOf(marker)
        if (assignment < 0) throw ExtensionFailure("HTTP_401")
        val start = html.indexOf('{', assignment + marker.length)
        if (start < 0) throw ExtensionFailure("INVALID_RESPONSE")
        // Scan JSON, not JavaScript; braces inside quoted strings must not terminate it.
        var depth = 0; var quoted = false; var escaped = false
        for (end in start until html.length) {
            val c = html[end]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    val json = JSONObject(html.substring(start, end + 1))
                    fun field(key: String) = json.optString(key).takeIf { it.isNotBlank() && it.length <= 4096 }
                        ?: throw ExtensionFailure("HTTP_401")
                    val path = field("Im6cmf").trimEnd('/')
                    if (!Regex("/(?:u/[0-9]{1,2}/)?_/(?:PhotosUi|Photos)").matches(path)) throw ExtensionFailure("INVALID_RESPONSE")
                    return Globals(field("SNlM0e"), field("FdrFJe"), field("cfb2h"), path,
                        json.optString("oPEP7c").takeIf { '@' in it })
                }
            }
        }
        throw ExtensionFailure("INVALID_RESPONSE")
    }

    fun envelope(body: String, requests: List<Request>): Map<String, JSONArray> {
        val expected = requests.associateBy { it.id }
        if (expected.size != requests.size) throw ExtensionFailure("INVALID_RESPONSE")
        val result = mutableMapOf<String, JSONArray>()
        // batchexecute is XSSI-prefixed, optionally length-framed JSON lines.
        body.lineSequence().map(String::trim).filter { it.startsWith('[') }.forEach { line ->
            val chunks = JSONArray(line)
            for (i in 0 until chunks.length()) {
                val entry = chunks.optJSONArray(i) ?: continue
                if (entry.optString(0) != "wrb.fr") continue
                val request = expected[entry.optString(6)] ?: continue
                if (entry.optString(1) != request.rpc || result.containsKey(request.id)) throw ExtensionFailure("INVALID_RESPONSE")
                val value = entry.opt(2)
                if (value !is String || value.isBlank()) throw ExtensionFailure("INVALID_RESPONSE")
                result[request.id] = JSONArray(value)
            }
        }
        if (result.keys != expected.keys) throw ExtensionFailure("INVALID_RESPONSE")
        return result
    }

    fun page(data: JSONArray, album: Boolean = false): Page {
        val rows = data.optJSONArray(if (album) 1 else 0) ?: if (data.isNull(if (album) 1 else 0)) JSONArray()
            else throw ExtensionFailure("INVALID_RESPONSE")
        if (rows.length() > 2000) throw ExtensionFailure("INVALID_RESPONSE")
        val items = List(rows.length()) { index ->
            val row = rows.getJSONArray(index)
            val key = row.getString(0)
            val thumbnail = nested(row, 1, 0) as? String ?: throw ExtensionFailure("INVALID_RESPONSE")
            if (!validKey(key) || !allowedMedia(thumbnail)) {
                val url = thumbnail.toHttpUrlOrNull()
                android.util.Log.w("SAO.GooglePhotos", "Item format rejected: keyValid=${validKey(key)}, keyLength=${key.length}, " +
                    "thumbnailHost=${url?.host}, https=${url?.isHttps}, port=${url?.port}, " +
                    "userinfo=${url?.let { it.username.isNotEmpty() || it.password.isNotEmpty() }}, " +
                    "fragment=${url?.fragment != null}, urlLength=${thumbnail.length}")
                throw ExtensionFailure("INVALID_RESPONSE")
            }
            Row(key, thumbnail, (row.opt(2) as? Number)?.toLong() ?: 0,
                (row.opt(5) as? Number)?.toLong() ?: 0,
                nested(row, -1, "76647426", 0) is Number,
                (nested(row, -1, "396644657", 0) as? String).orEmpty().take(512))
        }
        return Page(items, optionalString(data, if (album) 2 else 1)?.also {
            if (it.length > 8192) throw ExtensionFailure("INVALID_RESPONSE")
        })
    }

    fun albums(data: JSONArray): Pair<List<Album>, String?> {
        val rows = data.optJSONArray(0) ?: JSONArray()
        if (rows.length() > 500) throw ExtensionFailure("INVALID_RESPONSE")
        return List(rows.length()) { i ->
            val row = rows.getJSONArray(i)
            val key = row.getString(0)
            if (!validKey(key)) throw ExtensionFailure("INVALID_RESPONSE")
            Album(key, (nested(row, -1, "72930366", 1) as? String).orEmpty().take(256),
                (nested(row, -1, "72930366", 3) as? Number)?.toInt() ?: 0)
        } to optionalString(data, 1)
    }

    fun info(data: JSONArray): Map<String, Info> {
        val rows = nested(data, 0, 1) as? JSONArray ?: throw ExtensionFailure("INVALID_RESPONSE")
        return List(rows.length()) { index ->
            val row = rows.getJSONArray(index)
            Info(row.getString(0), (nested(row, 1, 3) as? String).orEmpty().take(256),
                (nested(row, 1, 2) as? String).orEmpty().take(512))
        }.associateBy { it.key }
    }

    fun download(data: JSONArray, original: Boolean): String {
        val value = optionalString(data, if (original) 7 else 1)
        if (value != null && allowedMedia(value)) return value
        android.util.Log.w("SAO.GooglePhotos", "Download URL rejected: original=$original, " +
            "editedType=${data.opt(1)?.javaClass?.simpleName}, originalType=${data.opt(7)?.javaClass?.simpleName}, " +
            "host=${value?.toHttpUrlOrNull()?.host}, length=${value?.length}")
        throw ExtensionFailure("INVALID_RESPONSE")
    }

    fun allowedMedia(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (url.host.endsWith(".googleusercontent.com") || url.host.endsWith(".ggpht.com") || url.host.endsWith(".googlevideo.com") ||
                url.host in setOf("photos.fife.usercontent.google.com", "lh3.google.com")) && value.length <= 4096
    }

    fun preview(value: String): String {
        return rendition(value, "w1600-h1600-no")
    }

    /** Google's 360p MP4 playback rendition, never the original/download URL. */
    fun playback(value: String): String = rendition(value, "m18")

    private fun rendition(value: String, parameter: String): String {
        if (!allowedMedia(value)) throw ExtensionFailure("INVALID_RESPONSE")
        val url = requireNotNull(value.toHttpUrlOrNull())
        return url.newBuilder().encodedPath(url.encodedPath.substringBeforeLast('=', url.encodedPath) + "=$parameter").build().toString()
    }

    fun mime(filename: String, video: Boolean): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"; "avif" -> "image/avif"; "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"
        "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; else -> if (video) "video/mp4" else "image/jpeg"
    }
    fun optionalString(array: JSONArray, index: Int) = (array.opt(index) as? String)?.takeIf(String::isNotBlank)
    fun nested(value: Any?, vararg path: Any): Any? = path.fold(value) { current, key ->
        when {
            current is JSONArray && key is Int -> current.opt(if (key < 0) current.length() + key else key)
            current is JSONObject && key is String -> current.opt(key)
            else -> null
        }.takeUnless { it == JSONObject.NULL }
    }
}
