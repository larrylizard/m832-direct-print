package com.m832print.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Phomemo M832 raw raster protocol (300 DPI), 1F 11 xx command space.
 *
 * Mirrors m832/cups/filter/rastertopm832.py in the m832 project exactly for the
 * "plain continuous roll" case (that project's w195h297 media, A4-width roll):
 *   1F 11 0B                          media type: continuous (PAPER_CONTINUES)
 *   1F 11 35 00                       compression: raw (the M832 never packs)
 *   1D 76 30 00 xL xH yL yH <bitmap>  GS v 0, one block for the page,
 *                                     MSB first, 1 = black, no inversion
 *   1B 64 01                          print and feed, after every page
 *   1B 64 02                          print and feed, after the last page
 *   1F 11 11                          status query, once at the end of the job
 *
 * The A4/Letter "cut sheet" framing and the Z-fold pitch-padding from that
 * project's filter are deliberately not reimplemented here -- this is the
 * continuous-roll-only MVP (see the project's own PLAN notes for why: it's
 * the one media type that needs no page-size bookkeeping at all, just crop
 * to content and go).
 */
object M832Protocol {
    /** 195.3mm printable width at 300 DPI -- CLAUDE.md confirmed fact, same
     * constant m832's own filter and calibration script use. */
    const val WIDTH_DOTS = 2304
    const val WIDTH_BYTES = WIDTH_DOTS / 8 // 288
    const val DPI = 300

    /** 2026-10-06 user decision: one "page" for the roll-fed page-count
     * estimate/shrink-to-fit feature is a 297mm (A4-equivalent) length --
     * this user's paper is Z-fold stock cut to that pitch, not a generic
     * assumption. 297mm * 300dpi / 25.4mm-per-inch = 3507.87, rounded. */
    const val PAGE_LENGTH_MM = 297f
    val PAGE_LENGTH_DOTS = Math.round(PAGE_LENGTH_MM / 25.4f * DPI)

    private const val PAPER_CONTINUES = 0x0B
    private const val MAX_LINES = 65535 // GS v 0's line count is 16-bit

    /**
     * Converts a single bitmap into the exact M832 byte stream for one
     * continuous-roll page. See the multi-page overload below for the
     * actual per-page logic (this just wraps it in a one-element list) --
     * kept separate so the existing single-image print path doesn't change.
     */
    fun buildPrintJob(source: Bitmap): ByteArray = buildPrintJob(listOf(source))

    /**
     * Same per-page processing as the single-bitmap version (scale to
     * WIDTH_DOTS wide, Floyd-Steinberg dither, crop trailing blank rows),
     * repeated for each page of a multi-page document (e.g. one Bitmap per
     * rendered PDF page from PdfRenderer) and written as a single combined
     * job over one connection -- mirrors how rastertopm832.py handles a
     * multi-page CUPS job: bCmdBMP block per page, but the tail feed
     * (1B 64 01/02) is only sent once at the very end of the whole job, not
     * per page, so the paper doesn't jump between pages mid-document.
     */
    fun buildPrintJob(pages: List<Bitmap>): ByteArray {
        val out = ByteArrayOutputStream()
        for (source in pages) {
            val scaled = scaleToWidth(source, WIDTH_DOTS)
            val dithered = ditherToBilevel(scaled) // BooleanArray, true = black
            val height = trimTrailingBlankRows(dithered, scaled.width, scaled.height)
            if (height > 0) {
                writeHeader(out)
                writeRaster(out, dithered, scaled.width, height)
            }
        }
        writeFeed(out, 1)
        writeFeed(out, 2)
        writeStatusQuery(out)
        return out.toByteArray()
    }

    /**
     * Manual "nudge the paper forward" command for the app's "Feed 1cm"
     * recovery button -- added 2026-10-06 after a print job completed and
     * the tail feed still didn't advance the paper to the tear line on
     * one run, so the user can retry the feed from the app instead of
     * opening the printer. This is NOT a distance-calibrated command: the
     * M832 has no known command for "feed exactly N mm" (CLAUDE.md: don't
     * assume undocumented behavior), so it's just the same header + tail
     * feed (1B 64 01/02) + status query bytes that already end every
     * normal print job and are confirmed to reach the tear line when they
     * do arrive -- "1cm" is this button's label, not a measured distance.
     */
    fun buildFeedOnlyJob(): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeader(out)
        writeFeed(out, 1)
        writeFeed(out, 2)
        writeStatusQuery(out)
        return out.toByteArray()
    }

    private fun scaleToWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width == targetWidth) return src
        val targetHeight = (src.height.toLong() * targetWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    /**
     * Floyd-Steinberg dithering straight to black/white. Operates on a
     * float luminance buffer so error diffusion doesn't clip/accumulate
     * incorrectly the way repeatedly rounding 8-bit ints would.
     * Output convention: true = black (ink), matching the M832's 1=black
     * bit (no inversion needed at the raster-packing stage below).
     */
    private fun ditherToBilevel(bmp: Bitmap): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val lum = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // standard luminance weights
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val black = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = lum[i]
                val isBlack = old < 128f
                black[i] = isBlack
                val newVal = if (isBlack) 0f else 255f
                val err = old - newVal
                if (x + 1 < w) lum[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += err * 3f / 16f
                    lum[i + w] += err * 5f / 16f
                    if (x + 1 < w) lum[i + w + 1] += err * 1f / 16f
                }
            }
        }
        return black
    }

    /** Mirrors rastertopm832.py's continuous-roll branch: crop off
     * trailing all-white rows so short content doesn't waste a full
     * page's worth of paper. */
    private fun trimTrailingBlankRows(black: BooleanArray, width: Int, height: Int): Int {
        for (y in height - 1 downTo 0) {
            val rowStart = y * width
            for (x in 0 until width) {
                if (black[rowStart + x]) return y + 1
            }
        }
        return 0
    }

    private fun writeHeader(out: ByteArrayOutputStream) {
        out.write(byteArrayOf(0x1F, 0x11, PAPER_CONTINUES.toByte()))
        out.write(byteArrayOf(0x1F, 0x11, 0x35, 0x00))
    }

    private fun writeRaster(out: ByteArrayOutputStream, black: BooleanArray, width: Int, height: Int) {
        val widthBytes = (width + 7) / 8
        var line = 0
        while (line < height) {
            val lines = minOf(height - line, MAX_LINES)
            out.write(byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0x00))
            out.write(widthBytes and 0xFF)
            out.write((widthBytes shr 8) and 0xFF)
            out.write(lines and 0xFF)
            out.write((lines shr 8) and 0xFF)

            val block = ByteArray(widthBytes * lines)
            for (ly in 0 until lines) {
                val srcRow = (line + ly) * width
                val dstRow = ly * widthBytes
                for (x in 0 until width) {
                    if (black[srcRow + x]) {
                        block[dstRow + (x / 8)] = (block[dstRow + (x / 8)].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                }
            }
            out.write(block)
            line += lines
        }
    }

    private fun writeFeed(out: ByteArrayOutputStream, which: Int) {
        out.write(byteArrayOf(0x1B, 'd'.code.toByte(), which.toByte()))
    }

    private fun writeStatusQuery(out: ByteArrayOutputStream) {
        out.write(byteArrayOf(0x1F, 0x11, 0x11))
    }
}
