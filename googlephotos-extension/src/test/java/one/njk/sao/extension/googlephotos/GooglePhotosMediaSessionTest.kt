package one.njk.sao.extension.googlephotos

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import one.njk.sao.source.ExtensionFailure
import org.junit.Assert.*
import org.junit.Test

class GooglePhotosMediaSessionTest {
    @Test fun netscapeImportsKeepAccountCookiesHostOnlyAndRespectPaths() {
        val text = ".google.com\tTRUE\t/\tTRUE\t2147483647\tSID\tfixture\n" +
            "#HttpOnly_accounts.google.com\tFALSE\t/ServiceLogin\tTRUE\t2147483647\tLSID\taccounts\n" +
            ".evil.test\tTRUE\t/\tTRUE\t2147483647\tsecret\tignored\n"
        val jar = GooglePhotosCookieJar(GooglePhotosCookies.scoped(text))
        assertEquals(2, jar.loadForRequest("https://accounts.google.com/ServiceLogin".toHttpUrl()).size)
        assertEquals(1, jar.loadForRequest("https://accounts.google.com/other".toHttpUrl()).size)
        assertEquals(1, jar.loadForRequest("https://photos.fife.usercontent.google.com/fixture".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://lh3.googleusercontent.com/fixture".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://accounts.google.com/ServiceLogin".toHttpUrl()).isEmpty())
    }

    @Test fun headerOnlyImportsDoNotReuseAnotherSessionsCookieScopes() {
        val cookies = GooglePhotosCookies.scoped("SID=new-session")
        assertEquals(1, cookies.size)
        assertEquals("new-session", cookies.single().value)
        assertTrue(cookies.single().hostOnly)
        assertEquals("photos.google.com", cookies.single().domain)
    }
    private fun config() = GooglePhotosConfig("SID=fixture-session", scopedCookies = listOf(
        Cookie.Builder().name("SID").value("fixture-session").domain("google.com").path("/").secure().build(),
        Cookie.Builder().name("LSID").value("accounts-only").hostOnlyDomain("accounts.google.com").path("/").secure().build(),
    ))
    @Test fun authenticatesPrivateMediaAndDropsCookiesOnPublicCdnRedirects() = runBlocking {
        for (host in listOf("lh3.google.com", "photos.fife.usercontent.google.com")) {
            val requests = mutableListOf<Request>()
            val bytes = byteArrayOf(1, 2, 3, 4)
            val http = GooglePhotosApi.client().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (requests.size == 1) 302 else 200).message("fixture")
                    .apply { if (requests.size == 1) header("Location", "https://lh3.googleusercontent.com/fixture") }
                    .body(bytes.toResponseBody()).build()
            }.build()
            try {
                val api = GooglePhotosApi(config(), http)
                api.media("https://$host/fixture").use { assertArrayEquals(bytes, it.response.body!!.bytes()) }
                assertEquals(2, requests.size)
                assertEquals("SID=fixture-session", requests[0].header("Cookie"))
                assertNull(requests[1].header("Cookie"))
                assertNull(requests[0].header("Authorization"))
                assertEquals("https://photos.google.com/", requests[0].header("Referer"))
            } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
    }

    @Test fun refusesForeignRedirectsBeforeMakingAnotherRequest() = runBlocking {
        var requests = 0
        val http = GooglePhotosApi.client().newBuilder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                .header("Location", "https://evil.test/fixture").body(ByteArray(0).toResponseBody()).build()
        }.build()
        try {
            val api = GooglePhotosApi(config(), http)
            try { api.media("https://lh3.google.com/fixture"); fail("Expected rejected redirect") }
            catch (error: ExtensionFailure) { assertEquals("INVALID_RESPONSE", error.code) }
            assertEquals(1, requests)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun completesGoogleMediaSignInWithoutSendingAccountCookiesToMediaHosts() = runBlocking {
        val requests = mutableListOf<Request>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val http = GooglePhotosApi.client().newBuilder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").body(bytes.toResponseBody()).apply {
                when (requests.size) {
                    1 -> { code(302); header("Location", "https://accounts.google.com/ServiceLogin") }
                    2 -> { code(302); header("Location", "https://photos.fife.usercontent.google.com/CheckCookie") }
                    3 -> {
                        code(302); header("Location", "/fixture")
                        header("Set-Cookie", "MEDIA_SESSION=fixture-media; Path=/; Secure; HttpOnly")
                    }
                    else -> code(200)
                }
            }.build()
        }.build()
        try {
            val api = GooglePhotosApi(config(), http)
            api.media("https://photos.fife.usercontent.google.com/fixture").use { assertArrayEquals(bytes, it.response.body!!.bytes()) }
            assertEquals(4, requests.size)
            assertFalse(requests[0].header("Cookie")!!.contains("LSID="))
            assertTrue(requests[1].header("Cookie")!!.contains("LSID=accounts-only"))
            assertFalse(requests[2].header("Cookie")!!.contains("LSID="))
            assertTrue(requests[3].header("Cookie")!!.contains("MEDIA_SESSION=fixture-media"))
            assertTrue(api.currentConfig().scopedCookies.any { it.name == "MEDIA_SESSION" && it.hostOnly })
            assertFalse(api.currentConfig().cookies.contains("MEDIA_SESSION="))
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun signInPageIsAnAuthenticationErrorRatherThanAnImage() = runBlocking {
        var count = 0
        val http = GooglePhotosApi.client().newBuilder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (count == 1) 302 else 200).apply {
                    if (count == 1) header("Location", "https://accounts.google.com/ServiceLogin")
                }.body("<html>Sign in</html>".toResponseBody()).build()
        }.build()
        try {
            val api = GooglePhotosApi(config(), http)
            try { api.media("https://lh3.google.com/fixture"); fail("Expected authentication failure") }
            catch (error: ExtensionFailure) { assertEquals("HTTP_401", error.code) }
            assertEquals(2, count)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
