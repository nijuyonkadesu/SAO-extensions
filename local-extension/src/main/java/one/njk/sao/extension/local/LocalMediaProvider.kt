package one.njk.sao.extension.local

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

/** Read-only streams/thumbnails; no duplicate persistent media cache in the extension. */
class LocalMediaProvider : ContentProvider() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(3))
    private val streams=MediaStreams(scope)
    override fun onCreate()=true
    override fun getType(uri: Uri)="application/octet-stream"
    override fun openFile(uri: Uri, mode: String)=openFile(uri,mode,null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val app=requireNotNull(context)
        require(mode=="r" && one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())!=null)
        val parts=uri.pathSegments; require(parts.size==5)
        return streams.open(signal,{ error -> when(error) {
            is ExtensionFailure -> error
            is SecurityException -> ExtensionFailure("PERMISSION_REQUIRED")
            is java.io.FileNotFoundException -> ExtensionFailure("HTTP_404")
            else -> ExtensionFailure("MEDIA_UNAVAILABLE")
        } }) {
            val store=LocalStore.get(app); val library=store.library(parts[0])
            if(library.revision!=parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
            val asset=store.asset(parts[2].toLong(),library.id)
            if(asset.signature!=parts[3]) throw ExtensionFailure("MEDIA_CHANGED")
            if(parts[4]=="preview") {
                val cancel=CancellationSignal()
                cancellable(cancel::cancel) {
                    val bitmap=MediaBitmaps.read(app,Uri.parse(asset.uri),asset.mime,720,signal=cancel)
                    try { if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,output)) throw java.io.IOException("Thumbnail failed") }
                    finally { bitmap.recycle() }
                }
            } else {
                require(parts[4]=="original")
                copyUri(app,Uri.parse(asset.uri),64L*1024*1024)
            }
        }
    }

    override fun openTypedAssetFile(uri: Uri,mimeTypeFilter: String,opts: android.os.Bundle?,signal: CancellationSignal?): android.content.res.AssetFileDescriptor? {
        if(opts?.getBoolean(one.njk.sao.source.MediaStreamOptions.STREAMING)!=true || uri.lastPathSegment!="original")
            return super.openTypedAssetFile(uri,mimeTypeFilter,opts,signal)
        val app=requireNotNull(context)
        require(one.njk.sao.source.SourceAccess.caller(app,Binder.getCallingUid())!=null)
        val parts=uri.pathSegments; require(parts.size==5)
        return runBlocking(Dispatchers.IO) {
            val store=LocalStore.get(app); val library=store.library(parts[0])
            if(library.revision!=parts[1]) throw java.io.FileNotFoundException("PROFILE_CHANGED")
            val asset=store.asset(parts[2].toLong(),library.id)
            if(asset.signature!=parts[3]) throw java.io.FileNotFoundException("MEDIA_CHANGED")
            app.contentResolver.openAssetFileDescriptor(Uri.parse(asset.uri),"r",signal)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor?=null
    override fun insert(uri: Uri, values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
