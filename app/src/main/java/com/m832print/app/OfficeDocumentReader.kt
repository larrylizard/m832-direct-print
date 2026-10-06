package com.m832print.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal .docx / .odt text extraction and rendering -- 2026-10-06 user
 * decision: ".docx and the open doc standard file extensions" (ODF,
 * i.e. .odt), explicitly not legacy binary .doc (a much harder format to
 * parse without a dedicated library).
 *
 * Both .docx and .odt are just a zip of XML, so this needs no extra
 * library: java.util.zip (already in the JDK) + Android's built-in
 * XmlPullParser (android.util.Xml.newPullParser()) cover both. Only
 * plain text is extracted -- formatting, images, tables etc. in the
 * source document are not preserved; it's laid out fresh with
 * StaticLayout and paginated at this app's standard page geometry
 * (M832Protocol.WIDTH_DOTS wide, PAGE_LENGTH_DOTS tall per page -- see
 * MainActivity's page-count estimate, same 297mm reference).
 */
object OfficeDocumentReader {
    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val ODT_MIME = "application/vnd.oasis.opendocument.text"

    private const val DOCX_BODY_ENTRY = "word/document.xml"
    private const val ODT_BODY_ENTRY = "content.xml"

    fun isSupported(mimeType: String?): Boolean = mimeType == DOCX_MIME || mimeType == ODT_MIME

    /** Returns null if this isn't a .docx/.odt this reader recognizes, or the entry it needs isn't present. */
    fun extractParagraphs(resolver: ContentResolver, uri: Uri, mimeType: String?): List<String>? {
        val targetEntry = when (mimeType) {
            DOCX_MIME -> DOCX_BODY_ENTRY
            ODT_MIME -> ODT_BODY_ENTRY
            else -> return null
        }
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == targetEntry) return parseParagraphs(zip)
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    /**
     * Both formats use a `p` element per paragraph once namespaces are
     * resolved (docx: `w:p` containing `w:t` text runs; odt: `text:p`
     * with its own text content directly, no separate run element) --
     * XmlPullParser reports local names without the prefix once
     * FEATURE_PROCESS_NAMESPACES is on, so both reduce to "did we see a
     * START_TAG/END_TAG named p, and accumulate TEXT events seen while
     * inside one." docx's other elements between paragraphs (`w:pPr`
     * formatting etc.) have no text events of their own to worry about;
     * odt's text being direct rather than in a separate run element
     * makes no difference to this same accumulate-while-inside-p logic.
     */
    private fun parseParagraphs(input: InputStream): List<String> {
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var insideParagraph = false
        val parser = Xml.newPullParser()
        // XmlPullParser doesn't process namespaces by default, which means
        // parser.name would return the raw prefixed tag ("w:p", "text:p")
        // instead of the bare local name "p" this relies on below.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p") {
                        sb.setLength(0)
                        insideParagraph = true
                    }
                }
                XmlPullParser.TEXT -> if (insideParagraph) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && insideParagraph) {
                        paragraphs.add(sb.toString())
                        insideParagraph = false
                    }
                }
            }
            event = parser.next()
        }
        return paragraphs
    }

    /**
     * Lays out the extracted paragraphs with StaticLayout and paginates
     * them into widthDots-wide, pageHeightDots-tall bitmaps.
     */
    fun renderToPages(paragraphs: List<String>, widthDots: Int, pageHeightDots: Int): List<Bitmap> {
        val marginDots = (widthDots * 0.06f).toInt() // ~6% margin each side, a typical printed-document look
        val textWidth = (widthDots - marginDots * 2).coerceAtLeast(1)
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = widthDots / 48f // scales with width so it's legible at this DPI regardless of paper width
            isAntiAlias = true
        }
        val paragraphGapDots = (paint.textSize * 0.5f).toInt()

        val pages = mutableListOf<Bitmap>()
        var page = Bitmap.createBitmap(widthDots, pageHeightDots, Bitmap.Config.ARGB_8888)
        var canvas = Canvas(page).apply { drawColor(Color.WHITE) }
        var y = marginDots

        fun startNewPage() {
            pages.add(page)
            page = Bitmap.createBitmap(widthDots, pageHeightDots, Bitmap.Config.ARGB_8888)
            canvas = Canvas(page).apply { drawColor(Color.WHITE) }
            y = marginDots
        }

        for (para in paragraphs) {
            if (para.isBlank()) {
                y += paragraphGapDots
                continue
            }
            val layout = StaticLayout.Builder.obtain(para, 0, para.length, paint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            if (y + layout.height > pageHeightDots - marginDots && y > marginDots) {
                startNewPage()
            }
            canvas.save()
            canvas.translate(marginDots.toFloat(), y.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + paragraphGapDots
        }
        pages.add(page)
        return pages
    }
}
