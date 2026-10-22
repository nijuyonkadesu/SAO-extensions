package one.njk.sao.extension.googlephotos

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import one.njk.sao.source.ExtensionFailure
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** One account session. Only authentication/bootstrap state is retained; search results are not cached. */
internal class GooglePhotosApi(
    private val config: GooglePhotosConfig,
    private val http: OkHttpClient,
    private val updateCookies: suspend (String) -> Unit = {},
) {
    private val bootstrapLock = Mutex()
    private val cookiesLock = Any()
    private var cookieHeader = config.cookies
    private var persistedCookies = config.cookies
    private var globals: GooglePhotosProtocol.Globals? = null
    private var bootstrappedAt = 0L
    private val basePath = if (config.account == 0) "/" else "/u/${config.account}/"

    suspend fun check() { bootstrap(force = true) }
    fun currentConfig() = config.copy(cookies = synchronized(cookiesLock) { cookieHeader })
    suspend fun page(query: String, cursor: String?): GooglePhotosProtocol.Page {
        val album = config.album.isNotEmpty()
        val request = when {
            album -> GooglePhotosProtocol.Request("snAcKc", GooglePhotosProtocol.array(config.album, cursor, null, null))
            query.isNotBlank() -> GooglePhotosProtocol.Request("EzkLib", GooglePhotosProtocol.array(query, null, cursor))
            else -> GooglePhotosProtocol.Request("lcxiM", GooglePhotosProtocol.array(cursor, null, 50, null, 1, 1))
        }
        return GooglePhotosProtocol.page(rpc(request), album)
    }
    suspend fun albums(cursor: String?) = GooglePhotosProtocol.albums(rpc(GooglePhotosProtocol.Request("Z5xsfc",
        GooglePhotosProtocol.array(cursor, null, null, null, 1, null, null, 50, GooglePhotosProtocol.array(2), 5))))

    suspend fun info(keys: List<String>): Map<String, GooglePhotosProtocol.Info> {
        if (keys.isEmpty()) return emptyMap()
        val masks = JSONArray(List(24) { org.json.JSONObject.NULL } + listOf(JSONArray()) + List(10) { org.json.JSONObject.NULL } + listOf(JSONArray()))
        val data = GooglePhotosProtocol.array(GooglePhotosProtocol.array(GooglePhotosProtocol.array(JSONArray(keys.map {
            GooglePhotosProtocol.array(it)
        })), GooglePhotosProtocol.array(masks)))
        return GooglePhotosProtocol.info(rpc(GooglePhotosProtocol.Request("EWgK9e", data)))
    }

    suspend fun download(key: String, original: Boolean): String {
        if (!GooglePhotosProtocol.validKey(key)) throw ExtensionFailure("INVALID_RESPONSE")
        return GooglePhotosProtocol.download(rpc(GooglePhotosProtocol.Request("VrseUb",
            GooglePhotosProtocol.array(key, null, null, null, config.album.ifBlank { null }))), original)
    }

    private suspend fun rpc(request: GooglePhotosProtocol.Request): JSONArray {
        val data = bootstrap()
        val url = "https://photos.google.com${data.path}/data/batchexecute".toHttpUrl().newBuilder()
            .addQueryParameter("rpcids", request.rpc).addQueryParameter("source-path", basePath)
            .addQueryParameter("f.sid", data.session).addQueryParameter("bl", data.build).addQueryParameter("rt", "c").build()
        val body = FormBody.Builder().add("at", data.token).add("f.req", GooglePhotosProtocol.array(
            GooglePhotosProtocol.array(GooglePhotosProtocol.array(request.rpc, request.data.toString(), null, request.id))).toString()).build()
        return try {
            val response = text(Request.Builder().url(url).header("Origin", "https://photos.google.com")
                .header("Referer", "https://photos.google.com$basePath").post(body).build(), JSON_LIMIT)
            android.util.Log.i("SAO.GooglePhotos", "RPC ${request.rpc} response received")
            GooglePhotosProtocol.envelope(response, listOf(request)).getValue(request.id)
        } catch (error: ExtensionFailure) {
            if (error.code in setOf("HTTP_401", "HTTP_403")) bootstrapLock.withLock { globals = null }
            // The host owns retries. Expired sessions need explicit reconnection, not an auth loop.
            throw error
        }
    }

    private suspend fun bootstrap(force: Boolean = false): GooglePhotosProtocol.Globals = bootstrapLock.withLock {
        val age = android.os.SystemClock.elapsedRealtime() - bootstrappedAt
        if (!force && globals != null && age in 0..(10 * 60_000)) return@withLock requireNotNull(globals)
        val html = text(Request.Builder().url("https://photos.google.com$basePath").build(), HTML_LIMIT)
        GooglePhotosProtocol.globals(html).also {
            globals = it; bootstrappedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private suspend fun text(request: Request, limit: Long): String = coroutineScope {
        val authenticated = request.newBuilder().header("Cookie", synchronized(cookiesLock) { cookieHeader }).build()
        open(authenticated).use { opened ->
            if (opened.response.isRedirect) throw ExtensionFailure("HTTP_401")
            val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { opened.cancel() }
            }
            try {
                val body = opened.response.body ?: throw ExtensionFailure("INVALID_RESPONSE")
                val source = body.source()
                if (body.contentLength() > limit || source.request(limit + 1)) throw ExtensionFailure("INVALID_RESPONSE")
                val changed = synchronized(cookiesLock) {
                    val values = cookieHeader.split(';').associate {
                        val pair = it.trim().split('=', limit = 2); pair[0] to pair[1]
                    }.toMutableMap()
                    Cookie.parseAll(request.url, opened.response.headers).forEach { cookie ->
                        if (cookie.matches(request.url)) {
                            if (cookie.expiresAt <= System.currentTimeMillis()) values.remove(cookie.name) else values[cookie.name] = cookie.value
                        }
                    }
                    val next = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (next != cookieHeader) try { GooglePhotosCookies.validate(next) }
                        catch (_: IllegalArgumentException) { throw ExtensionFailure("HTTP_401") }
                    cookieHeader = next
                    next.takeIf { it != persistedCookies }
                }
                if (changed != null) { updateCookies(changed); synchronized(cookiesLock) { persistedCookies = changed } }
                source.readUtf8()
            } finally { cancel.cancel() }
        }
    }

    suspend fun media(url: String): Opened {
        var current = url
        repeat(4) {
            if (!GooglePhotosProtocol.allowedMedia(current)) throw ExtensionFailure("INVALID_RESPONSE")
            // Signed media URLs never receive the account's cookies or the RPC authentication header.
            val opened = open(Request.Builder().url(current).build())
            if (!opened.response.isRedirect) return opened
            current = opened.response.header("Location")?.let { opened.response.request.url.resolve(it)?.toString() }
                ?: run { opened.close(); throw ExtensionFailure("INVALID_RESPONSE") }
            opened.close()
        }
        throw ExtensionFailure("INVALID_RESPONSE")
    }

    private suspend fun open(request: Request) = open(http, request)

    internal class Opened(val response: Response, private val call: Call) : AutoCloseable {
        fun cancel() = call.cancel()
        override fun close() = response.close()
    }
    companion object {
        private suspend fun open(http: OkHttpClient, request: Request): Opened {
            val call = http.newCall(request.newBuilder().header("User-Agent", USER_AGENT).build())
            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            if (!response.isSuccessful && !response.isRedirect) {
                val failure = ExtensionFailure("HTTP_${response.code}", response.header("Retry-After")?.take(128))
                response.close(); throw failure
            }
            return Opened(response, call)
        }
        /** Consume a one-use native web-login URL into an isolated Photos session. No master token is exported. */
        suspend fun connect(loginUrl: String, account: String, http: OkHttpClient): GooglePhotosConfig = withContext(Dispatchers.IO) {
            var url = loginUrl.toHttpUrl()
            if (url.scheme != "https" || url.host != "accounts.google.com" || url.port != 443 ||
                url.encodedPath != "/MergeSession" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.queryParameter("uberauth").isNullOrBlank() || loginUrl.length > 16_384) throw ExtensionFailure("AUTH_UNSUPPORTED")
            val cookies = mutableListOf<Cookie>()
            repeat(8) {
                if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                    url.host !in setOf("accounts.google.com", "photos.google.com")) throw ExtensionFailure("HTTP_401")
                val header = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
                    .joinToString("; ") { "${it.name}=${it.value}" }
                open(http, Request.Builder().url(url).header("Cookie", header).build()).use { opened ->
                    Cookie.parseAll(url, opened.response.headers).forEach { fresh ->
                        cookies.removeAll { it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path }
                        cookies += fresh
                    }
                    if (opened.response.isRedirect) {
                        url = opened.response.header("Location")?.let(url::resolve) ?: throw ExtensionFailure("HTTP_401")
                    } else {
                        if (url.host != "photos.google.com") throw ExtensionFailure("HTTP_401")
                        val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { opened.cancel() }
                        }
                        try {
                            val body = opened.response.body ?: throw ExtensionFailure("INVALID_RESPONSE")
                            if (body.contentLength() > HTML_LIMIT || body.source().request(HTML_LIMIT + 1)) throw ExtensionFailure("INVALID_RESPONSE")
                            val html = body.source().readUtf8()
                            val signedIn = GooglePhotosProtocol.globals(html).account
                            if (signedIn != null && !signedIn.equals(account, ignoreCase = true)) throw ExtensionFailure("HTTP_401")
                            val session = cookies.filter { it.matches("https://photos.google.com/".toHttpUrl()) && it.expiresAt > System.currentTimeMillis() }
                                .joinToString("; ") { "${it.name}=${it.value}" }
                            GooglePhotosCookies.validate(session)
                            // A fresh cookie jar contains exactly the user-selected account, hence account index 0.
                            return@withContext GooglePhotosConfig(session)
                        } finally { cancel.cancel() }
                    }
                }
            }
            throw ExtensionFailure("HTTP_401")
        }
        private const val JSON_LIMIT = 4L * 1024 * 1024
        private const val HTML_LIMIT = 8L * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build()
    }
}
