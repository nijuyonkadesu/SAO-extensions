package one.njk.sao.extension.googlephotos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import one.njk.sao.extension.runtime.MediaStreams
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.SourceAccess

/** Read-only, bounded HTTP-to-pipe transfer; media is never stored in this APK's data directory. */
class GooglePhotosMediaProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val streams=MediaStreams(scope)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String) = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val app = requireNotNull(context)
        check(mode == "r" && SourceAccess.caller(app, Binder.getCallingUid()) != null)
        val parts = uri.pathSegments
        require(parts.size == 4)
        return streams.open(signal,GooglePhotosRuntime::failure) {
            val runtime=GooglePhotosRuntime.get(app); val row=runtime.profiles.get(parts[0])
            if(row.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val records=GooglePhotosRecords(runtime.profiles); val ref=records.mediaReference(row,parts[3])
            val (mime,opened)=runtime.read(row) { api ->
                val (url,type)=records.variant(ref,parts[2],api)
                type to api.media(url) { android.util.Log.i("SAO.GooglePhotos",it) }
            }
            opened.use { cancellable(opened::cancel) {
                val body=requireNotNull(opened.response.body)
                val limit=when {
                    parts[2]=="playback" -> 8L*1024*1024
                    parts[2]=="preview" && mime=="image/jpeg" -> 16L*1024*1024
                    else -> 1024L*1024*1024
                }
                if(body.contentLength()>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                val input=body.source()
                if(!input.request(12)) throw ExtensionFailure("INVALID_RESPONSE")
                val head=input.peek().readByteArray(12)
                if(head[0]=='<'.code.toByte()) throw ExtensionFailure("INVALID_RESPONSE")
                if(parts[2]=="playback" && !head.copyOfRange(4,8).contentEquals(byteArrayOf(102,116,121,112))) throw ExtensionFailure("UNSUPPORTED")
                val size=copy(body.byteStream(),limit)
                android.util.Log.i("SAO.GooglePhotos","Media ${parts[2]} transferred: bytes=$size")
            } }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
