package one.njk.sao.extension.googlephotos

import one.njk.sao.source.*
import org.json.JSONObject
import java.time.Instant

/** Maps one requested page; neither media bytes nor prior search results are persisted here. */
internal class GooglePhotosRecords(private val store: GooglePhotosProfiles) {
    fun capabilities(profile: GooglePhotosProfile, categories: List<SourceCategory> = emptyList()) = SourceCapabilities(
        // Google's Text matches includes OCR and textual metadata; explained in the source settings.
        modes = if (profile.config.album.isEmpty()) setOf(SearchMode.CONTEXT, SearchMode.OCR) else emptySet(),
        defaultMode = SearchMode.CONTEXT,
        categories = if (profile.config.album.isEmpty()) categories else emptyList(),
    )

    suspend fun page(profile: GooglePhotosProfile, source: String, query: MediaQuery, cursor: String?, api: GooglePhotosApi): MediaPage {
        if (query.text.length > 2048) throw ExtensionFailure("LIMIT_EXCEEDED")
        val normalized = capabilities(profile).normalize(query)
        if (profile.config.album.isNotEmpty() && query.text.isNotBlank()) throw ExtensionFailure("UNSUPPORTED")
        val text = if (normalized.mode == SearchMode.OCR && normalized.text.isNotBlank())
            "\"${normalized.text.trim().removeSurrounding("\"").replace("\"", "\\\"")}\"" else normalized.text
        val fingerprint = digest(SourceWire.query(normalized).toString())
        val prior = cursor?.let { reference(profile, it) }
        if (prior != null && prior.optString("query") != fingerprint) throw ExtensionFailure("INVALID_RESPONSE")
        val input = prior?.let { SourceWire.nullable(it, "page") }
        val offset = prior?.optInt("offset") ?: 0
        val page = api.page(text, input)
        val hash = digest(page.rows.joinToString("|") { it.key })
        if (offset !in 0..page.rows.size || (offset > 0 && prior?.optString("hash") != hash) ||
            (offset == 0 && prior?.optString("previous") == hash && page.rows.isNotEmpty())) throw ExtensionFailure("INVALID_RESPONSE")
        val slice = page.rows.drop(offset).take(PAGE_SIZE)
        val info = api.info(slice.map { it.key })
        var consumed = 0
        var wireSize = 1024
        val items = mutableListOf<MediaItem>()
        for (row in slice) {
            val details = info[row.key] ?: throw ExtensionFailure("INVALID_RESPONSE")
            val mime = GooglePhotosProtocol.mime(details.filename, row.video)
            val ref = scope(profile).put("key", row.key).put("preview", row.thumbnail).put("mime", mime)
            val version = digest("${row.key}:${row.thumbnail}")
            val item = MediaItem("googlephotos_" + digest("$source:${row.key}"), details.filename.ifBlank { details.description.ifBlank { "Google Photos" } },
                store.seal(ref), mime, emptyList(), emptyList(), mime in setOf("image/gif", "image/webp"),
                RemoteMedia(source, row.key, false, row.uploaded.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                    mapOf("original" to "$version:original", "raster" to "$version:${if (mime.startsWith("video/")) "preview" else "original"}",
                        "video" to "$version:original", "preview" to "$version:${if (mime == "image/gif" || mime == "image/webp") "original" else "preview"}") +
                        if (mime.startsWith("video/")) mapOf("playback" to "$version:playback-m18-v1") else emptyMap()),
                details.filename.ifBlank { null })
            val bytes = SourceWire.item(item).toString().length * 2
            if (items.isNotEmpty() && wireSize + bytes > ExtensionProtocol.MAX_RESPONSE - 40 * 1024) break
            items += item; wireSize += bytes; consumed++
        }
        val next = when {
            offset + consumed < page.rows.size -> store.seal(scope(profile).put("query", fingerprint).put("page", input)
                .put("offset", offset + consumed).put("hash", hash))
            page.next != null -> {
                if (page.next == input) throw ExtensionFailure("INVALID_RESPONSE")
                store.seal(scope(profile).put("query", fingerprint).put("page", page.next).put("offset", 0).put("previous", hash))
            }
            else -> null
        }
        return MediaPage(items.distinctBy { it.remote!!.assetId }, next)
    }

    fun reference(profile: GooglePhotosProfile, token: String): JSONObject = store.open(token).also {
        if (it.optString("profile") != profile.id || it.optString("revision") != profile.revision) throw ExtensionFailure("PROFILE_CHANGED")
    }
    fun mediaReference(profile: GooglePhotosProfile, token: String) = reference(profile, token).also {
        if (!GooglePhotosProtocol.validKey(it.getString("key")) || !GooglePhotosProtocol.allowedMedia(it.getString("preview")))
            throw ExtensionFailure("INVALID_RESPONSE")
    }
    suspend fun variant(ref: JSONObject, kind: String, api: GooglePhotosApi): Pair<String, String> {
        val mime = ref.getString("mime")
        val motionImage = mime in setOf("image/gif", "image/webp")
        return when (kind) {
            "preview" -> if (motionImage) api.download(ref.getString("key"), true) to mime
                else GooglePhotosProtocol.preview(ref.getString("preview")) to "image/jpeg"
            "raster" -> if (mime.startsWith("video/")) GooglePhotosProtocol.preview(ref.getString("preview")) to "image/jpeg"
                else api.download(ref.getString("key"), true) to mime
            "playback" -> {
                if (!mime.startsWith("video/")) throw ExtensionFailure("UNSUPPORTED")
                GooglePhotosProtocol.playback(ref.getString("preview")) to "video/mp4"
            }
            "original", "video" -> {
                if (kind == "video" && !mime.startsWith("video/")) throw ExtensionFailure("UNSUPPORTED")
                api.download(ref.getString("key"), true) to mime
            }
            else -> throw ExtensionFailure("UNSUPPORTED")
        }
    }
    private fun scope(profile: GooglePhotosProfile) = JSONObject().put("profile", profile.id).put("revision", profile.revision)
    companion object { const val PAGE_SIZE = 40 }
}
