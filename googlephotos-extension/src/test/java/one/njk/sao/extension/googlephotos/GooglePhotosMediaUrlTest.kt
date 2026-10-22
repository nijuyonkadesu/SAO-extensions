package one.njk.sao.extension.googlephotos

import org.junit.Assert.*
import org.junit.Test

class GooglePhotosMediaUrlTest {
    @Test fun acceptsPhotosFifeAndExistingMediaHosts() {
        listOf("photos.fife.usercontent.google.com", "lh3.google.com", "lh3.googleusercontent.com", "lh3.ggpht.com", "rr1.googlevideo.com").forEach { host ->
            assertTrue(host, GooglePhotosProtocol.allowedMedia("https://$host/pw/fixture=w512-h512-no"))
        }
    }

    @Test fun fifeDoesNotAllowOtherGoogleHostsOrHostSpoofing() {
        listOf(
            "https://photos.fife.usercontent.google.com.evil.test/fixture",
            "https://lh3.google.com.evil.test/fixture",
            "https://rr1.googlevideo.com.evil.test/fixture",
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

    @Test fun playbackRequests360pMp4WithoutDownloadOrOriginalParameters() {
        for (host in listOf("photos.fife.usercontent.google.com", "lh3.google.com", "lh3.googleusercontent.com")) {
            assertEquals("https://$host/pw/fixture=m18?value=fixture",
                GooglePhotosProtocol.playback("https://$host/pw/fixture=w512-h512-no?value=fixture"))
        }
        assertEquals("https://lh3.googleusercontent.com/fixture=m18",
            GooglePhotosProtocol.playback("https://lh3.googleusercontent.com/fixture"))
    }

    @Test fun mkvOriginalRetainsItsOwnContainerType() {
        assertEquals("video/x-matroska", GooglePhotosProtocol.mime("large.MKV", true))
        assertEquals("video/mp4", GooglePhotosProtocol.mime("preview.mp4", true))
    }
}
