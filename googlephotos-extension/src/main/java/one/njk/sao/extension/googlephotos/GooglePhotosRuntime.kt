package one.njk.sao.extension.googlephotos

import android.content.Context
import one.njk.sao.source.ExtensionFailure

/** A single lazy HTTP pool; no result/media cache, polling, service, or scheduled work. */
internal class GooglePhotosRuntime private constructor(private val context: Context) {
    val profiles = GooglePhotosProfiles(context)
    private val production by lazy { GooglePhotosApi.client() }
    internal var fixture: okhttp3.OkHttpClient? = null
    internal var fixtureRenewal: (suspend (String) -> GooglePhotosConfig)? = null
    private val renewal=GooglePhotosSessionRenewal(profiles,{ account ->
        fixtureRenewal?.invoke(account) ?: GooglePhotosNativeRenewal.connect(context,account,http())
    })
    private val sessions = object : LinkedHashMap<String, GooglePhotosApi>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GooglePhotosApi>?) = size > 4
    }
    fun api(row: GooglePhotosProfile): GooglePhotosApi = synchronized(sessions) {
        sessions.getOrPut("${row.id}:${row.revision}:${row.config.nativeSession}") {
            GooglePhotosApi(row.config, fixture ?: production) {
                profiles.refreshCookies(row.id, row.revision, it.cookies, it.scopedCookies, row.config.nativeSession)
            }
        }
    }
    suspend fun <T> read(row: GooglePhotosProfile, block: suspend (GooglePhotosApi) -> T): T {
        val current=api(row)
        try { return block(current) }
        catch(error: ExtensionFailure) {
            if(error.code!="HTTP_401" && error.code!="HTTP_403") throw error
            if(error.code=="HTTP_403") {
                // A valid login plus a 403 is not evidence that Google wants a new login.
                // Refresh bootstrap/CSRF once; permission failures never launch renewal.
                try { current.check(); return block(current) }
                catch(checked: ExtensionFailure) { if(checked.code!="HTTP_401") throw checked }
            }
            return block(api(renewal.renew(row)))
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
