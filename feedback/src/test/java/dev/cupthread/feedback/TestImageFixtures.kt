package dev.cupthread.feedback

import java.io.ByteArrayOutputStream

/**
 * Synthetic image fixtures for metadata-scrubbing tests. Everything is built byte-by-byte so the
 * tests exercise the exact on-disk structures the scrubber parses (no codec required).
 */
internal object TestImageFixtures {

    // region low-level byte helpers

    internal fun u16Bytes(value: Int, bigEndian: Boolean): ByteArray {
        val hi = ((value ushr 8) and 0xFF).toByte()
        val lo = (value and 0xFF).toByte()
        return if (bigEndian) byteArrayOf(hi, lo) else byteArrayOf(lo, hi)
    }

    internal fun u32Bytes(value: Int, bigEndian: Boolean): ByteArray =
        if (bigEndian) {
            byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        } else {
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte()
            )
        }

    internal fun rationalBytes(numerator: Int, denominator: Int, bigEndian: Boolean): ByteArray =
        u32Bytes(numerator, bigEndian) + u32Bytes(denominator, bigEndian)

    internal fun u16At(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        } else {
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

    internal fun u32At(bytes: ByteArray, offset: Int, bigEndian: Boolean): Long {
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return if (bigEndian) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    /** Index of [needle] inside [haystack], or `-1`. */
    internal fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    internal fun isAllZero(bytes: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) {
            if (bytes[i] != 0.toByte()) return false
        }
        return true
    }

    // endregion

    // region TIFF / EXIF

    private class TiffWriter(private val bigEndian: Boolean) {
        private val out = ByteArrayOutputStream()

        fun bytes(value: ByteArray) = out.write(value)
        fun ascii(value: String) = bytes(value.toByteArray(Charsets.US_ASCII))
        fun u16(value: Int) = bytes(u16Bytes(value, bigEndian))
        fun u32(value: Int) = bytes(u32Bytes(value, bigEndian))

        /** Writes a 12-byte TIFF IFD entry with a 4-byte inline value. */
        fun entry(tag: Int, type: Int, count: Int, inlineValue: ByteArray) {
            check(inlineValue.size == 4)
            u16(tag)
            u16(type)
            u32(count)
            bytes(inlineValue)
        }

        fun entryWithValueOffset(tag: Int, type: Int, count: Int, valueOffset: Int) =
            entry(tag, type, count, u32Bytes(valueOffset, bigEndian))

        fun entryInlineShort(tag: Int, value: Int) =
            entry(
                tag,
                type = 3,
                count = 1,
                inlineValue = u16Bytes(value, bigEndian) + u16Bytes(0, bigEndian)
            )

        fun toBytes(): ByteArray = out.toByteArray()
    }

    /**
     * Builds a TIFF block with a fixed, hand-computed layout:
     *
     * ```
     * 0:  byte order, 42, IFD0 offset (8)
     * 8:  IFD0  [Make -> out-of-line] [Orientation inline] [GPSInfo -> GPS IFD] (next -> IFD1 or 0)
     * ..: IFD1  [Compression inline] [Orientation inline] [+ GPSInfo -> same GPS IFD] (next -> 0)
     * ..: GPS IFD [VersionID inline] [LatRef inline] [Latitude -> out-of-line 24 B]
     *            [LonRef inline] [Longitude -> out-of-line 24 B]
     * ..: out-of-line values (Make, Latitude, Longitude)
     * ```
     */
    internal fun exifTiff(
        bigEndian: Boolean,
        includeGps: Boolean = true,
        includeThumbnailIfd: Boolean = false
    ): ByteArray {
        val makeValue = "CupThreadCam\u0000".toByteArray(Charsets.US_ASCII)
        val latitudeValue = rationalBytes(37, 1, bigEndian) +
            rationalBytes(46, 1, bigEndian) +
            rationalBytes(3990, 100, bigEndian)
        val longitudeValue = rationalBytes(126, 1, bigEndian) +
            rationalBytes(58, 1, bigEndian) +
            rationalBytes(1234, 100, bigEndian)

        val ifd0EntryCount = 3
        val ifd0Size = 2 + ifd0EntryCount * 12 + 4
        val ifd1EntryCount = 3
        val ifd1Size = if (includeThumbnailIfd) 2 + ifd1EntryCount * 12 + 4 else 0
        val gpsEntryCount = 5
        val gpsIfdSize = 2 + gpsEntryCount * 12 + 4

        val gpsIfdOffset = 8 + ifd0Size + ifd1Size
        val makeOffset = gpsIfdOffset + (if (includeGps) gpsIfdSize else 0)
        val latitudeOffset = makeOffset + makeValue.size
        val longitudeOffset = latitudeOffset + latitudeValue.size

        val w = TiffWriter(bigEndian)
        w.ascii(if (bigEndian) "MM" else "II")
        w.u16(42)
        w.u32(8) // IFD0 lives right after the 8-byte TIFF header

        // IFD0 entries, ascending by tag.
        w.u16(ifd0EntryCount)
        w.entryWithValueOffset(tag = 0x010F, type = 2, count = makeValue.size, valueOffset = makeOffset)
        w.entryInlineShort(tag = 0x0112, value = 6) // Orientation: right-top
        if (includeGps) {
            w.entryWithValueOffset(tag = 0x8825, type = 4, count = 1, valueOffset = gpsIfdOffset)
        } else {
            // Stand-in third entry; an inline SHORT XResolution is invalid EXIF on purpose to
            // prove the scrubber never interprets non-GPS entries.
            w.entryInlineShort(tag = 0x011A, value = 72)
        }
        w.u32(if (includeThumbnailIfd) 8 + ifd0Size else 0) // next IFD -> IFD1

        if (includeThumbnailIfd) {
            w.u16(ifd1EntryCount)
            w.entryInlineShort(tag = 0x0103, value = 6) // Compression
            w.entryInlineShort(tag = 0x0112, value = 1) // Orientation
            if (includeGps) {
                w.entryWithValueOffset(tag = 0x8825, type = 4, count = 1, valueOffset = gpsIfdOffset)
            } else {
                w.entryInlineShort(tag = 0x011A, value = 72)
            }
            w.u32(0)
        }

        if (includeGps) {
            w.u16(gpsEntryCount)
            w.entry(tag = 0x0000, type = 1, count = 4, inlineValue = byteArrayOf(2, 3, 0, 0)) // VersionID
            w.entry(
                tag = 0x0001,
                type = 2,
                count = 2,
                inlineValue = byteArrayOf('N'.code.toByte(), 0, 0, 0) // GPSLatitudeRef
            )
            w.entryWithValueOffset(tag = 0x0002, type = 5, count = 3, valueOffset = latitudeOffset)
            w.entry(
                tag = 0x0003,
                type = 2,
                count = 2,
                inlineValue = byteArrayOf('E'.code.toByte(), 0, 0, 0) // GPSLongitudeRef
            )
            w.entryWithValueOffset(tag = 0x0004, type = 5, count = 3, valueOffset = longitudeOffset)
            w.u32(0)
        }
        w.bytes(makeValue)
        if (includeGps) {
            w.bytes(latitudeValue)
            w.bytes(longitudeValue)
        }
        return w.toBytes()
    }

    internal fun exifApp1Payload(tiff: ByteArray): ByteArray =
        "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff

    internal fun ifd0Offset(tiff: ByteArray, bigEndian: Boolean): Int =
        u32At(tiff, 4, bigEndian).toInt()

    /** Walks IFD0 (then its successors) of a TIFF block and returns the GPS IFD offset, or -1. */
    internal fun findGpsIfdOffset(tiff: ByteArray, bigEndian: Boolean): Int {
        var ifdOffset = ifd0Offset(tiff, bigEndian)
        var guard = 0
        while (ifdOffset != 0 && guard++ < 8) {
            val count = u16At(tiff, ifdOffset, bigEndian)
            for (i in 0 until count) {
                val entry = ifdOffset + 2 + i * 12
                if (u16At(tiff, entry, bigEndian) == 0x8825) {
                    return u32At(tiff, entry + 8, bigEndian).toInt()
                }
            }
            ifdOffset = u32At(tiff, ifdOffset + 2 + count * 12, bigEndian).toInt()
        }
        return -1
    }

    /** Returns the inline SHORT value of IFD entry [tag] in the IFD at [ifdOffset], or -1. */
    internal fun ifdInlineShort(
        tiff: ByteArray,
        ifdOffset: Int,
        tag: Int,
        bigEndian: Boolean
    ): Int {
        val count = u16At(tiff, ifdOffset, bigEndian)
        for (i in 0 until count) {
            val entry = ifdOffset + 2 + i * 12
            if (u16At(tiff, entry, bigEndian) == tag) {
                return u16At(tiff, entry + 8, bigEndian)
            }
        }
        return -1
    }

    /** Offset of the IFD following the one at [ifdOffset] with [entryCount] entries (0 = none). */
    internal fun nextIfdOffset(
        tiff: ByteArray,
        ifdOffset: Int,
        entryCount: Int,
        bigEndian: Boolean
    ): Int = u32At(tiff, ifdOffset + 2 + entryCount * 12, bigEndian).toInt()

    /** Raw bytes of the out-of-line GPS latitude RATIONAL triplet the fixture TIFF embeds. */
    internal fun fixtureGpsLatitudeBytes(bigEndian: Boolean): ByteArray =
        rationalBytes(37, 1, bigEndian) + rationalBytes(46, 1, bigEndian) + rationalBytes(3990, 100, bigEndian)

    /** Raw bytes of the out-of-line GPS longitude RATIONAL triplet the fixture TIFF embeds. */
    internal fun fixtureGpsLongitudeBytes(bigEndian: Boolean): ByteArray =
        rationalBytes(126, 1, bigEndian) + rationalBytes(58, 1, bigEndian) + rationalBytes(1234, 100, bigEndian)

    // endregion

    // region JPEG

    internal val SOI: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    internal val EOI: ByteArray = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** Entropy-coded scan bytes exercising 0xFF 0x00 stuffing. */
    internal val SCAN_BYTES: ByteArray = byteArrayOf(
        0x12.toByte(), 0xD5.toByte(), 0x00, 0xF3.toByte(), 0x44, 0x0F.toByte(), 0x00, 0x77
    )

    /** SOS segment: marker + 8-byte header, followed by [SCAN_BYTES] in [jpeg]. */
    internal val SOS_SEGMENT: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00
    )

    internal fun jpegSegment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (((payload.size + 2) ushr 8) and 0xFF).toByte(),
            ((payload.size + 2) and 0xFF).toByte()
        ) + payload

    /** Minimal JFIF APP0 payload. */
    internal fun jfifPayload(): ByteArray = byteArrayOf(
        0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
        0x01, 0x02, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00
    )

    /** Assembles a complete JPEG: SOI + [segments] + SOS + scan bytes + EOI. */
    internal fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SOI)
        for (segment in segments) out.write(segment)
        out.write(SOS_SEGMENT)
        out.write(SCAN_BYTES)
        out.write(EOI)
        return out.toByteArray()
    }

    /** A JPEG with exactly one EXIF APP1 segment (GPS optional) plus the JFIF APP0. */
    internal fun jpegWithExif(
        bigEndian: Boolean,
        includeGps: Boolean,
        includeThumbnailIfd: Boolean = false
    ): ByteArray = jpeg(
        jpegSegment(0xE0, jfifPayload()),
        jpegSegment(0xE1, exifApp1Payload(exifTiff(bigEndian, includeGps, includeThumbnailIfd)))
    )

    /** Returns the payload of the first APP1 segment found in [data], or `null`. */
    internal fun findApp1Payload(data: ByteArray): ByteArray? {
        var i = 2
        while (i + 3 < data.size) {
            if (data[i] != 0xFF.toByte()) return null
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            val length = u16At(data, i + 2, bigEndian = true)
            if (length < 2 || i + 2 + length > data.size) return null
            if (marker == 0xE1) return data.copyOfRange(i + 4, i + 2 + length)
            i += 2 + length
        }
        return null
    }

    // endregion

    // region PNG

    internal val PNG_SIGNATURE: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** Builds one PNG chunk with a placeholder (but stable) CRC so kept-chunk identity is checkable. */
    internal fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32Bytes(data.size, bigEndian = true))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        return out.toByteArray()
    }

    internal fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        for (chunk in chunks) out.write(chunk)
        return out.toByteArray()
    }

    // endregion

    // region WebP

    internal fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        for (chunk in chunks) {
            body.write(chunk)
            if (chunk.size % 2 == 1) body.write(0) // pad byte
        }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(bytes.size, bigEndian = false))
        out.write(bytes)
        return out.toByteArray()
    }

    internal fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(data.size, bigEndian = false))
        out.write(data)
        return out.toByteArray()
    }

    /** VP8X payload with the EXIF presence flag (bit 3 of the flags byte) set. */
    internal fun vp8xPayload(): ByteArray = byteArrayOf(
        0x08, 0x00, 0x00, 0x00, // flags: EXIF set; 3 reserved bytes
        0x01, 0x00, 0x00, 0x00, // canvas width - 1
        0x01, 0x00, 0x00, 0x00 // canvas height - 1
    )

    // endregion

    // region GIF

    internal const val GIF_TRAILER_INT = 0x3B

    /** Assembles a GIF: signature + logical screen descriptor (no global color table) + blocks + trailer. */
    internal fun gif(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.write(
            byteArrayOf(
                0x01, 0x00, // width
                0x01, 0x00, // height
                0x00, // packed: no global color table
                0x00, // background color index
                0x00 // pixel aspect ratio
            )
        )
        for (block in blocks) out.write(block)
        out.write(GIF_TRAILER_INT)
        return out.toByteArray()
    }

    internal fun gifSubBlock(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(data.size)
        out.write(data)
        return out.toByteArray()
    }

    internal fun gifCommentExtension(text: String): ByteArray =
        byteArrayOf(0x21, 0xFE.toByte()) + gifSubBlock(text.toByteArray(Charsets.US_ASCII)) +
            byteArrayOf(0x00)

    /**
     * Generic Application Extension with a sub-block-encoded payload:
     * `21 FF 0B` + 8-byte identifier + 3-byte authentication code + data sub-block + terminator.
     */
    internal fun gifApplicationExtension(
        identifier: String,
        authCode: String,
        payload: ByteArray
    ): ByteArray {
        require(identifier.length <= 8)
        require(authCode.length <= 3)
        val header = identifier.toByteArray(Charsets.US_ASCII) +
            ByteArray(8 - identifier.length) +
            authCode.toByteArray(Charsets.US_ASCII) +
            ByteArray(3 - authCode.length)
        return byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + header +
            gifSubBlock(payload) + byteArrayOf(0x00)
    }

    /** Standard NETSCAPE2.0 animation-loop extension (sub-block ID 1, loop count 0 = forever). */
    internal fun gifNetscapeExtension(): ByteArray =
        gifApplicationExtension("NETSCAPE", "2.0", byteArrayOf(0x01, 0x00, 0x00))

    /**
     * Spec-conformant `XMP Data` Application Extension (XMP Specification Part 3, §GIF): the
     * UTF-8 packet follows the 11-byte application header raw, without sub-block length
     * prefixes, and is closed by the 258-byte "magic trailer" — `0x01`, `0xFF` descending to
     * `0x01`, then `0x00 0x00` — whose final byte is the Block Terminator. No extra terminator
     * is appended.
     */
    internal fun gifXmpApplicationExtension(packet: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("XMP Data".toByteArray(Charsets.US_ASCII))
        out.write("XMP".toByteArray(Charsets.US_ASCII))
        out.write(packet.toByteArray(Charsets.UTF_8))
        out.write(0x01)
        var value = 0xFF
        while (value >= 0x01) {
            out.write(value)
            value--
        }
        out.write(0x00)
        out.write(0x00)
        return out.toByteArray()
    }

    internal fun gifGraphicControlExtension(): ByteArray = byteArrayOf(
        0x21, 0xF9.toByte(), 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    /** A minimal image descriptor with no local color table and 3 LZW data bytes. */
    internal fun gifImageBlock(lzwBytes: ByteArray): ByteArray =
        byteArrayOf(0x2C) +
            byteArrayOf(0, 0, 0, 0, 1, 0, 1, 0) + // left/top/width/height
            byteArrayOf(0x00) + // packed: no local color table, not interleaved
            byteArrayOf(0x02) + // LZW minimum code size
            gifSubBlock(lzwBytes) +
            byteArrayOf(0x00)

    // endregion
}
