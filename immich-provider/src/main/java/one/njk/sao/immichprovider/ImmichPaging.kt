package one.njk.sao.immichprovider

import one.njk.sao.immich.ImmichAsset
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Public source ownership is independent of two profiles pointing at the same Immich account. */
data class ImmichProfileScope(val id: String, val revision: String)

internal sealed interface ImmichPageInput {
    data class Metadata(val cursor: String? = null) : ImmichPageInput
    data class Context(val page: Int = 1) : ImmichPageInput
}

internal data class ImmichBackendPage(val assets: List<ImmichAsset>, val next: ImmichPageInput?, val fingerprint: String,
    val hasRecords: Boolean = assets.isNotEmpty())
internal data class ImmichContinuation(val input: ImmichPageInput, val offset: Int = 0,
    val fingerprint: String? = null, val previousFingerprint: String? = null)

/** Stateless authenticated positions. Backend results are reread on demand, never retained between calls. */
internal class ImmichPageCursors(secret: String, private val binding: String, private val request: (String) -> JSONObject) {
    private val key=SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256")
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    private val decoder=Base64.getUrlDecoder()
    private fun signature(bytes: ByteArray)=Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes) }
    fun encode(value: ImmichContinuation): String {
        val json=JSONObject().put("version",1).put("binding",binding).put("offset",value.offset)
            .put("fingerprint",value.fingerprint).put("previous",value.previousFingerprint)
        when(val input=value.input) {
            is ImmichPageInput.Metadata -> json.put("kind","metadata").put("cursor",input.cursor)
            is ImmichPageInput.Context -> json.put("kind","context").put("page",input.page)
        }
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        val token=encoder.encodeToString(bytes)+"."+encoder.encodeToString(signature(bytes))
        // The continuation must also fit the next SEARCH request alongside its full query/scope.
        if(request(token).toString().length.toLong()*2>ExtensionProtocol.MAX_REQUEST) throw ExtensionFailure("LIMIT_EXCEEDED")
        return token
    }
    fun decode(token: String, expected: ImmichPageInput): ImmichContinuation = try {
        require(token.length<=ExtensionProtocol.MAX_REQUEST/2)
        val parts=token.split('.'); require(parts.size==2)
        val bytes=decoder.decode(parts[0])
        require(MessageDigest.isEqual(signature(bytes),decoder.decode(parts[1])))
        val json=JSONObject(String(bytes,Charsets.UTF_8))
        require(json.getInt("version")==1 && json.getString("binding")==binding)
        val input=when(json.getString("kind")) {
            "metadata" -> ImmichPageInput.Metadata(SourceWire.nullable(json,"cursor").also { require(it==null || it.isNotBlank()) })
            "context" -> ImmichPageInput.Context(json.getInt("page").also { require(it>=1) })
            else -> throw IllegalArgumentException()
        }
        require((input is ImmichPageInput.Metadata)==(expected is ImmichPageInput.Metadata))
        val offset=json.getInt("offset").also { require(it in 0..100) }
        val fingerprint=SourceWire.nullable(json,"fingerprint")
        val previous=SourceWire.nullable(json,"previous")
        require((offset==0)==(fingerprint==null))
        require(listOfNotNull(fingerprint,previous).all { it.length==64 && it.all { c -> c in "0123456789abcdef" } })
        ImmichContinuation(input,offset,fingerprint,previous)
    } catch(_: Exception) { throw ExtensionFailure("INVALID_RESPONSE") }
}

internal class ImmichMediaPaging(private val cursors: ImmichPageCursors, private val mapAsset: (ImmichAsset) -> MediaItem) {
    fun page(backend: ImmichBackendPage, position: ImmichContinuation, incoming: String?): MediaPage {
        if(position.offset>0 && (position.offset>=backend.assets.size || position.fingerprint!=backend.fingerprint))
            throw ExtensionFailure("INVALID_RESPONSE")
        if(position.offset==0 && backend.hasRecords && position.previousFingerprint==backend.fingerprint)
            throw ExtensionFailure("INVALID_RESPONSE")
        if(backend.next==position.input) throw ExtensionFailure("INVALID_RESPONSE")
        fun next(consumed: Int): String? {
            val continuation=if(consumed<backend.assets.size) position.copy(offset=consumed,fingerprint=backend.fingerprint)
                else backend.next?.let { ImmichContinuation(it,previousFingerprint=backend.fingerprint) }
            return continuation?.let(cursors::encode).also { if(it!=null && it==incoming) throw ExtensionFailure("INVALID_RESPONSE") }
        }
        if(backend.assets.isEmpty()) return MediaPage(emptyList(),next(0)).also { ImmichResponses.checked(SourceWire.page(it)) }
        val items=mutableListOf<MediaItem>()
        var continuation: String?=null
        for(index in position.offset until backend.assets.size) {
            val candidate=mapAsset(backend.assets[index])
            val following=next(index+1)
            val proposed=MediaPage(items+candidate,following)
            if(!ImmichResponses.fits(SourceWire.page(proposed))) {
                if(items.isEmpty()) throw ExtensionFailure("LIMIT_EXCEEDED")
                break
            }
            items+=candidate; continuation=following
        }
        return MediaPage(items,continuation)
    }
}

/** Budget the actual SDK wrappers in UTF-16, including every continuation/sequence token. */
object ImmichResponses {
    fun fits(value: JSONObject)=value.toString().length.toLong()*2<=ExtensionProtocol.MAX_RESPONSE
    fun checked(value: JSONObject): JSONObject {
        if(!fits(value)) throw ExtensionFailure("LIMIT_EXCEEDED")
        return value
    }
    fun values(values: List<String>)=checked(JSONObject().put("values",SourceWire.strings(values)))
    fun people(value: PeoplePage)=checked(JSONObject().put("people",JSONArray(value.people.map(SourceWire::person))).put("next",value.nextPage))
    fun label(value: String, length: Int): String {
        var end=minOf(value.length,length)
        if(end>0 && end<value.length && Character.isHighSurrogate(value[end-1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0,end)
    }
    private fun compact(item: MediaItem, minimal: Boolean)=item.copy(title=if(minimal) "" else label(item.title,80),tags=emptyList(),emojis=emptyList())
    fun random(items: List<MediaItem>): MediaPage {
        for(level in 0..2) {
            val page=MediaPage(if(level==0) items else items.map { compact(it,level==2) })
            if(fits(SourceWire.page(page))) return page
        }
        // Another random request would be a different batch. Never drop rows or invent a continuation.
        throw ExtensionFailure("LIMIT_EXCEEDED")
    }
    fun sequence(value: SequencePage, token: JSONObject?=null): JSONObject {
        for(level in 0..2) {
            val page=if(level==0) value else value.copy(items=value.items.map { compact(it,level==2) })
            val response=SourceWire.sequence(page).apply { if(token!=null) put("token",token) }
            if(fits(response)) return response
        }
        throw ExtensionFailure("LIMIT_EXCEEDED")
    }
}
