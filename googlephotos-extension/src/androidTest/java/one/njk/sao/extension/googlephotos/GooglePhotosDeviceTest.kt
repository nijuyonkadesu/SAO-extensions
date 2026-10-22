package one.njk.sao.extension.googlephotos

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.source.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic servers and media only. These checks never connect to a real Google account. */
class GooglePhotosDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val runtime = GooglePhotosRuntime.get(app)
    private val store = runtime.profiles
    private val client = ExtensionClient(app, ComponentName(app.packageName, GooglePhotosExtensionService::class.java.name))
    private val server = MockWebServer()
    private val created = mutableListOf<String>()
    private var search: (JSONArray) -> MockResponse? = { null }
    private var media: (okhttp3.HttpUrl) -> MockResponse? = { null }
    private val filenames = mutableMapOf<String, String>()
    private val requestedQueries = mutableListOf<JSONArray>()
    private val downloads = AtomicInteger()
    private val png by lazy { ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(60, 130, 180)); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
        }; out.toByteArray()
    } }
    private fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
    private fun arr(vararg values: Any?) = GooglePhotosProtocol.array(*values)
    private fun html() = "<script data-id=\"_gd\">window.WIZ_global_data = " + JSONObject()
        .put("SNlM0e", "fixture-csrf").put("FdrFJe", "fixture-session").put("cfb2h", "fixture-build")
        .put("Im6cmf", "/_/PhotosUi").put("oPEP7c", "fixture@example.com") + ";</script>"
    private fun envelope(rpc: String, id: String, value: JSONArray) = ")]}'\n\n42\n" + arr(arr("wrb.fr", rpc, value.toString(), null, null, null, id)) + "\n"
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun filename(key: String) = filenames[key] ?: ("$key." + when (key.substringAfterLast('-').toIntOrNull()?.rem(3)) {
        1 -> "gif"; 2 -> "mp4"; else -> "png"
    })
    private fun row(number: Int): JSONArray {
        val result = arr("key-$number", arr("https://lh3.googleusercontent.com/preview/key-$number", 200, 280),
            1_700_000_000_000L, "dedup-$number", 0, 1_700_000_000_000L + number)
        result.put(15, JSONObject().apply { if (filename("key-$number").substringAfterLast('.') in setOf("mp4", "mkv")) put("76647426", arr(5000)) })
        return result
    }
    private fun rows(start: Int, count: Int) = JSONArray((start until start + count).map(::row))

    @Before fun setup() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/MergeSession") return MockResponse().setResponseCode(302)
                    .setHeader("Location", "https://photos.google.com/")
                    .addHeader("Set-Cookie", "SID=fixture-native; Domain=.google.com; Path=/; Secure; HttpOnly")
                if (!url.encodedPath.contains("batchexecute") && !url.encodedPath.startsWith("/preview/") && !url.encodedPath.startsWith("/original/"))
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(html())
                if (url.encodedPath.startsWith("/original/") || url.encodedPath.startsWith("/preview/")) {
                    media(url)?.let { return it }
                    val file = url.encodedPath.substringAfterLast('/').substringBefore('=')
                    val bytes = if (url.encodedPath.endsWith("=m18")) asset("video.mp4")
                        else when (file.substringAfterLast('.')) { "gif" -> asset("hello.gif"); "mp4" -> asset("video.mp4"); else -> png }
                    return MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes))
                }
                val form = request.body.readUtf8().split('&').associate {
                    val parts = it.split('=', limit = 2); URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                if (form["at"] != "fixture-csrf") return MockResponse().setResponseCode(403)
                val entry = JSONArray(form.getValue("f.req")).getJSONArray(0).getJSONArray(0)
                val rpc = entry.getString(0); val data = JSONArray(entry.getString(1)); val id = entry.getString(3)
                val result = when (rpc) {
                    "EzkLib", "lcxiM", "snAcKc" -> {
                        synchronized(requestedQueries) { requestedQueries += data }
                        search(data)?.let { return it }
                        if (rpc == "snAcKc") arr(null, rows(0, 4), null)
                        else if (GooglePhotosProtocol.optionalString(data, if (rpc == "lcxiM") 0 else 2) == "second-page") arr(rows(85, 1), null)
                        else arr(rows(0, 85), "second-page")
                    }
                    "EWgK9e" -> {
                        val keys = data.getJSONArray(0).getJSONArray(0).getJSONArray(0)
                        arr(arr(null, JSONArray(List(keys.length()) { i ->
                            val key = keys.getJSONArray(i).getString(0)
                            arr(key, arr(null, null, "Fixture media", filename(key)))
                        })))
                    }
                    "VrseUb" -> {
                        downloads.incrementAndGet()
                        arr(row(0), "https://lh3.googleusercontent.com/edited/" + filename(data.getString(0)), null, null, null, null, null,
                            "https://lh3.googleusercontent.com/original/" + filename(data.getString(0)))
                    }
                    "Z5xsfc" -> arr(arr(arr("album-key", null, null, null, null, null, null,
                        JSONObject().put("72930366", arr(null, "Fixture album", null, 4)))), null)
                    else -> return MockResponse().setResponseCode(400)
                }
                return json(envelope(rpc, id, result))
            }
        }
        server.start()
        runtime.fixture = GooglePhotosApi.client().newBuilder().addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").host("127.0.0.1")
                .port(server.port).build()).build())
        }).build()
        runtime.clearSessions()
    }
    @After fun cleanup(): Unit = runBlocking {
        created.forEach { id -> store.list().firstOrNull { it.id == id }?.let { store.delete(id, it.revision) } }
        runtime.clearSessions()
        runtime.fixture?.let { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        runtime.fixture = null
        server.shutdown()
    }
    private suspend fun profile(config: GooglePhotosConfig = GooglePhotosConfig("SID=fixture-cookie")): GooglePhotosProfile {
        val id = UUID.randomUUID().toString(); created += id
        return store.save(id, "Photos fixture", config)
    }
    private fun payload(row: GooglePhotosProfile) = JSONObject().put("revision", row.revision).put("source", "fixture:${row.id}:${row.revision}")
    private suspend fun page(row: GooglePhotosProfile, query: MediaQuery = MediaQuery("blue shirt 😂"), cursor: String? = null) = SourceWire.page(
        client.request(row.id, ExtensionProtocol.SEARCH, payload(row).put("query", SourceWire.query(query)).put("cursor", cursor)))
    private suspend fun uri(row: GooglePhotosProfile, item: MediaItem, kind: String) = Uri.parse(client.request(row.id,
        ExtensionProtocol.MEDIA, payload(row).put("item", SourceWire.item(item)).put("kind", kind)).getString("uri"))
    private fun read(uri: Uri): ByteArray = app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes(); fd.checkError(); bytes
    }
    private suspend fun fails(code: String, operation: suspend () -> Unit) {
        try { operation(); fail("Expected $code") } catch (error: ExtensionFailure) { assertEquals(code, error.code) }
    }

    @Test fun capabilitiesAndProfilesStayPrivateAndDoNotMakeIdleRequests(): Unit = runBlocking {
        val first = profile(); val album = profile(first.config.copy(album = "album-key", albumName = "Memes"))
        val all = client.request("", ExtensionProtocol.PROFILES, JSONObject()).toString()
        assertFalse(all.contains("fixture-cookie")); assertFalse(all.contains("SID="))
        val caps = SourceWire.capabilities(client.request(first.id, ExtensionProtocol.CAPABILITIES, payload(first)))
        assertEquals(setOf(SearchMode.CONTEXT, SearchMode.OCR), caps.modes)
        assertTrue(caps.facets.isEmpty()); assertFalse(caps.random); assertFalse(caps.imports); assertFalse(caps.sequence)
        val albumCaps = SourceWire.capabilities(client.request(album.id, ExtensionProtocol.CAPABILITIES, payload(album)))
        assertTrue(albumCaps.modes.isEmpty()); assertTrue(albumCaps.categories.isEmpty())
        assertEquals(first.revision, store.save(first.id, "Renamed", first.config).revision)
        delay(300)
        assertEquals(0, server.requestCount)
    }

    @Test fun contextPreservesWholeQueryAndOversizedBackendPagesContinueWithoutResultCache(): Unit = runBlocking {
        val row = profile(); val collected = mutableListOf<MediaItem>()
        var cursor: String? = null
        repeat(4) {
            val result = page(row, cursor = cursor); collected += result.items; cursor = result.nextCursor
        }
        assertNull(cursor); assertEquals(86, collected.size)
        assertEquals(86, collected.map { it.remote!!.assetId }.distinct().size)
        assertTrue(requestedQueries.all { it.getString(0) == "blue shirt 😂" })
        assertEquals("second-page", requestedQueries.last().getString(2))
        page(row)
        assertEquals(5, requestedQueries.size)
        assertTrue(collected.any { it.mimeType == "image/gif" && it.animated })
        assertTrue(collected.any { it.mimeType == "video/mp4" && it.isVideo })
    }

    @Test fun textMatchesAreQuotedAndAlbumProfilesBrowseOnly(): Unit = runBlocking {
        val row = profile()
        page(row, MediaQuery("some words 😁", mode = SearchMode.OCR))
        assertEquals("\"some words 😁\"", requestedQueries.single().getString(0))
        val album = profile(row.config.copy(album = "album-key", albumName = "Memes"))
        assertEquals(4, page(album, MediaQuery()).items.size)
        assertEquals("album-key", requestedQueries.last().getString(0))
        fails("UNSUPPORTED") { page(album, MediaQuery("unexpected search")) }
        val albums = runtime.api(row).albums(null)
        assertEquals("Fixture album", albums.first.single().name)
    }

    @Test fun cursorsAreBoundToQueryAndRevisionAndRejectTampering(): Unit = runBlocking {
        val row = profile(); val result = page(row)
        val count = server.requestCount
        fails("INVALID_RESPONSE") { page(row, MediaQuery("other query"), result.nextCursor) }
        fails("INVALID_RESPONSE") { page(row, cursor = "broken") }
        val other = profile()
        fails("PROFILE_CHANGED") { page(other, cursor = result.nextCursor) }
        store.save(row.id, row.name, row.config.copy(account = 1))
        fails("PROFILE_CHANGED") { page(row) }
        assertEquals(count, server.requestCount)
    }

    @Test fun imagesGifAndMp4StreamOriginalBytesWithoutForwardingAccountCookies(): Unit = runBlocking {
        val row = profile(); val items = page(row).items.take(3)
        items.forEach { item ->
            val expected = when (item.mimeType) { "image/gif" -> asset("hello.gif"); "video/mp4" -> asset("video.mp4"); else -> png }
            assertArrayEquals(expected, read(uri(row, item, "original")))
            if (item.isVideo) assertArrayEquals(expected, read(uri(row, item, "video")))
            else assertArrayEquals(expected, read(uri(row, item, "raster")))
        }
        var checked = 0
        while (true) {
            val request = server.takeRequest(50, TimeUnit.MILLISECONDS) ?: break
            if (request.path!!.startsWith("/original/")) { checked++; assertNull(request.getHeader("Cookie")) }
        }
        assertEquals(6, checked)
        assertTrue(downloads.get() >= 6)
    }

    @Test fun playbackMp4AndOriginalMkvUseIndependentVariants(): Unit = runBlocking {
        filenames["key-2"] = "huge.mkv"
        val row = profile(); val item = page(row).items.first { it.remote!!.assetId == "key-2" }
        assertEquals("video/x-matroska", item.mimeType)
        assertNotEquals(item.remote!!.cacheKeys["original"], item.remote!!.cacheKeys["playback"])
        assertArrayEquals(asset("video.mp4"), read(uri(row, item, "playback")))
        assertEquals(0, downloads.get())
        val original = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(100) { 7 }
        media = { url -> if (url.encodedPath.startsWith("/original/")) MockResponse().setBody(Buffer().write(original)) else null }
        assertArrayEquals(original, read(uri(row, item, "original")))
        assertEquals(1, downloads.get())
    }

    @Test fun unavailableOrOversizedPlaybackNeverFallsBackToOriginal(): Unit = runBlocking {
        val row = profile(); val item = page(row).items.first { it.isVideo }
        for ((response, code) in listOf(
            MockResponse().setResponseCode(404) to "HTTP_404",
            MockResponse().setBody(Buffer().write(asset("video.mp4"))).setHeader("Content-Length", 8L * 1024 * 1024 + 1) to "MEDIA_TOO_LARGE",
            MockResponse().setBody(Buffer().write(png)) to "UNSUPPORTED",
        )) {
            media = { response }
            try { read(uri(row, item, "playback")); fail("Expected $code") }
            catch (error: java.io.IOException) { assertTrue(error.message, error.message.orEmpty().contains(code)) }
        }
        media = { null }
        assertArrayEquals(png, read(uri(row, item, "preview")))
        assertEquals(0, downloads.get())
    }

    @Test fun hostCarouselRequestsPlaybackForMkvWithoutOpeningOriginalDownload(): Unit = runBlocking {
        filenames["key-2"] = "huge.mkv"
        search = { json(envelope("snAcKc", "sao", arr(null, arr(row(2)), null))) }
        val playbackRequests = AtomicInteger()
        media = { url ->
            if (url.encodedPath.endsWith("=m18")) {
                playbackRequests.incrementAndGet()
                MockResponse().setBody(Buffer().write(asset("video.mp4")))
            } else null
        }
        val row = profile(GooglePhotosConfig("SID=fixture-cookie", album="album-key", albumName="Playback fixture"))
        app.startActivity(app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("SAO home") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/type" } }
        click { it.viewIdResourceName == "one.njk.sao:id/edit" }
        waitFor("Discovered source") { nodes().any { it.text?.toString() == row.name } }
        click { it.text?.toString() == row.name }
        click { it.viewIdResourceName == "one.njk.sao:id/back" }
        waitFor("Centered MKV requests the small playback rendition") { playbackRequests.get() == 1 }
        assertTrue(nodes().any { it.viewIdResourceName == "one.njk.sao:id/media_title" && it.text?.toString() == "huge.mkv" })
        assertEquals(0, downloads.get())
        // Observe the settled carousel, rather than merely a request started during navigation.
        delay(500)
        assertEquals(1, playbackRequests.get()); assertEquals(0, downloads.get())
    }

    @Test fun explicitNativeLoginConsumesOnlyTrustedGoogleRedirects(): Unit = runBlocking {
        val config = GooglePhotosApi.connect("https://accounts.google.com/MergeSession?uberauth=fixture-one-use", "fixture@example.com", runtime.fixture!!)
        assertEquals("SID=fixture-native", config.cookies); assertEquals(0, config.account)
        val first = server.takeRequest(); assertEquals("/MergeSession?uberauth=fixture-one-use", first.path)
        assertEquals("", first.getHeader("Cookie"))
        assertEquals("SID=fixture-native", server.takeRequest().getHeader("Cookie"))
        val before = server.requestCount
        fails("AUTH_UNSUPPORTED") {
            GooglePhotosApi.connect("https://evil.test/MergeSession?uberauth=fixture", "fixture@example.com", runtime.fixture!!)
        }
        assertEquals(before, server.requestCount)
    }

    @Test fun nativeHandshakeRequiresFreshOneUseNonceAndGoogleCaller(): Unit = runBlocking {
        val pending = GooglePhotosConnect.begin()
        assertFalse(GooglePhotosConnect.claim("wrong")); assertTrue(GooglePhotosConnect.claim(pending.nonce))
        assertFalse(GooglePhotosConnect.claim(pending.nonce))
        GooglePhotosConnect.complete(pending.nonce, "fixture@example.com", "https://accounts.google.com/MergeSession?uberauth=fixture", "")
        assertEquals("fixture@example.com", pending.result.await().account)
        assertFalse(GooglePhotosConnect.claim(pending.nonce))
        assertFalse(GooglePhotosConnect.isPhotos(app.packageManager, android.os.Process.myUid()))
        val connected = CompletableDeferred<android.os.IBinder>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: android.os.IBinder) { connected.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        assertTrue(app.bindService(Intent(app, GooglePhotosAuthService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            val broker = IGooglePhotosAuthBroker.Stub.asInterface(withTimeout(5000) { connected.await() })
            assertThrows(SecurityException::class.java) { broker.claim(pending.nonce) }
        } finally { app.unbindService(connection) }
    }

    @Test fun throttlingAndAuthenticationErrorsHaveNoExtensionRetry(): Unit = runBlocking {
        val row = profile()
        search = { MockResponse().setResponseCode(429).setHeader("Retry-After", "30") }
        try { page(row); fail("Expected 429") } catch (error: ExtensionFailure) {
            assertEquals("HTTP_429", error.code); assertEquals("30", error.retryAfter)
        }
        assertEquals(1, requestedQueries.size)
        search = { MockResponse().setResponseCode(401) }
        fails("HTTP_401") { page(row) }
        assertEquals(2, requestedQueries.size)
    }

    @Test fun cookieImportsDiscardOtherDomainsAndRejectHeaderInjection() {
        val text = "# Netscape HTTP Cookie File\n.google.com\tTRUE\t/\tTRUE\t2147483647\tSID\tfixture\n.evil.test\tTRUE\t/\tTRUE\t2147483647\tsecret\tignored\n"
        assertEquals("SID=fixture", GooglePhotosCookies.parse(text))
        assertEquals("SID=fixture; HSID=second", GooglePhotosCookies.parse("SID=fixture; HSID=second"))
        assertThrows(IllegalArgumentException::class.java) { GooglePhotosCookies.parse("SID=fixture\r\nInjected: yes") }
        assertFalse(GooglePhotosProtocol.allowedMedia("https://lh3.googleusercontent.com.evil.test/media"))
        assertFalse(GooglePhotosProtocol.allowedMedia("https://user@lh3.googleusercontent.com/media"))
    }

    @Test fun cancellationDuringSlowRpcBodyInterruptsTheHttpCall(): Unit = runBlocking {
        val row = profile(); runtime.api(row).check(); server.takeRequest()
        search = { data -> json(envelope("EzkLib", "sao", arr(rows(0, 1), null))).throttleBody(1, 1, TimeUnit.SECONDS) }
        val request = async(Dispatchers.IO) { page(row) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        val start = SystemClock.elapsedRealtime(); request.cancelAndJoin()
        assertTrue(SystemClock.elapsedRealtime() - start < 3000)
    }

    @Test fun longMediaReferencesStayWithinBinderBoundsAndResumeAtTheCorrectItem(): Unit = runBlocking {
        search = { data ->
            val values = rows(0, 60)
            repeat(values.length()) { index -> values.getJSONArray(index).getJSONArray(1)
                .put(0, "https://lh3.googleusercontent.com/" + "x".repeat(3000) + "/key-$index") }
            json(envelope("EzkLib", "sao", arr(values, null)))
        }
        val row = profile(); val first = page(row)
        assertTrue(first.items.size in 1..39)
        assertTrue(SourceWire.page(first).toString().length * 2 <= ExtensionProtocol.MAX_RESPONSE)
        val second = page(row, cursor = first.nextCursor)
        assertEquals("key-${first.items.size}", second.items.first().remote!!.assetId)
    }

    @Test fun bootstrapAndSessionRotationPreserveProfileIdentity(): Unit = runBlocking {
        val row = profile()
        store.refreshCookies(row.id, row.revision, "SID=rotated")
        assertEquals(row.revision, store.get(row.id).revision)
        assertEquals("SID=rotated", store.get(row.id).config.cookies)
        assertEquals("https://lh3.googleusercontent.com/photo=w1600-h1600-no?authuser=0",
            GooglePhotosProtocol.preview("https://lh3.googleusercontent.com/photo=w80-h80-c?authuser=0"))
        assertThrows(ExtensionFailure::class.java) { GooglePhotosProtocol.globals("<html>Sign in</html>") }
        assertThrows(ExtensionFailure::class.java) { GooglePhotosProtocol.envelope("[]", listOf(GooglePhotosProtocol.Request("EzkLib", arr()))) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // Settings finish across two packages. Refresh UiAutomation's cached active window after that transition.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val output = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node != null) { output += node; repeat(node.childCount) { visit(node.getChild(it)) } } }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        instrumentation.uiAutomation.windows.filter { it.isActive || it.isFocused }.forEach { visit(it.root) }
        return output
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(80)
        assertTrue(label + ": " + nodes().mapNotNull { it.text?.toString() }.take(25), condition())
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        waitFor("Clickable control") {
            target = nodes().firstOrNull(predicate)
            while (target != null && !target!!.isClickable) target = target!!.parent
            target?.isVisibleToUser == true && target?.isEnabled == true
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        try { instrumentation.uiAutomation.waitForIdle(300, 5000) }
        catch (_: java.util.concurrent.TimeoutException) { }
    }
    private fun show(id: String) {
        waitFor("Visible $id") {
            val node = nodes().firstOrNull { it.viewIdResourceName == id }
            if (node?.isVisibleToUser == true) true else {
                if (node != null) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
    }
    private fun visual(stage: String) {
        if (InstrumentationRegistry.getArguments().getString("visualPause") != "true") return
        val only = InstrumentationRegistry.getArguments().getString("visualStage")
        if (only != null && only != stage) return
        val ready = java.io.File(app.cacheDir, "googlephotos-visual-$stage").apply { writeText("ready") }
        val resume = java.io.File(app.cacheDir, "googlephotos-visual-continue").apply { delete() }
        try {
            val until = SystemClock.elapsedRealtime() + 50_000
            while (!resume.exists() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertTrue("Visual capture resumed", resume.exists())
        } finally { ready.delete(); resume.delete() }
    }

    @Test fun unchangedSaoBrowsesAndEditsThisExtensionAndKeepsUnsupportedControlsHidden(): Unit = runBlocking {
        val row = profile()
        app.startActivity(app.packageManager.getLaunchIntentForPackage("one.njk.sao")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("SAO home") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/type" } }
        click { it.viewIdResourceName == "one.njk.sao:id/edit" }
        waitFor("Discovered source") { nodes().any { it.text?.toString() == row.name } }
        click { it.text?.toString() == row.name }
        click { it.viewIdResourceName == "one.njk.sao:id/back" }
        waitFor("Home shows extension media") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/media_title" && it.text?.toString() == "key-0.png" } }
        click { it.viewIdResourceName == "one.njk.sao:id/edit" }
        click { it.contentDescription?.toString() == "Edit ${row.name}" }
        waitFor("Source settings") { nodes().any { it.viewIdResourceName == "${app.packageName}:id/connect_vector" } }
        visual("settings")
        val field = nodes().first { it.viewIdResourceName == "${app.packageName}:id/source_name" }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Google Photos saved")
        }))
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor("Shared settings guard") { nodes().any { it.text?.toString() == "Keep editing" } }
        click { it.text?.toString() == "Keep editing" }
        show("${app.packageName}:id/save_source")
        visual("settings-bottom")
        click { it.viewIdResourceName == "${app.packageName}:id/save_source" }
        waitFor("Saved source") { nodes().any { it.text?.toString() == "Google Photos saved" } }
        assertEquals(row.revision, store.get(row.id).revision)
        click { it.viewIdResourceName == "one.njk.sao:id/back" }
        waitFor("Home again") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/type" } }
        click { it.viewIdResourceName == "one.njk.sao:id/settings" }
        show("one.njk.sao:id/preview"); click { it.viewIdResourceName == "one.njk.sao:id/preview" }
        waitFor("Floating source control") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/source_toggle" } }
        if (nodes().none { it.viewIdResourceName == "one.njk.sao:id/source_toggle" && it.contentDescription?.toString()?.contains("Google Photos saved") == true }) {
            click { it.viewIdResourceName == "one.njk.sao:id/source_toggle" }; click { it.text?.toString() == "Google Photos saved" }
        }
        waitFor("Floating media") { nodes().any { it.viewIdResourceName == "one.njk.sao:id/selection_title" && it.text?.toString() == "key-0.png" } }
        click { it.viewIdResourceName == "one.njk.sao:id/search_toggle" }
        assertFalse(nodes().any { it.viewIdResourceName == "one.njk.sao:id/search_lucky" && it.isVisibleToUser })
        visual("floating")
        click { it.viewIdResourceName == "one.njk.sao:id/close" }
    }
}
