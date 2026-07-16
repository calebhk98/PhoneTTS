package com.phonetts.core.text.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlTextExtractorTest {
    private val extractor = HtmlTextExtractor()

    @Test
    fun supportsByExtensionAndMime() {
        assertTrue(extractor.supports("page.html", null))
        assertTrue(extractor.supports("page.htm", null))
        assertTrue(extractor.supports("x", "text/html"))
        assertFalse(extractor.supports("a.txt", "text/plain"))
    }

    @Test
    fun extractsHeadingAndBodyTextAndDropsTags() {
        val html =
            """
            <html><head><title>ignored</title>
            <style>.a{color:red}</style>
            <script>var x = 1 < 2;</script>
            </head>
            <body>
              <h1>The Title</h1>
              <p>First paragraph with <b>bold</b> and a <a href="x">link</a>.</p>
              <p>Second paragraph.</p>
            </body></html>
            """.trimIndent()

        val text = extractor.extract(html.toByteArray())

        assertTrue(text.contains("The Title"))
        assertTrue(text.contains("First paragraph with bold and a link."))
        assertTrue(text.contains("Second paragraph."))
        assertFalse(text.contains("<"), "tags should be gone: $text")
        assertFalse(text.contains("color:red"), "style content should be dropped")
        assertFalse(text.contains("var x"), "script content should be dropped")
        // h1 and the two <p> become separate lines
        assertEquals(3, text.lines().size, "expected heading + two paragraphs on their own lines: $text")
    }

    @Test
    fun decodesEntitiesIncludingNumericAndAvoidsDoubleDecodingAmp() {
        val text = extractor.extract("<p>Tom &amp; Jerry &#39;n friends &lt;3 &nbsp;end</p>".toByteArray())
        assertEquals("Tom & Jerry 'n friends <3  end", text)
    }

    @Test
    fun registryRoutesHtmlFiles() {
        assertEquals("html", TextExtractorRegistry.withDefaults().extractorFor("index.html")?.id)
    }
}
