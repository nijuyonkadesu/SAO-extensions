package one.njk.sao.extension.googlephotos

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Nonce/cancellation transport only: never opens Photos, lists accounts, or requests a token. */
class GooglePhotosCancellationDeviceTest {
    @Test fun cancellationClosesTheClaimedRemoteAndRejectsItsLateCompletion() {
        val pending=GooglePhotosConnect.begin(); val calls=AtomicInteger()
        try {
            assertTrue(GooglePhotosConnect.claim(pending.nonce,object : IGooglePhotosAuthCancellation.Stub() {
                override fun cancel(nonce: String) { assertEquals(pending.nonce,nonce); calls.incrementAndGet() }
            }))
            GooglePhotosConnect.cancel(pending.nonce)
            assertEquals(1,calls.get()); assertTrue(pending.result.isCancelled)
            assertFalse(GooglePhotosConnect.complete(pending.nonce,"fixture@example.test","https://accounts.google.com/MergeSession?synthetic=1",""))
        } finally { GooglePhotosConnect.cancel(pending.nonce) }
    }
    @Test fun replacingARequestCancelsOnlyItsOwnNonce() {
        val first=GooglePhotosConnect.begin(); val calls=AtomicInteger()
        assertTrue(GooglePhotosConnect.claim(first.nonce,object : IGooglePhotosAuthCancellation.Stub() {
            override fun cancel(nonce: String) { assertEquals(first.nonce,nonce); calls.incrementAndGet() }
        }))
        val next=GooglePhotosConnect.begin()
        try {
            assertEquals(1,calls.get()); assertTrue(first.result.isCancelled)
            GooglePhotosConnect.cancel(first.nonce)
            assertFalse(next.result.isCancelled)
            assertTrue(GooglePhotosConnect.claim(next.nonce))
        } finally { GooglePhotosConnect.cancel(next.nonce) }
    }
}
