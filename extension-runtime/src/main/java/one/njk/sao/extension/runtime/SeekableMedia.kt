package one.njk.sao.extension.runtime

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.*
import one.njk.sao.source.*
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/** A remote file, not a download/cache. One HTTP response is reused until the reader actually seeks. */
class SeekableMedia internal constructor(private val context: Context,
    private val ready: CompletableDeferred<AssetFileDescriptor>, private val start: Long, private val control: IMediaReadControl) {
    class Response(val input: InputStream, val total: Long, val offset: Long, val validator: String?,
        private val cancelRead: () -> Unit, private val closeRead: () -> Unit, val endExclusive: Long = total) : Closeable {
        fun cancel() = cancelRead()
        override fun close() { cancelRead(); closeRead() }
        companion object {
            fun http(input: InputStream, status: Int, length: Long, range: String?, requested: Long,
                validator: String?, cancel: () -> Unit, close: () -> Unit): Response {
                try {
                    if (status !in 200..299) throw ExtensionFailure("HTTP_$status")
                    if (status == 206) {
                        val fields = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty())
                            ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: throw ExtensionFailure("INVALID_RESPONSE") }
                            ?: throw ExtensionFailure("INVALID_RESPONSE")
                        val (first, last, total) = fields
                        if (first != requested || last < first || total <= last || length >= 0 && length != last - first + 1)
                            throw ExtensionFailure("INVALID_RESPONSE")
                        return Response(input, total, first, validator, cancel, close, last + 1)
                    }
                    if (requested != 0L) throw ExtensionFailure("STREAM_UNSEEKABLE")
                    return Response(input, length, 0, validator, cancel, close)
                } catch (error: Exception) { cancel(); close(); throw error }
            }
        }
    }
    suspend fun serve(open: suspend (Long, String?) -> Response) = coroutineScope {
        val current = AtomicReference<Response?>()
        val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { current.get()?.cancel() }
        }
        var callbacks: HandlerThread? = null
        val released = CompletableDeferred<Unit>()
        try {
            val first = open(start, null).also(current::set)
            if (first.total < 0) throw ExtensionFailure("STREAM_UNSEEKABLE")
            if (first.total == 0L || first.offset != start) throw ExtensionFailure("INVALID_RESPONSE")
            currentCoroutineContext().ensureActive()
            suspend fun replace(offset: Long) {
                current.getAndSet(null)?.close()
                val replacement=open(offset,first.validator)
                if(replacement.total!=first.total || replacement.offset!=offset || replacement.endExclusive<=offset ||
                    first.validator!=null && replacement.validator!=first.validator) {
                    replacement.close(); throw ExtensionFailure("MEDIA_CHANGED")
                }
                current.set(replacement)
            }
            val work = currentCoroutineContext()
            var cursor = start
            val thread = HandlerThread("SAO source reads", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.also { callbacks = it }
            val file = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = first.total
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        runBlocking(work) {
                            ensureActive()
                            if (offset >= first.total) return@runBlocking 0
                            if(offset!=cursor) { replace(offset); cursor=offset }
                            val wanted = minOf(size.toLong(), first.total - offset).toInt()
                            var read = 0
                            while (read < wanted) {
                                ensureActive()
                                if(cursor==requireNotNull(current.get()).endExclusive) replace(cursor)
                                val response=requireNotNull(current.get())
                                val count=minOf((wanted-read).toLong(),response.endExclusive-cursor).toInt()
                                val n=response.input.read(data,read,count)
                                if(n<=0) throw ExtensionFailure("INVALID_RESPONSE")
                                read+=n; cursor+=n
                            }
                            read
                        }
                    } catch (error: Exception) {
                        throw ErrnoException("Media read", when {
                            error is CancellationException -> OsConstants.EINTR
                            error is ExtensionFailure && error.code == "STREAM_UNSEEKABLE" -> OsConstants.ESPIPE
                            error is ExtensionFailure && error.code in setOf("HTTP_401", "HTTP_403") -> OsConstants.EACCES
                            else -> OsConstants.EIO
                        })
                    }
                    override fun onRelease() { released.complete(Unit) }
                }, Handler(thread.looper))
            val extras = Bundle().apply { putBinder(MediaStreamOptions.CONTROL, control.asBinder()) }
            if (!ready.complete(AssetFileDescriptor(file, 0, AssetFileDescriptor.UNKNOWN_LENGTH, extras))) file.close()
            released.await()
        } finally {
            closer.cancel()
            current.getAndSet(null)?.close()
            callbacks?.quitSafely()
            withContext(NonCancellable + Dispatchers.IO) { callbacks?.join() }
        }
    }
}
