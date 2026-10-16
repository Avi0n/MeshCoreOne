package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [ImageURLClassifier], mirrored from ImageURLDetectorTests / ImageURLClassifier Swift. */
class ImageURLClassifierTest {
    @Test
    fun directExtensionsAreImageUrls() {
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.jpg"))
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.JPEG"))
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.png?x=1"))
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.gif#frag"))
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.webp"))
        assertTrue(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.heic"))
        assertFalse(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a.html"))
        assertFalse(ImageURLClassifier.isDirectImageURL("https://cdn.example.com/a"))
    }

    @Test
    fun htmlContentTypeIsAPageNotAnImage() {
        assertTrue(ImageURLClassifier.isHtmlContentType("text/html"))
        assertTrue(ImageURLClassifier.isHtmlContentType("text/html; charset=utf-8"))
        assertFalse(ImageURLClassifier.isHtmlContentType("image/jpeg"))
        assertFalse(ImageURLClassifier.isHtmlContentType(null))
        assertFalse(ImageURLClassifier.isHtmlContentType(""))
    }

    @Test
    fun giphyPageResolvesToMediaUrl() {
        val page = "https://giphy.com/gifs/funny-cat-abc123"
        val resolved = ImageURLClassifier.resolveImageURL(page)
        assertEquals("https://i.giphy.com/media/abc123/giphy.gif", resolved)
        assertTrue(ImageURLClassifier.isImageURL(page))
        assertEquals(resolved, ImageURLClassifier.directImageURL(page))
    }

    @Test
    fun giphyEmbedResolves() {
        val page = "https://www.giphy.com/embed/xyz789"
        assertEquals("https://i.giphy.com/media/xyz789/giphy.gif", ImageURLClassifier.resolveImageURL(page))
    }

    @Test
    fun directGiphyMediaDoesNotReResolve() {
        val media = "https://i.giphy.com/media/abc/giphy.gif"
        assertNull(ImageURLClassifier.resolveImageURL(media))
        assertTrue(ImageURLClassifier.isDirectImageURL(media))
        assertEquals(media, ImageURLClassifier.directImageURL(media))
    }

    @Test
    fun nonImagePageIsNotImageUrl() {
        assertFalse(ImageURLClassifier.isImageURL("https://example.com/article"))
        assertEquals(
            "https://example.com/article",
            ImageURLClassifier.directImageURL("https://example.com/article"),
        )
    }

    @Test
    fun gifUrlsAreDetected() {
        assertTrue(ImageURLClassifier.isGifURL("https://cdn.example.com/a.gif"))
        assertTrue(ImageURLClassifier.isGifURL("https://cdn.example.com/a.GIF?x=1"))
        assertFalse(ImageURLClassifier.isGifURL("https://cdn.example.com/a.png"))
        assertFalse(ImageURLClassifier.isGifURL("https://example.com/article"))
    }

    @Test
    fun giphyPageIsDetectedAsGif() {
        assertTrue(ImageURLClassifier.isGifURL("https://giphy.com/gifs/funny-cat-abc123"))
    }
}
