package one.njk.sao.extension.googlephotos

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import one.njk.sao.source.ExtensionFailure

/** No scheduled work: only an expired, actively requested read can enter this coordinator. */
internal class GooglePhotosSessionRenewal(private val profiles: GooglePhotosProfiles,
    private val connect: suspend (String) -> GooglePhotosConfig,
    private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val lock=Mutex()
    private data class Failed(val code: String, val until: Long, val retryAfter: String?)
    private val failures=object : LinkedHashMap<String,Failed>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Failed>?)=size>32
    }

    suspend fun renew(expected: GooglePhotosProfile): GooglePhotosProfile = try {
        withTimeout(20_000) { lock.withLock {
            val current=profiles.get(expected.id)
            if(current.revision!=expected.revision) throw ExtensionFailure("PROFILE_CHANGED")
            // The first failed read renews; concurrent failures reuse its new session.
            if(current.config.nativeSession!=expected.config.nativeSession) return@withLock current
            if(current.config.nativeAccount.isEmpty() || current.config.nativeSession.isEmpty()) throw ExtensionFailure("HTTP_401")
            val key="${current.id}:${current.revision}:${current.config.nativeSession}"
            failures[key]?.takeIf { now()<it.until }?.let { throw ExtensionFailure(it.code,it.retryAfter) }
            try {
                val fresh=withTimeout(15_000) { connect(current.config.nativeAccount) }
                profiles.renewSession(current,fresh).also {
                    failures.remove(key)
                    android.util.Log.i("SAO.GooglePhotos","Account session renewed")
                }
            } catch(error: Exception) {
                if(error is CancellationException && error !is TimeoutCancellationException) throw error
                val code=if(error is TimeoutCancellationException) "TIMEOUT" else GooglePhotosRuntime.failure(error).code
                if(code=="PROFILE_CHANGED" || code=="PROFILE_MISSING") throw error
                // Credential/user-interaction failures stay latched until an explicit reconnect.
                // Network failures get a passive cooldown, never a timer or background retry.
                val transient=code in setOf("NETWORK","TIMEOUT","DISCONNECTED","HTTP_408","HTTP_429","HTTP_500","HTTP_502","HTTP_503","HTTP_504")
                val reported=if(transient) code else "HTTP_401"
                val retryAfter=(error as? ExtensionFailure)?.retryAfter
                val serverDelay=retryAfter?.toLongOrNull()?.coerceIn(0,Long.MAX_VALUE/1000)?.times(1000)
                    ?: runCatching { java.time.ZonedDateTime.parse(retryAfter,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli()-System.currentTimeMillis() }.getOrDefault(0)
                val cooldown=maxOf(60_000,serverDelay).coerceAtMost(Long.MAX_VALUE-now())
                failures[key]=Failed(reported,if(transient) now()+cooldown else Long.MAX_VALUE,retryAfter)
                throw ExtensionFailure(reported,retryAfter)
            }
        } }
    } catch(_: TimeoutCancellationException) { throw ExtensionFailure("TIMEOUT") }
}

/** One short-lived call wakes Photos' provider; it opens no activity and retains no binding. */
internal object GooglePhotosNativeRenewal {
    const val AUTHORITY="com.google.android.apps.photos.backup.apiservice.status.BackupStatusContentProvider"
    const val METHOD="one.njk.sao.googlephotos.RENEW_SESSION"
    suspend fun connect(context: Context, account: String, http: OkHttpClient): GooglePhotosConfig {
        withContext(Dispatchers.IO) {
            val info=context.packageManager.getApplicationInfo(GooglePhotosConnect.PHOTOS,0)
            if(!GooglePhotosConnect.isPhotos(context.packageManager,info.uid)) throw ExtensionFailure("HTTP_401")
        }
        val pending=GooglePhotosConnect.begin(account)
        var visibility: Uri?=null
        var provider: android.content.ContentProviderClient?=null
        try {
            withContext(Dispatchers.IO) {
                visibility=GooglePhotosVisibility.grant(context)
                provider=context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY) ?: throw ExtensionFailure("HTTP_401")
                val response=provider!!.call(METHOD,pending.nonce,Bundle().apply { putString("account",account) })
                if(response?.getBoolean("accepted")!=true) throw ExtensionFailure("HTTP_401")
            }
            val result=pending.result.await()
            withContext(Dispatchers.IO) { provider?.close(); provider=null }
            return GooglePhotosApi.connect(result.loginUrl,result.account,http)
        } finally {
            GooglePhotosConnect.cancel(pending.nonce)
            withContext(NonCancellable+Dispatchers.IO) {
                try { provider?.close() } finally { visibility?.let { GooglePhotosVisibility.revoke(context,it) } }
            }
        }
    }
}
