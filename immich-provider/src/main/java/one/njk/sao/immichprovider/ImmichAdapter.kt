package one.njk.sao.immichprovider

import one.njk.sao.source.*

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.njk.sao.immich.*

/** One requested Immich operation; the extension closes its client after producing the response. */
class ImmichAdapter(private val config: ImmichConfig, private val sourceId: String,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers(),
    private val profileScope: ImmichProfileScope? = null) : AutoCloseable {
    private val client = ImmichClient(config.connection, retryReadConnections = false)
    private var cachedTags: List<ImmichTag>? = null
    private val tagsMutex = Mutex()
    suspend fun openSequence(anchor: MediaItem): MediaSequence = withContext(dispatchers.io) {
        val ref = requireNotNull(anchor.remote)
        check(ref.sourceId == sourceId) { "Media belongs to a different source" }
        val asset = run { client.api.asset(ref.assetId) }
        check(asset.visibility == "timeline") { "This item is not in the Photos timeline" }
        val month = requireNotNull(asset.localDateTime).substringBefore('T').substringBeforeLast('-')
        val buckets = run { client.api.timeBuckets(config.albumId) }
        val bucket = buckets.indexOfFirst { it.timeBucket.substringBefore('T').substringBeforeLast('-') == month }
        check(bucket >= 0) { "This item is not in the Photos timeline" }
        ImmichMediaSequence(client.api, config.albumId, asset.id, bucket, buckets, dispatchers, ::mapAsset)
    }
    override fun close() = client.close()
    suspend fun searchModes(): Set<SearchMode> = withContext(dispatchers.io) {
        val features = run { client.api.features() }
        buildSet {
            add(SearchMode.FILENAME); add(SearchMode.DESCRIPTION); add(SearchMode.PATH)
            if (features.smartSearch) add(SearchMode.CONTEXT)
            if (features.ocr != false) add(SearchMode.OCR)
        }
    }
    suspend fun filters(): List<String> {
        val tags = tags()
        return withContext(dispatchers.cpu) { tags.map { it.name }.filter(::isEmojiTag).distinct().also { ImmichResponses.values(it) } }
    }
    suspend fun page(query: MediaQuery, cursor: String?): MediaPage = withContext(dispatchers.io) {
        if (query.randomRequest != null && cursor != null) return@withContext MediaPage(emptyList())
        val tagIds = query.filter?.let { selected -> tags().filter {
            MediaSearch.normalize(it.name) == MediaSearch.normalize(selected)
        }.map { it.id }.distinct().sorted() }
        val filter = ImmichQueryCompiler.filter(if (query.randomRequest == null) query else query.copy(text = ""), sourceId, config.albumId, tagIds)
        if (query.randomRequest != null) {
            if (tagIds != null && tagIds.isEmpty()) return@withContext MediaPage(emptyList())
            val assets=client.api.randomSearch(RandomSearch(filter))
            validateAssets(assets,100)
            return@withContext withContext(dispatchers.cpu) {
                ImmichResponses.random(assets.filter { it.type in MEDIA_TYPES }.map(::mapAsset))
            }
        }
        val initial=if(query.text.isNotBlank() && query.mode==SearchMode.CONTEXT) ImmichPageInput.Context() else ImmichPageInput.Metadata()
        val binding=digest(org.json.JSONObject().put("query",SourceWire.query(query)).put("source",sourceId)
            .put("profile",profileScope?.id ?: sourceId).put("revision",profileScope?.revision ?: config.identity)
            .put("configuration",config.identity).put("tags",tagIds?.let(SourceWire::strings)).toString())
        val cursors=ImmichPageCursors(config.identity,binding) { next ->
            org.json.JSONObject().put("query",SourceWire.query(query)).put("cursor",next).put("source",sourceId)
                .put("revision",profileScope?.revision ?: config.identity)
        }
        val position=cursor?.let { cursors.decode(it,initial) } ?: ImmichContinuation(initial)
        if (tagIds != null && tagIds.isEmpty()) return@withContext MediaPage(emptyList())
        val backend=when(val input=position.input) {
            is ImmichPageInput.Metadata -> {
                val result=client.api.search(MetadataSearch(filter,input.cursor)).assets
                validateAssets(result.items,60)
                val next=result.nextCursor?.takeIf(String::isNotBlank)?.let { ImmichPageInput.Metadata(it) }
                backendPage(result.items,result.items.filter { it.type in MEDIA_TYPES },next)
            }
            is ImmichPageInput.Context -> smartPage(query,tagIds,filter,input.page)
        }
        withContext(dispatchers.cpu) { ImmichMediaPaging(cursors,::mapAsset).page(backend,position,cursor) }
    }
    fun mapAsset(asset: ImmichAsset): MediaItem {
        val mime = mediaMime(asset)
        val motion = asset.type == "VIDEO" || mime == "image/gif" || mime.startsWith("video/")
        return MediaItem("immich_${digest(sourceId).take(16)}_${digest(asset.id + asset.updatedAt + asset.isEdited).take(32)}",
            ImmichResponses.label(asset.exifInfo?.description?.takeIf { it.isNotBlank() } ?: asset.originalFileName,160), "", mime,
            asset.tags.asSequence().take(20).map { ImmichResponses.label(it.value,80) }.toList(),
            asset.tags.asSequence().map { it.name }.filter(::isEmojiTag).take(20).map { ImmichResponses.label(it,80) }.toList(), motion,
            RemoteMedia(sourceId, asset.id, asset.isEdited && !motion, asset.createdAt, mapOf(
                "original" to "original", "video" to "original",
                "preview" to if (motion && !mime.startsWith("video/")) "original" else "preview",
                "raster" to if (mime in setOf("image/png","image/webp")) "original" else "preview")), filename = asset.originalFileName)
    }
    private fun mediaMime(asset: ImmichAsset): String {
        val reported = asset.originalMimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (!reported.isNullOrBlank() && reported !in setOf("application/octet-stream", "binary/octet-stream", "application/binary"))
            return reported
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            asset.originalFileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            ?: if (asset.type == "VIDEO") "video/mp4" else "image/jpeg"
    }
    private suspend fun smartPage(query: MediaQuery, tagIds: List<String>?, filter: SearchFilter, page: Int): ImmichBackendPage {
        // Flat Context is paged by rank; multiple matching emoji leaves need a separate ANY lookup.
        currentCoroutineContext().ensureActive()
        val result=client.api.pagedSmartSearch(ImmichQueryCompiler.context(query,sourceId,config.albumId,tagIds,page)).assets
        validateAssets(result.items,100)
        val next=result.nextPage?.takeIf(String::isNotBlank)?.let { value ->
            ImmichPageInput.Context(value.toIntOrNull()?.takeIf { it>page } ?: throw ExtensionFailure("INVALID_RESPONSE"))
        }
        if(result.items.isEmpty() && next!=null) throw ExtensionFailure("INVALID_RESPONSE")
        val candidates=result.items.filter { it.type in MEDIA_TYPES && it.visibility in setOf("timeline","archive") }
        val assets=if(tagIds!=null && tagIds.size>1 && candidates.isNotEmpty()) {
            val matching=client.api.search(MetadataSearch(filter.copy(
                or=candidates.map { SearchBranch(id=IdEqualityFilter(it.id)) }),size=candidates.size)).assets
            validateAssets(matching.items,candidates.size)
            if(!matching.nextCursor.isNullOrBlank()) throw ExtensionFailure("INVALID_RESPONSE")
            val ids=matching.items.map { it.id }.toSet()
            candidates.filter { it.id in ids }
        } else candidates
        return backendPage(result.items,assets,next)
    }
    private fun validateAssets(assets: List<ImmichAsset>, maximum: Int) {
        if(assets.size>maximum || assets.any { it.id.isBlank() } || assets.map { it.id }.distinct().size!=assets.size)
            throw ExtensionFailure("INVALID_RESPONSE")
    }
    private fun backendPage(raw: List<ImmichAsset>, assets: List<ImmichAsset>, next: ImmichPageInput?)=ImmichBackendPage(assets,next,
        digest(org.json.JSONObject().put("backend",SourceWire.strings(raw.map { it.id }))
            .put("eligible",SourceWire.strings(assets.map { it.id })).toString()),raw.isNotEmpty())
    suspend fun people(name: String, page: Int, expectedSourceId: String?): PeoplePage = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        if(page<1) throw ExtensionFailure("INVALID_RESPONSE")
        val (people,next)=if(name.isNotBlank()) {
            val all=client.api.searchPeople(name.trim())
            val offset=(page.toLong()-1)*PEOPLE_PAGE_SIZE
            if(offset>Int.MAX_VALUE) throw ExtensionFailure("INVALID_RESPONSE")
            val shown=all.drop(offset.toInt()).take(PEOPLE_PAGE_SIZE)
            shown to if(offset+shown.size<all.size) page+1 else null
        } else {
            val result=client.api.people(page,PEOPLE_PAGE_SIZE)
            if(result.people.size>PEOPLE_PAGE_SIZE || result.hasNextPage && page==Int.MAX_VALUE) throw ExtensionFailure("INVALID_RESPONSE")
            result.people to if(result.hasNextPage) page+1 else null
        }
        PeoplePage(people.map { SearchPerson(it.id,ImmichResponses.label(it.name,160),sourceId) },next).also { ImmichResponses.people(it) }
    }
    suspend fun places(level: PlaceLevel, country: String?, state: String?, expectedSourceId: String?) = withContext(dispatchers.io) {
        check(expectedSourceId == null || expectedSourceId == sourceId) { "Filter belongs to a different source" }
        client.api.places(level.name.lowercase(java.util.Locale.ROOT),country,state).filterNotNull().distinct().also { ImmichResponses.values(it) }
    }
    fun mediaCall(id: String, kind: String, edited: Boolean) = client.mediaCall(id,kind,edited)
    fun reopenSequence(token: org.json.JSONObject): MediaSequence = ImmichMediaSequence(client.api, config.albumId,
        token.getString("anchor"), token.getInt("bucket"),
        SourceWire.strings(token.getJSONArray("buckets")).map { TimeBucket(it,0) }, dispatchers, ::mapAsset)
    private fun isEmojiTag(value: String) = value.codePoints().anyMatch {
        it in 0x1f000..0x1faff || it in 0x2600..0x27ff || it == 0x00a9 || it == 0x00ae
    }
    private suspend fun tags(): List<ImmichTag> = withContext(dispatchers.io) { tagsMutex.withLock {
        cachedTags ?: run { client.api.tags() }.also { cachedTags = it }
    } }
    private companion object {
        val MEDIA_TYPES=setOf("IMAGE","VIDEO")
        const val PEOPLE_PAGE_SIZE=40
    }
}
