package one.njk.sao.extension.runtime

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import one.njk.sao.source.ExtensionFailure
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Disposable sockets and streams only; no accounts, providers, or network requests. */
class MediaStreamsDeviceTest {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(3))
    private val streams=MediaStreams(scope)
    private val descriptors=mutableListOf<ParcelFileDescriptor>()
    private fun open(signal: CancellationSignal?=null, produce: suspend MediaStreams.Writer.() -> Unit)=
        streams.open(signal,{ it as? ExtensionFailure ?: ExtensionFailure("MEDIA_UNAVAILABLE") },produce)
            .also(descriptors::add)

    @After fun cleanup(): Unit=runBlocking {
        descriptors.forEach { runCatching { it.close() } }
        scope.cancel()
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
    }

    @Test fun peerCloseCancelsAnUpstreamReadBeforeItProducesAnyBytes(): Unit=runBlocking {
        val input=StalledInput()
        val finished=CompletableDeferred<Unit>()
        val reader=open {
            try { cancellable(input::close) { input.use { copy(it,1024) } } }
            finally { finished.complete(Unit) }
        }
        assertTrue("The synthetic upstream did not begin reading",input.entered.await(5,TimeUnit.SECONDS))
        reader.close()
        assertTrue("Peer close did not close the stalled upstream",input.closed.await(5,TimeUnit.SECONDS))
        withTimeout(5_000) { finished.await() }
    }

    @Test fun cancellationReleasesAWriterBlockedOnAnUndrainedSocket(): Unit=runBlocking {
        val signal=CancellationSignal()
        val entered=CompletableDeferred<Unit>(); val finished=CompletableDeferred<Unit>()
        open(signal) {
            try {
                val bytes=ByteArray(8*1024*1024)
                entered.complete(Unit)
                output.write(bytes)
            } finally { finished.complete(Unit) }
        }
        withTimeout(5_000) { entered.await() }
        // An undrained socket cannot accept this whole payload. Give its blocking write time to start.
        delay(100)
        assertFalse("The fixture did not block the writer",finished.isCompleted)
        signal.cancel()
        withTimeout(5_000) { finished.await() }
    }

    @Test fun transferLimitRemainsHeldAcrossCoroutineSuspension(): Unit=runBlocking {
        val started=List(3) { CompletableDeferred<Unit>() }
        val release=List(3) { CompletableDeferred<Unit>() }
        repeat(3) { index -> open { started[index].complete(Unit); release[index].await() } }
        withTimeout(5_000) { started.forEach { it.await() } }
        val fourth=CompletableDeferred<Unit>()
        open { fourth.complete(Unit) }
        assertNull("A suspended transfer released its permit",withTimeoutOrNull(200) { fourth.await() })
        release.first().complete(Unit)
        withTimeout(5_000) { fourth.await() }
        release.forEach { it.complete(Unit) }
    }

    @Test fun peerCloseCancelsAQueuedTransferBeforeAPermitBecomesAvailable(): Unit=runBlocking {
        val started=List(3) { CompletableDeferred<Unit>() }; val release=CompletableDeferred<Unit>()
        repeat(3) { index -> open { started[index].complete(Unit); release.await() } }
        withTimeout(5_000) { started.forEach { it.await() } }
        val existing=scope.coroutineContext.job.children.toSet()
        val opened=AtomicInteger()
        val reader=open { opened.incrementAndGet() }
        val queued=scope.coroutineContext.job.children.single { it !in existing }
        reader.close()
        withTimeout(5_000) { queued.join() }
        assertEquals("A closed queued stream opened its upstream",0,opened.get())
        release.complete(Unit)
    }

    @Test fun signalCancellationWhileWaitingForAPermitNeverOpensTheUpstream(): Unit=runBlocking {
        val started=List(3) { CompletableDeferred<Unit>() }; val release=CompletableDeferred<Unit>()
        repeat(3) { index -> open { started[index].complete(Unit); release.await() } }
        withTimeout(5_000) { started.forEach { it.await() } }
        val existing=scope.coroutineContext.job.children.toSet()
        val signal=CancellationSignal(); val opened=AtomicInteger()
        open(signal) { opened.incrementAndGet() }
        val queued=scope.coroutineContext.job.children.single { it !in existing }
        signal.cancel()
        withTimeout(5_000) { queued.join() }
        release.complete(Unit)
        assertEquals(0,opened.get())
    }

    @Test fun signalCancelledBeforeOpenDoesNotStartAProducer(): Unit=runBlocking {
        val signal=CancellationSignal().apply { cancel() }; val opened=AtomicInteger()
        val reader=open(signal) { opened.incrementAndGet() }
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
        assertEquals(0,opened.get())
        assertEquals(-1,ParcelFileDescriptor.AutoCloseInputStream(reader).use { it.read() })
    }

    @Test fun stoppedProviderScopeDoesNotStartAProducer(): Unit=runBlocking {
        scope.cancel()
        val opened=AtomicInteger()
        val reader=open { opened.incrementAndGet() }
        assertEquals(0,opened.get())
        assertEquals(-1,ParcelFileDescriptor.AutoCloseInputStream(reader).use { it.read() })
    }

    private class StalledInput : InputStream() {
        val entered=CountDownLatch(1)
        val closed=CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            if(!closed.await(10,TimeUnit.SECONDS)) throw IOException("Synthetic stream was not cancelled")
            throw IOException("Synthetic stream closed")
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int)=read()
        override fun close() { closed.countDown() }
    }
}
