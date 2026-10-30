package one.njk.sao.extension.runtime

import android.os.CancellationSignal
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.ExtensionFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SeekableMediaDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun rangesAreProgressiveSeekableAndHoldResourcesUntilClose(): Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val streams=MediaStreams(scope)
        val fetched=AtomicLong(); val calls=mutableListOf<Long>(); val released=CompletableDeferred<Unit>()
        val total=32L*1024*1024
        try {
            val asset=streams.openSeekable(app,null,0,{ it as? ExtensionFailure ?: ExtensionFailure("NETWORK") }) {
                try { serve { start,_ ->
                    synchronized(calls) { calls+=start }
                    var cursor=start
                    val input=object : InputStream() {
                        override fun read()=error("Use block reads")
                        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                            val count=minOf(length.toLong(),total-cursor).toInt()
                            repeat(count) { bytes[offset+it]=((cursor+it)%251).toByte() }; cursor+=count; fetched.addAndGet(count.toLong()); return count
                        }
                    }
                    SeekableMedia.Response.http(input,206,total-start,"bytes $start-${total-1}/$total",start,"fixture",{}, {})
                } } finally { released.complete(Unit) }
            }
            asset.createInputStream().use { input ->
                assertEquals(total,input.channel.size())
                val bytes=ByteArray(8192)
                for(position in listOf(0L,20L*1024*1024,4096L)) {
                    input.channel.position(position); assertEquals(bytes.size,input.read(bytes))
                    bytes.forEachIndexed { index,value -> assertEquals(((position+index)%251).toByte(),value) }
                }
                assertFalse(released.isCompleted)
                assertTrue("Never download all 32 MiB",fetched.get()<2L*1024*1024)
            }
            asset.close()
            withTimeout(3000) { released.await() }
            assertTrue(calls.any { it>=20L*1024*1024 })
        } finally { scope.cancel() }
    }
    @Test fun cappedPartialResponsesContinueAtTheirDeclaredBoundary(): Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO); val streams=MediaStreams(scope)
        val calls=java.util.concurrent.atomic.AtomicInteger(); val released=CompletableDeferred<Unit>()
        val total=32*1024L
        try {
            val asset=streams.openSeekable(app,null,0,{ it as? ExtensionFailure ?: ExtensionFailure("NETWORK") }) {
                try { serve { offset,validator ->
                    assertTrue(validator==null || validator=="fixture")
                    calls.incrementAndGet()
                    val end=minOf(offset+4096,total)
                    val bytes=ByteArray((end-offset).toInt()) { ((offset+it)%251).toByte() }
                    SeekableMedia.Response.http(bytes.inputStream(),206,bytes.size.toLong(),"bytes $offset-${end-1}/$total",offset,"fixture",{}, {})
                } } finally { released.complete(Unit) }
            }
            val bytes=asset.createInputStream().use { it.readBytes() }; asset.close()
            assertEquals(total.toInt(),bytes.size)
            bytes.forEachIndexed { index,value -> assertEquals((index%251).toByte(),value) }
            assertEquals("Each continuation starts after a consumed 4 KiB response",8,calls.get())
            withTimeout(3000) { released.await() }
        } finally { scope.cancel() }
    }

    @Test fun cancellationInterruptsAnOutstandingRemoteReadAndReleasesThePermit(): Unit=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO); val streams=MediaStreams(scope)
        val entered=CountDownLatch(1); val stop=CountDownLatch(1); val finished=CompletableDeferred<Unit>()
        val cancel=CancellationSignal()
        try {
            val asset=streams.openSeekable(app,cancel,0,{ ExtensionFailure("NETWORK") }) {
                try { serve { _,_ ->
                    SeekableMedia.Response(object : InputStream() {
                        override fun read(): Int=error("Use block reads")
                        override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                            entered.countDown(); check(stop.await(5,TimeUnit.SECONDS)); throw java.io.IOException("Cancelled")
                        }
                    },1024*1024,0,"fixture",{ stop.countDown() },{})
                } } finally { finished.complete(Unit) }
            }
            val read=async(Dispatchers.IO) { runCatching { asset.createInputStream().use { it.read(ByteArray(4096)) } } }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            one.njk.sao.source.IMediaReadControl.Stub.asInterface(asset.extras!!.getBinder(one.njk.sao.source.MediaStreamOptions.CONTROL)).cancel()
            withTimeout(3000) { read.await(); finished.await() }
            asset.close()
        } finally { scope.cancel(); stop.countDown() }
    }
    @Test fun ignoredAndMalformedRangesAreRejectedBeforeExposingWrongBytes() {
        fun rejected(code: Int,range: String?,offset: Long): String {
            var closed=false
            try {
                SeekableMedia.Response.http(byteArrayOf(0).inputStream(),code,1,range,offset,null,{}, { closed=true })
                fail("Invalid range accepted")
            } catch(error: ExtensionFailure) { assertTrue(closed); return error.code }
            error("Unreachable")
        }
        assertEquals("STREAM_UNSEEKABLE",rejected(200,null,100))
        assertEquals("INVALID_RESPONSE",rejected(206,"bytes 0-0/1000",100))
        assertEquals("INVALID_RESPONSE",rejected(206,"bytes 100-102/1000",100))
    }
}
