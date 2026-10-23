package one.njk.sao.extension.immich

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import one.njk.sao.extension.runtime.MediaStreams
import one.njk.sao.immichprovider.ImmichAdapter
import one.njk.sao.source.ExtensionFailure

/** Authenticated one-shot stream. No extension-owned media downloads or cache directory. */
class ImmichMediaProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val streams=MediaStreams(scope)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri,mode,null)
    override fun openFile(uri: Uri, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
        val app = requireNotNull(context)
        val caller=one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())
        check(caller != null && mode == "r") { "Read access denied" }
        val parts = uri.pathSegments; require(parts.size == 4)
        return streams.open(signal,{ error -> when(error) {
            is ExtensionFailure -> error
            is javax.net.ssl.SSLException -> ExtensionFailure("TLS")
            else -> ExtensionFailure("NETWORK")
        } }) {
            val connection=ConnectionStore(app).get(parts[0])
            if(connection.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            ImmichAdapter(connection.config,"stream").use { adapter ->
                val call=adapter.mediaCall(parts[3],parts[2],uri.getQueryParameter("edited")=="true")
                cancellable(call::cancel) {
                    call.execute().use { response ->
                        if(!response.isSuccessful) throw ExtensionFailure("HTTP_${response.code}",response.header("Retry-After")?.take(128))
                        val body=requireNotNull(response.body)
                        val limit=64L*1024*1024
                        if(body.contentLength()>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                        body.byteStream().use { copy(it,limit) }
                    }
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
