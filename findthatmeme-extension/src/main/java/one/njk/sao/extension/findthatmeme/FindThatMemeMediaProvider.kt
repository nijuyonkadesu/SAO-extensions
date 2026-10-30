package one.njk.sao.extension.findthatmeme

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
class FindThatMemeMediaProvider : ContentProvider() {
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
        return streams.open(signal,FindThatMemeRuntime::failure) {
            val runtime=FindThatMemeRuntime.get(app); val row=runtime.profiles.get(parts[0])
            if(row.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val records=FindThatMemeRecords(runtime.profiles)
            val ref=records.reference(row,parts[3]); val (url,mime)=records.variant(ref,parts[2])
            runtime.api.media(url).use { opened -> cancellable(opened::cancel) {
                val body=requireNotNull(opened.response.body)
                val limit=if(url==ref.getString("file")) 64L*1024*1024 else 8L*1024*1024
                if(body.contentLength()>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                val input=body.source()
                if(!input.request(12) || !FindThatMemeApi.matches(input.peek().readByteArray(12),mime)) throw ExtensionFailure("INVALID_RESPONSE")
                copy(body.byteStream(),limit)
            } }
        }
    }

    override fun openTypedAssetFile(uri: Uri,mimeTypeFilter: String,opts: android.os.Bundle?,signal: android.os.CancellationSignal?): android.content.res.AssetFileDescriptor? {
        if(opts?.getBoolean(one.njk.sao.source.MediaStreamOptions.STREAMING)!=true) return super.openTypedAssetFile(uri,mimeTypeFilter,opts,signal)
        val app=requireNotNull(context)
        check(one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())!=null)
        val parts=uri.pathSegments; require(parts.size==4)
        return try { streams.openSeekable(app,signal,opts.getLong(one.njk.sao.source.MediaStreamOptions.POSITION),FindThatMemeRuntime::failure) {
            val runtime=FindThatMemeRuntime.get(app); val row=runtime.profiles.get(parts[0])
            if(row.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val records=FindThatMemeRecords(runtime.profiles); val ref=records.reference(row,parts[3])
            val (url,_)=records.variant(ref,parts[2])
            serve { offset,validator ->
                val opened=runtime.api.media(url,offset,validator)
                val response=opened.response; val body=requireNotNull(response.body)
                one.njk.sao.extension.runtime.SeekableMedia.Response.http(body.byteStream(),response.code,body.contentLength(),
                    response.header("Content-Range"),offset,response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified"),opened::cancel,opened::close)
            }
        } } catch(error: ExtensionFailure) {
            if(error.code=="STREAM_UNSEEKABLE") super.openTypedAssetFile(uri,mimeTypeFilter,opts,signal)
            else throw java.io.FileNotFoundException(error.code)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
