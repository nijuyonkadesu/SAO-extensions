package one.njk.sao.extension.moeview

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

/** Read-only cancellable socket-to-pipe transfer. The host exclusively owns downloaded media. */
class MoeviewMediaProvider : ContentProvider() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(3))
    private val streams=MediaStreams(scope)
    override fun onCreate()=true
    override fun getType(uri: Uri)="application/octet-stream"
    override fun openFile(uri: Uri,mode: String)=openFile(uri,mode,null)
    override fun openFile(uri: Uri,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        val app=requireNotNull(context)
        check(mode=="r" && one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())!=null)
        val parts=uri.pathSegments; require(parts.size==4)
        return streams.open(signal,MoeviewRuntime::failure) {
            val runtime=MoeviewRuntime.get(app); val profile=runtime.profiles.get(parts[0])
            if(profile.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val records=MoeviewRecords(runtime.profiles); val ref=records.reference(profile,parts[3])
            val variant=records.variant(ref,parts[2]); val post=records.post(profile,parts[3])
            runtime.use(profile) { api ->
                val media=api.openMedia(profile.config.board,post,variant)
                cancellable(media::cancel) { media.use { response ->
                    val fullSize=variant==one.njk.sao.moeview.data.MediaKind.FULL || ref.getString("preview")==ref.getString("file")
                    val limit=if(fullSize) 64L*1024*1024 else 8L*1024*1024
                    if(response.contentLength>limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                    copy(response.byteStream(),limit)
                } }
            }
        }
    }

    override fun openTypedAssetFile(uri: Uri,mimeTypeFilter: String,opts: android.os.Bundle?,signal: android.os.CancellationSignal?): android.content.res.AssetFileDescriptor? {
        if(opts?.getBoolean(one.njk.sao.source.MediaStreamOptions.STREAMING)!=true) return super.openTypedAssetFile(uri,mimeTypeFilter,opts,signal)
        val app=requireNotNull(context)
        check(one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())!=null)
        val parts=uri.pathSegments; require(parts.size==4)
        return try { streams.openSeekable(app,signal,opts.getLong(one.njk.sao.source.MediaStreamOptions.POSITION),MoeviewRuntime::failure) {
            val runtime=MoeviewRuntime.get(app); val profile=runtime.profiles.get(parts[0])
            if(profile.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val records=MoeviewRecords(runtime.profiles); val ref=records.reference(profile,parts[3])
            val variant=records.variant(ref,parts[2]); val post=records.post(profile,parts[3])
            runtime.use(profile) { api -> serve { offset,validator ->
                val response=api.openMedia(profile.config.board,post,variant,one.njk.sao.moeview.data.ByteRange(offset),validator)
                one.njk.sao.extension.runtime.SeekableMedia.Response.http(response.byteStream(),response.statusCode,response.contentLength,
                    response.contentRange,offset,response.validator,response::cancel,response::close)
            } }
        } } catch(error: ExtensionFailure) {
            if(error.code=="STREAM_UNSEEKABLE") super.openTypedAssetFile(uri,mimeTypeFilter,opts,signal)
            else throw java.io.FileNotFoundException(error.code)
        }
    }

    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,order: String?): Cursor?=null
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
