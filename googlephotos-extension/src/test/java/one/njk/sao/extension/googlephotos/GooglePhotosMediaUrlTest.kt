package one.njk.sao.extension.googlephotos

import org.junit.Assert.*
import org.junit.Test

class GooglePhotosMediaUrlTest {
    @Test fun acceptsPhotosFifeAndExistingMediaHosts() {
        listOf("photos.fife.usercontent.google.com", "lh3.googleusercontent.com", "lh3.ggpht.com").forEach { host ->
            assertTrue(host, GooglePhotosProtocol.allowedMedia("https://$host/pw/fixture=w512-h512-no"))
        }
    }

    @Test fun fifeDoesNotAllowOtherGoogleHostsOrHostSpoofing() {
        listOf(
            "https://photos.fife.usercontent.google.com.evil.test/fixture",
            "https://evil.test/photos.fife.usercontent.google.com/fixture",
            "https://accounts.google.com/fixture",
            "https://usercontent.google.com/fixture",
            "https://photos.fife.usercontent.google.com@evil.test/fixture",
            "https://user@photos.fife.usercontent.google.com/fixture",
            "https://photos.fife.usercontent.google.com:8443/fixture",
            "http://photos.fife.usercontent.google.com/fixture",
            "https://photos.fife.usercontent.google.com/fixture#fragment",
        ).forEach { assertFalse(it, GooglePhotosProtocol.allowedMedia(it)) }
    }

    @Test fun fifePreviewPreservesQueryAndChangesOnlyImageSize() {
        assertEquals("https://photos.fife.usercontent.google.com/pw/fixture=w1600-h1600-no?value=fixture",
            GooglePhotosProtocol.preview("https://photos.fife.usercontent.google.com/pw/fixture=w512-h512-no?value=fixture"))
    }
}
