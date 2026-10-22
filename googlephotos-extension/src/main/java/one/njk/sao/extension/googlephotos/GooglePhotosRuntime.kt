package one.njk.sao.extension.googlephotos

import android.content.Context
import one.njk.sao.source.ExtensionFailure

/** A single lazy HTTP pool; no result/media cache, polling, service, or scheduled work. */
internal class GooglePhotosRuntime private constructor(context: Context) {
    val profiles = GooglePhotosProfiles(context)
    private val production by lazy { GooglePhotosApi.client() }
    internal var fixture: okhttp3.OkHttpClient? = null
    private val sessions = object : LinkedHashMap<String, GooglePhotosApi>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GooglePhotosApi>?) = size > 4
    }
    fun api(row: GooglePhotosProfile): GooglePhotosApi = synchronized(sessions) {
        sessions.getOrPut("${row.id}:${row.revision}") {
            GooglePhotosApi(row.config, fixture ?: production) {
                profiles.refreshCookies(row.id, row.revision, it.cookies, it.scopedCookies)
            }
        }
    }
    fun check(config: GooglePhotosConfig) = GooglePhotosApi(config, fixture ?: production)
    fun http() = fixture ?: production
    internal fun clearSessions() = synchronized(sessions) { sessions.clear() }

    companion object {
        @Volatile private var instance: GooglePhotosRuntime? = null
        fun get(context: Context): GooglePhotosRuntime = instance ?: synchronized(this) {
            instance ?: GooglePhotosRuntime(context.applicationContext).also { instance = it }
        }
        fun failure(error: Exception): ExtensionFailure = when (error) {
            is ExtensionFailure -> error
            is org.json.JSONException -> ExtensionFailure("INVALID_RESPONSE")
            is javax.net.ssl.SSLException -> ExtensionFailure("TLS")
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> ExtensionFailure("TIMEOUT")
            is java.io.IOException -> ExtensionFailure("NETWORK")
            else -> ExtensionFailure("REQUEST_FAILED")
        }
    }
}
