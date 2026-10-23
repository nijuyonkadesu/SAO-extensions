package one.njk.sao.immichprovider

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** All requests use a local fixture server; no Immich account or personal media is involved. */
class ImmichPagingDeviceTest {
    private val server=MockWebServer()
    private val calls=Collections.synchronizedList(mutableListOf<Pair<String,JSONObject>>())
    private val scope=ImmichProfileScope("fixture-profile","fixture-revision")
    private val source="fixture-source"
    private val tagName="😀"+"Exact tag name ".repeat(25)
    private lateinit var config: ImmichConfig
    private var respond: (String,JSONObject) -> Any = { _,_ -> JSONObject() }
    @Before fun start() {
        server.dispatcher=object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path=request.requestUrl!!.encodedPath.removePrefix("/api/")
                val body=request.body.clone().readUtf8().let { if(it.isBlank()) JSONObject() else JSONObject(it) }
                calls+=path to body
                val response=if(path=="tags") JSONArray(listOf(tag("first"),tag("second"))) else respond(path,body)
                return MockResponse().setHeader("Content-Type","application/json").setBody(response.toString())
            }
        }
        server.start(); config=ImmichConfig(server.url("/").toString(),"synthetic-key")
    }
    @After fun close() { server.shutdown() }
    private fun adapter(value: ImmichConfig=config, owner: String=source, profile: ImmichProfileScope=scope)=
        ImmichAdapter(value,owner,profileScope=profile)
    private fun tag(id: String)=JSONObject().put("id",id).put("name",tagName).put("value","Parent/$id/$tagName")
    private fun filename(index: Int)="original-$index-"+"📸名字".repeat(60)+".png"
    private fun asset(index: Int, large: Boolean=true)=JSONObject().put("id","asset-$index").put("originalFileName",filename(index))
        .put("type","IMAGE").put("updatedAt","2026-10-23T00:00:00Z").put("originalMimeType","image/png")
        .put("visibility","timeline").put("exifInfo",JSONObject().put("description",if(large) "Long 😀 description ".repeat(300) else "Short"))
        .put("tags",if(!large) JSONArray() else JSONArray((0 until 80).map { tag ->
            JSONObject().put("id","tag-$tag").put("name","😀"+"Label ".repeat(35)).put("value","\n".repeat(100)+"tag-$tag")
        }))
    private fun result(rows: List<JSONObject>, next: String?=null, context: Boolean=false)=JSONObject().put("assets",
        JSONObject().put("items",JSONArray(rows)).put(if(context) "nextPage" else "nextCursor",next))
    private suspend fun collect(query: MediaQuery): List<MediaItem> {
        val items=mutableListOf<MediaItem>(); val continuations=mutableSetOf<String>(); var cursor: String?=null
        do {
            // A fresh adapter proves that continuations need no retained result buffer/client instance.
            val page=adapter().use { it.page(query,cursor) }
            assertTrue(SourceWire.page(page).toString().length.toLong()*2<=ExtensionProtocol.MAX_RESPONSE)
            page.items.forEach { item ->
                val index=item.remote!!.assetId.removePrefix("asset-").toInt()
                assertEquals(filename(index),item.filename)
                assertTrue(item.title.length<=160 && item.tags.size<=20 && item.tags.all { it.length<=80 })
            }
            items+=page.items; cursor=page.nextCursor
            if(cursor!=null) assertTrue("A continuation repeated",continuations.add(cursor))
            assertTrue("Paging did not terminate",continuations.size<30)
        } while(cursor!=null)
        return items
    }

    @Test fun metadataBudgetPreservesFullNamesSearchFiltersAndEveryUnconsumedRecord()=runBlocking {
        val first=(0 until 60).map { asset(it) }; val last=(60 until 70).map { asset(it) }
        respond={ path,body ->
            assertEquals("search/metadata",path)
            if(body.optString("cursor")=="second-backend-page") result(last) else result(first,"second-backend-page")
        }
        val text="A literal description 😀 "+"remaining search text ".repeat(100)
        val query=MediaQuery(text,tagName,SearchMode.DESCRIPTION)
        val items=collect(query)
        assertEquals((0 until 70).map { "asset-$it" },items.map { it.remote!!.assetId })
        val requests=calls.filter { it.first=="search/metadata" }.map { it.second }
        assertTrue(requests.size>2)
        assertTrue(requests.count { it.optString("cursor")!="second-backend-page" }>1)
        requests.forEach { body ->
            assertEquals("%${text.trim()}%",body.getJSONObject("filter").getJSONObject("description").getString("like"))
            assertEquals(listOf("first","second"),SourceWire.strings(body.getJSONObject("filter").getJSONObject("tagIds").getJSONArray("any")))
            assertFalse(body.has("page"))
        }
    }

    @Test fun contextBudgetRefetchesTheRankedPageAndRetainsAnyTagSemantics()=runBlocking {
        val rows=(0 until 150).associateWith { asset(it) }
        respond={ path,body -> when(path) {
            "search/smart" -> {
                assertFalse(body.has("filter")); assertFalse(body.has("cursor"))
                assertFalse(body.has("tagIds")) // Two matching leaves require ANY, not flat ALL.
                if(body.getInt("page")==1) result((0 until 100).map { rows.getValue(it) },"2",true)
                else result((100 until 150).map { rows.getValue(it) },context=true)
            }
            "search/metadata" -> {
                val ids=body.getJSONObject("filter").getJSONArray("or")
                val matching=(0 until ids.length()).map { ids.getJSONObject(it).getJSONObject("id").getString("eq").removePrefix("asset-").toInt() }
                    .filter { it%2==0 }.reversed()
                result(matching.map { rows.getValue(it) })
            }
            else -> throw AssertionError(path)
        } }
        val items=collect(MediaQuery("whole Context query 😀",tagName,SearchMode.CONTEXT))
        assertEquals((0 until 150 step 2).map { "asset-$it" },items.map { it.remote!!.assetId })
        val ranked=calls.filter { it.first=="search/smart" }.map { it.second }
        assertTrue(ranked.count { it.getInt("page")==1 }>1)
        assertTrue(ranked.count { it.getInt("page")==2 }>1)
        assertTrue(ranked.all { it.getString("query")=="whole Context query 😀" })
    }

    @Test fun cursorRejectsOtherQueryScopeCredentialTamperingAndChangedBackendPage()=runBlocking {
        var rows=(0 until 60).map { asset(it) }
        respond={ _,_ -> result(rows,"next") }
        val query=MediaQuery("query",mode=SearchMode.DESCRIPTION)
        val cursor=adapter().use { it.page(query,null).nextCursor!! }
        suspend fun rejects(value: ImmichAdapter, changed: MediaQuery=query, token: String=cursor) {
            value.use { try { it.page(changed,token); fail("Unbound/stale cursor accepted") }
                catch(error: ExtensionFailure) { assertEquals("INVALID_RESPONSE",error.code) } }
        }
        rejects(adapter(),query.copy(text="other"))
        rejects(adapter(),query.copy(filter="another exact filter"))
        rejects(adapter(owner="another-source"))
        rejects(adapter(profile=scope.copy(id="another-profile")))
        rejects(adapter(profile=scope.copy(revision="another-revision")))
        rejects(adapter(config.copy(apiKey="other-key")))
        rejects(adapter(config.copy(albumId="another-album")))
        val tampered=(if(cursor[0]=='A') "B" else "A")+cursor.drop(1)
        rejects(adapter(),token=tampered)
        rows=rows.reversed()
        rejects(adapter())
    }

    @Test fun repeatedBackendContinuationFailsBeforeReturningDuplicateRecords()=runBlocking {
        val rows=(0 until 60).map { asset(it) }
        respond={ _,body -> result(rows,if(body.optString("cursor")=="second") "third" else "second") }
        val query=MediaQuery("query",mode=SearchMode.DESCRIPTION)
        var cursor: String?=null
        val received=mutableListOf<String>()
        try {
            repeat(20) {
                val page=adapter().use { it.page(query,cursor) }
                received+=page.items.map { it.remote!!.assetId }; cursor=page.nextCursor
            }
            fail("A repeated backend page continued forever")
        } catch(error: ExtensionFailure) { assertEquals("INVALID_RESPONSE",error.code) }
        assertEquals((0 until 60).map { "asset-$it" },received)
    }

    @Test fun finiteRandomBatchReducesOnlyPresentationAndNeverRequestsASecondRandomPage()=runBlocking {
        respond={ path,_ -> assertEquals("search/random",path); JSONArray((0 until 100).map { asset(it) }) }
        val query=MediaQuery("Ignored ranking text",mode=SearchMode.CONTEXT,randomRequest="finite-batch")
        val page=adapter().use { it.page(query,null) }
        assertEquals((0 until 100).map { "asset-$it" },page.items.map { it.remote!!.assetId })
        assertTrue(page.items.all { it.tags.isEmpty() && it.emojis.isEmpty() })
        page.items.forEachIndexed { index,item -> assertEquals(filename(index),item.filename) }
        assertNull(page.nextCursor); assertTrue(ImmichResponses.fits(SourceWire.page(page)))
        assertTrue(adapter().use { it.page(query,"already-delivered").items.isEmpty() })
        assertEquals(1,calls.count { it.first=="search/random" })
    }

    @Test fun minimalRandomIdentityOverflowFailsExplicitlyWithoutTruncatingOrDroppingRows()=runBlocking {
        respond={ _,_ -> JSONArray(listOf(asset(0,false).put("originalFileName","x".repeat(ExtensionProtocol.MAX_RESPONSE)))) }
        try { adapter().use { it.page(MediaQuery(randomRequest="large-identity"),null) }; fail("Oversized identity was truncated/dropped") }
        catch(error: ExtensionFailure) { assertEquals("LIMIT_EXCEEDED",error.code) }
        assertEquals(1,calls.size)
    }

    @Test fun personSearchPagesAllIdsWithBoundedLabelsAndExactListsFailExplicitlyOnOverflow()=runBlocking {
        val people=(0 until 85).map { JSONObject().put("id","person-$it").put("name","Long person label 😀 ".repeat(100)) }
        respond={ path,_ -> assertEquals("search/person",path); JSONArray(people) }
        val ids=mutableListOf<String>(); var page: Int?=1
        while(page!=null) {
            val result=adapter().use { it.people("Original person query",page!!,source) }
            assertTrue(result.people.all { it.name.length<=160 }); ImmichResponses.people(result)
            ids+=result.people.map { it.id }; page=result.nextPage
        }
        assertEquals((0 until 85).map { "person-$it" },ids)
        val exact=listOf("😀 complete exact tag "+"x".repeat(1200))
        assertEquals(exact,SourceWire.strings(ImmichResponses.values(exact).getJSONArray("values")))
        try { ImmichResponses.values(listOf("x".repeat(ExtensionProtocol.MAX_RESPONSE))); fail("Exact choice silently changed") }
        catch(error: ExtensionFailure) { assertEquals("LIMIT_EXCEEDED",error.code) }
    }
}
