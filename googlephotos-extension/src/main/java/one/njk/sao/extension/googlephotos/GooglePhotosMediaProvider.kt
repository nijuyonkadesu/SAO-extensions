package one.njk.sao.extension.googlephotos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.SourceAccess
import org.json.JSONObject

/** Read-only, bounded HTTP-to-pipe transfer; media is never stored in this APK's data directory. */
class GooglePhotosMediaProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String) = openFile(uri, mode, null)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val app = requireNotNull(context)
        check(mode == "r" && SourceAccess.caller(app, Binder.getCallingUid()) != null)
        val parts = uri.pathSegments
        require(parts.size == 4)
        // A socket pair keeps the same sequential read contract, with EOF when the reader closes.
        // Unlike an opening CancellationSignal, this also covers cancellation after openFile returns.
        val pipe = ParcelFileDescriptor.createReliableSocketPair()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var stage = "resolve reference"
            try {
                val runtime = GooglePhotosRuntime.get(app)
                val row = runtime.profiles.get(parts[0])
                if (row.revision != parts[1]) throw ExtensionFailure("PROFILE_CHANGED")
                val records = GooglePhotosRecords(runtime.profiles)
                val ref = records.mediaReference(row, parts[3])
                val api = runtime.api(row)
                stage = "resolve variant"
                val (url, mime) = records.variant(ref, parts[2], api)
                stage = "open HTTP"
                coroutineScope {
                    val finished = java.util.concurrent.atomic.AtomicBoolean()
                    val transfer = currentCoroutineContext().job
                    val peer = launch(Dispatchers.IO) {
                        try { Os.read(pipe[1].fileDescriptor, ByteArray(1), 0, 1) }
                        catch (_: android.system.ErrnoException) { }
                        finally { if (!finished.get()) transfer.cancel() }
                    }
                    try { api.media(url) { android.util.Log.i("SAO.GooglePhotos", it) }.use { opened ->
                        val cancel = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { opened.cancel() }
                        }
                        try {
                            stage = "validate body"
                            val body = requireNotNull(opened.response.body)
                            val limit = if (parts[2] == "preview" && mime == "image/jpeg") 16L * 1024 * 1024 else 1024L * 1024 * 1024
                            if (body.contentLength() > limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                            val input = body.source()
                            if (!input.request(12)) throw ExtensionFailure("INVALID_RESPONSE")
                            val head = input.peek().readByteArray(12)
                            if (head[0] == '<'.code.toByte()) throw ExtensionFailure("INVALID_RESPONSE")
                            val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                            val buffer = ByteArray(32 * 1024)
                            var size = 0L
                            stage = "stream bytes"
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                if (size > limit) throw ExtensionFailure("MEDIA_TOO_LARGE")
                                output.write(buffer, 0, count)
                            }
                            android.util.Log.i("SAO.GooglePhotos", "Media ${parts[2]} transferred: bytes=$size")
                        } finally { cancel.cancel() }
                    } } finally {
                        finished.set(true)
                        // Wake the single blocking peer read; no timer or descriptor polling.
                        try { Os.shutdown(pipe[1].fileDescriptor, OsConstants.SHUT_RD) }
                        catch (_: android.system.ErrnoException) { }
                        peer.cancel()
                    }
                }
                pipe[1].close()
            } catch (error: Exception) {
                val failure = GooglePhotosRuntime.failure(error)
                if (error !is CancellationException) android.util.Log.w("SAO.GooglePhotos",
                    "Media ${parts[2]} failed at $stage: ${failure.code} (${error.javaClass.simpleName})\n" +
                        error.stackTrace.take(5).joinToString("\n") { "at $it" })
                try { pipe[1].closeWithError(JSONObject().put("code", failure.code).put("retryAfter", failure.retryAfter).toString()) }
                catch (_: java.io.IOException) { }
            }
        }
        job.invokeOnCompletion { try { pipe[1].close() } catch (_: java.io.IOException) { } }
        signal?.setOnCancelListener { job.cancel() }
        job.start()
        return pipe[0]
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun shutdown() { scope.cancel(); super.shutdown() }
}
