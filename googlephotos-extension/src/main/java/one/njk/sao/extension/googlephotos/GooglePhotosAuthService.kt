package one.njk.sao.extension.googlephotos

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import one.njk.sao.source.ExtensionFailure
import java.security.MessageDigest
import java.security.SecureRandom

/** A single, UI-initiated handshake, not a continuously connected authentication bridge. */
internal object GooglePhotosConnect {
    const val PHOTOS = "com.google.android.apps.photos"
    const val EXTRA = "one.njk.sao.googlephotos.CONNECT"
    data class Result(val account: String, val loginUrl: String)
    class Pending(val nonce: String, val started: Long, val result: CompletableDeferred<Result>, val expectedAccount: String?) {
        var claimed = false
    }
    private var pending: Pending? = null
    @Synchronized fun begin(expectedAccount: String? = null): Pending {
        pending?.result?.completeExceptionally(ExtensionFailure("CONNECT_CANCELLED"))
        return Pending(android.util.Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
            SystemClock.elapsedRealtime(), CompletableDeferred(), expectedAccount).also { pending = it }
    }
    @Synchronized fun claim(nonce: String): Boolean {
        val active = active(nonce) ?: return false
        if (active.claimed) return false
        active.claimed = true
        return true
    }
    @Synchronized fun complete(nonce: String, account: String, url: String, failure: String) {
        val active = active(nonce)?.takeIf { it.claimed } ?: return
        pending = null
        if (failure.isNotEmpty()) active.result.completeExceptionally(ExtensionFailure(
            failure.takeIf { it in setOf("ACCOUNT_MISSING", "CONNECT_CANCELLED", "AUTH_UNSUPPORTED", "HTTP_401") } ?: "HTTP_401"))
        else if (active.expectedAccount != null && !active.expectedAccount.equals(account, ignoreCase = true))
            active.result.completeExceptionally(ExtensionFailure("HTTP_401"))
        else if (account.length !in 3..320 || url.length !in 1..16_384) active.result.completeExceptionally(ExtensionFailure("INVALID_RESPONSE"))
        else active.result.complete(Result(account, url))
    }
    @Synchronized fun cancel(nonce: String) {
        if (pending?.nonce == nonce) { pending?.result?.cancel(); pending = null }
    }
    private fun active(nonce: String) = pending?.takeIf {
        nonce == it.nonce && !it.result.isCompleted && SystemClock.elapsedRealtime() - it.started in 0..120_000
    }

    fun isPhotos(pm: PackageManager, uid: Int): Boolean = try {
        if (PHOTOS !in pm.getPackagesForUid(uid).orEmpty()) false
        else {
            val info = pm.getPackageInfo(PHOTOS, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }.orEmpty()
            signatures.any { signature -> MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) } == "24bb24c05e47e0aefa68a58a766179d9b613a600" }
        }
    } catch (_: PackageManager.NameNotFoundException) { false }
}

/** Exported only because the caller runs as Google Photos. Every transaction checks its real UID and Google certificate. */
class GooglePhotosAuthService : Service() {
    private fun authorize() {
        if (!GooglePhotosConnect.isPhotos(packageManager, Binder.getCallingUid())) throw SecurityException("Google Photos only")
    }
    private val broker = object : IGooglePhotosAuthBroker.Stub() {
        override fun claim(nonce: String): Boolean { authorize(); return nonce.length == 43 && GooglePhotosConnect.claim(nonce) }
        override fun complete(nonce: String, account: String, loginUrl: String, failure: String) {
            authorize()
            if (nonce.length == 43 && account.length <= 320 && loginUrl.length <= 16_384 && failure.length <= 80)
                GooglePhotosConnect.complete(nonce, account, loginUrl, failure)
        }
    }
    override fun onBind(intent: Intent): IBinder = broker
}
