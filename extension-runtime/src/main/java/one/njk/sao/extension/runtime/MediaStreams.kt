package one.njk.sao.extension.runtime

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.content.res.AssetFileDescriptor
import android.os.Binder
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import one.njk.sao.source.ExtensionFailure
import one.njk.sao.source.readImportStream
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Shared extension-side lifetime only: the host owns media caching and request scheduling. */
class MediaStreams(private val scope: CoroutineScope) {
    private val active = Semaphore(3)
    fun open(signal: CancellationSignal?, failure: (Exception) -> ExtensionFailure,
        produce: suspend Writer.() -> Unit): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createReliableSocketPair()
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    val finished = AtomicBoolean()
                    val transfer = currentCoroutineContext().job
                    val closer = launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() }
                        finally {
                            // Cancellation must also release a writer blocked on an undrained socket.
                            runCatching {
                                Os.shutdown(pair[1].fileDescriptor,
                                    if(finished.get()) OsConstants.SHUT_RD else OsConstants.SHUT_RDWR)
                            }
                        }
                    }
                    // Observe peer close before permit acquisition, including queued requests.
                    val peer = launch(Dispatchers.IO) {
                        try { Os.read(pair[1].fileDescriptor,ByteArray(1),0,1) }
                        catch (_: android.system.ErrnoException) { }
                        finally { if(!finished.get()) transfer.cancel() }
                    }
                    try { active.withPermit {
                        currentCoroutineContext().ensureActive()
                        Writer(ParcelFileDescriptor.AutoCloseOutputStream(pair[1])).produce()
                    } }
                    finally {
                        finished.set(true)
                        closer.cancel()
                        peer.cancel()
                    }
                }
                pair[1].close()
            } catch (error: Exception) {
                val problem = failure(error)
                runCatching { pair[1].closeWithError(JSONObject().put("code",problem.code).put("retryAfter",problem.retryAfter).toString()) }
            }
        }
        job.invokeOnCompletion { runCatching { pair[1].close() } }
        signal?.setOnCancelListener { job.cancel() }
        job.start()
        return pair[0]
    }

    fun openSeekable(context: Context, signal: CancellationSignal?, position: Long,
        failure: (Exception) -> ExtensionFailure, produce: suspend SeekableMedia.() -> Unit): AssetFileDescriptor {
        require(position >= 0)
        val ready = CompletableDeferred<AssetFileDescriptor>()
        val caller = Binder.getCallingUid()
        lateinit var job: Job
        val control = object : one.njk.sao.source.IMediaReadControl.Stub() {
            override fun cancel() { if (Binder.getCallingUid() == caller) job.cancel() }
        }
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { active.withPermit { SeekableMedia(context, ready, position, control).produce() } }
            catch (error: Exception) { ready.completeExceptionally(failure(error)) }
        }
        signal?.setOnCancelListener { job.cancel() }
        job.invokeOnCompletion { error -> if (!ready.isCompleted) ready.completeExceptionally(error ?: ExtensionFailure("MEDIA_UNAVAILABLE")) }
        job.start()
        return runBlocking { ready.await() }
    }

    class Writer internal constructor(val output: OutputStream) {
        suspend fun <T> cancellable(cancel: () -> Unit, block: suspend () -> T): T = coroutineScope {
            val closer = launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runCatching(cancel) }
            }
            try { currentCoroutineContext().ensureActive(); block() } finally { closer.cancel() }
        }
        suspend fun copy(input: InputStream, maxBytes: Long): Long = copy(input,maxBytes,currentCoroutineContext())
        suspend fun copyUri(context: Context, uri: Uri, maxBytes: Long): Long {
            val work=currentCoroutineContext()
            // This SDK primitive installs its CancellationSignal before opening a cloud/SAF URI.
            return readImportStream(context,uri,work) { copy(it,maxBytes,work) }
        }
        private fun copy(input: InputStream, maxBytes: Long, work: kotlin.coroutines.CoroutineContext): Long {
            val buffer=ByteArray(32*1024); var bytes=0L
            while(true) {
                work.ensureActive()
                val count=input.read(buffer); if(count<0) break
                bytes+=count; if(bytes>maxBytes) throw ExtensionFailure("MEDIA_TOO_LARGE")
                output.write(buffer,0,count)
            }
            return bytes
        }
    }
}
