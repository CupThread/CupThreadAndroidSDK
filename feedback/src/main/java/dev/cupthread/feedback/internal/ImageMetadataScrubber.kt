package dev.cupthread.feedback.internal

import java.util.Arrays

/**
 * Strips privacy-sensitive embedded metadata from image payloads before upload.
 *
 * Photo-picker and camera images routinely embed EXIF GPS coordinates plus device details,
 * XMP/IPTC notes and free-text comments. CupThread attachments end up on publicly readable
 * CDN URLs, so [FeedbackClient.uploadAttachment] scrubs every image payload by default
 * (issue #21):
 *
 * - **JPEG**: zeroes the EXIF GPS Info IFD so coordinates become unrecoverable while
 *   display-relevant tags (orientation, color space) survive, and drops APP1 XMP, APP13
 *   (IPTC/Photoshop), COM (comments) and unknown APPn metadata segments. APP0 (JFIF),
 *   APP1 (EXIF), APP2 (ICC color profile) and APP14 (Adobe transform flag) are kept for
 *   faithful rendering.
 * - **PNG**: drops `eXIf`, `tEXt`, `iTXt`, `zTXt` and `tIME` chunks.
 * - **WebP**: drops `EXIF` and `XMP ` chunks and clears the corresponding VP8X presence flags.
 * - Any other content is returned unchanged, so non-image attachments are never touched.
 *
 * Pixel data is never re-encoded: scrubbing is lossless and cheap. When a metadata structure
 * is malformed beyond confident parsing the whole structure is dropped instead of passed
 * through (fail closed); when the surrounding file structure itself is corrupt, the original
 * bytes are returned because such a file would not decode for viewers either.
 */
internal object ImageMetadataScrubber {

    /** EXIF IFD tag pointing at the GPS Info IFD. */
    private const val EXIF_TAG_GPS_INFO = 0x8825

    /** Size of the APP1 payload prefix (`"Exif\0\0"`) before the TIFF block starts. */
    private const val EXIF_TIFF_OFFSET = 6

    /** TIFF IFD entry size in bytes: tag (2) + type (2) + count (4) + value/offset (4). */
    private const val IFD_ENTRY_SIZE = 12

    /** Maximum IFDs followed while walking the EXIF IFD chain before giving up. */
    private const val MAX_IFD_CHAIN_LENGTH = 8

    /** Byte length per EXIF value type, indexed by type code. */
    private val EXIF_TYPE_BYTE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    private val JPEG_XMP_PREFIXES = listOf(
        "http://ns.adobe.com/xap/1.0/",
        "http://ns.adobe.com/xmp/extension/"
    )

    private val PNG_METADATA_CHUNK_TYPES = setOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME")

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    /** Returns [data] with embedded metadata stripped, or [data] itself when there is nothing to strip. */
    fun scrub(data: ByteArray): ByteArray {
        if (isJpeg(data)) return scrubJpeg(data)
        if (isPng(data)) return scrubPng(data)
        if (isWebP(data)) return scrubWebP(data)
        return data
    }

    // region JPEG

    private fun isJpeg(data: ByteArray): Boolean =
        data.size >= 4 &&
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()

    /**
     * Rewrites the JPEG header region (everything before the first SOS marker), dropping or
     * rewriting metadata-bearing segments, and copies the SOS scan payload through verbatim.
     */
    private fun scrubJpeg(data: ByteArray): ByteArray {
        val kept = mutableListOf(JPEG_SOI)
        var changed = false
        var i = 2
        while (i < data.size) {
            if (data[i] != 0xFF.toByte()) {
                // A marker was required here; the header is corrupt, so keep the original bytes.
                return data
            }
            // Collapse 0xFF fill bytes, then read the marker byte.
            var markerPos = i + 1
            while (markerPos < data.size && data[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= data.size) return data
            when (val marker = data[markerPos].toInt() and 0xFF) {
                0xD8 -> i = markerPos + 1
                0xD9 -> {
                    kept.add(data.copyOfRange(i, markerPos + 1))
                    return concat(kept, changed, data)
                }
                0x01, in 0xD0..0xD7 -> {
                    // Standalone markers (TEM, RST0-7) carry no length field.
                    kept.add(data.copyOfRange(i, markerPos + 1))
                    i = markerPos + 1
                }
                0xDA -> {
                    // Start of Scan: entropy-coded data follows verbatim through end of file.
                    kept.add(data.copyOfRange(i, data.size))
                    return concat(kept, changed, data)
                }
                else -> {
                    val lengthPos = markerPos + 1
                    if (lengthPos + 1 >= data.size) return data
                    val length = u16At(data, lengthPos, bigEndian = true)
                    if (length < 2 || lengthPos + length > data.size) return data
                    val segmentEnd = lengthPos + length
                    val payloadStart = lengthPos + 2
                    when {
                        marker == 0xFE || marker == 0xED -> changed = true // COM, APP13 (IPTC)
                        marker == 0xE1 -> when (val result = scrubJpegApp1(data, payloadStart, segmentEnd)) {
                            App1Outcome.KEPT_UNCHANGED -> kept.add(data.copyOfRange(i, segmentEnd))
                            App1Outcome.DROPPED -> changed = true
                            is App1Outcome.Scrubbed -> {
                                val payload = result.payload
                                kept.add(
                                    byteArrayOf(
                                        0xFF.toByte(),
                                        0xE1.toByte(),
                                        ((payload.size + 2) shr 8).toByte(),
                                        (payload.size + 2).toByte()
                                    ) + payload
                                )
                                changed = true
                            }
                        }
                        marker in 0xE3..0xEC || marker == 0xEF -> changed = true // unknown APPn
                        else -> kept.add(data.copyOfRange(i, segmentEnd))
                    }
                    i = segmentEnd
                }
            }
        }
        return concat(kept, changed, data)
    }

    private sealed interface App1Outcome {
        data object KEPT_UNCHANGED : App1Outcome
        data object DROPPED : App1Outcome

        /** APP1 payload (including the `Exif\0\0` prefix) after GPS scrubbing. */
        data class Scrubbed(val payload: ByteArray) : App1Outcome
    }

    private fun scrubJpegApp1(data: ByteArray, payloadStart: Int, payloadEnd: Int): App1Outcome {
        if (startsWith(data, payloadStart, "Exif\u0000\u0000")) {
            val tiff = scrubExifTiff(data, payloadStart + EXIF_TIFF_OFFSET, payloadEnd)
            return when {
                tiff == null -> App1Outcome.DROPPED
                tiff === data -> App1Outcome.KEPT_UNCHANGED
                else -> App1Outcome.Scrubbed(
                    data.copyOfRange(payloadStart, payloadStart + EXIF_TIFF_OFFSET) + tiff
                )
            }
        }
        val magicLength = minOf(32, payloadEnd - payloadStart)
        if (magicLength > 0) {
            val magic = String(data, payloadStart, magicLength, Charsets.ISO_8859_1)
            if (JPEG_XMP_PREFIXES.any { magic.startsWith(it) }) return App1Outcome.DROPPED
        }
        return App1Outcome.KEPT_UNCHANGED
    }

    /**
     * Zeroes the GPS Info IFD inside the TIFF block spanning `[tiffStart, tiffEnd)`.
     *
     * Returns a scrubbed copy of the TIFF block when GPS data was found and removed, the
     * original [data] reference when the IFD chain contains no GPS pointer, or `null` when the
     * EXIF structure cannot be parsed confidently and the caller should drop the whole APP1
     * segment. Zeroing keeps every other offset intact: readers encounter a valid but empty
     * (entry count 0) GPS IFD, so no location data survives in the byte stream.
     */
    private fun scrubExifTiff(data: ByteArray, tiffStart: Int, tiffEnd: Int): ByteArray? {
        val tiffSize = tiffEnd - tiffStart
        if (tiffSize < 8) return null
        val bigEndian = when {
            data[tiffStart] == 'I'.code.toByte() && data[tiffStart + 1] == 'I'.code.toByte() -> false
            data[tiffStart] == 'M'.code.toByte() && data[tiffStart + 1] == 'M'.code.toByte() -> true
            else -> return null
        }
        if (u16At(data, tiffStart + 2, bigEndian) != 42) return null

        var gpsOffset = -1L
        var ifdOffset = u32At(data, tiffStart + 4, bigEndian)
        var chainLength = 0
        while (gpsOffset < 0) {
            val entryCount = readIfdEntryCount(data, tiffStart, tiffSize, ifdOffset, bigEndian)
                ?: return null
            for (entryIndex in 0 until entryCount) {
                val entry = ifdOffset + 2 + entryIndex * IFD_ENTRY_SIZE
                if (u16At(data, tiffStart + entry.toInt(), bigEndian) == EXIF_TAG_GPS_INFO) {
                    val candidate = u32At(data, tiffStart + entry.toInt() + 8, bigEndian)
                    if (candidate < 2) return null // a GPS IFD must live past the 8-byte header
                    gpsOffset = candidate
                    break
                }
            }
            if (gpsOffset >= 0) break
            ifdOffset = u32At(
                data,
                tiffStart + (ifdOffset + 2 + entryCount * IFD_ENTRY_SIZE).toInt(),
                bigEndian
            )
            if (ifdOffset == 0L) return data // chain exhausted; no GPS IFD anywhere
            if (++chainLength > MAX_IFD_CHAIN_LENGTH) return null
        }

        val gpsEntryCount = readIfdEntryCount(data, tiffStart, tiffSize, gpsOffset, bigEndian)
            ?: return null
        val structuralEnd = gpsOffset + 2 + gpsEntryCount * IFD_ENTRY_SIZE + 4
        if (structuralEnd > tiffSize) return null

        // Collect the regions to wipe before mutating anything: the GPS IFD structure itself
        // (its entry count collapses to a valid empty IFD) plus each out-of-line GPS value
        // block individually, so unrelated metadata laid out in between keeps its bytes.
        val rangesToZero = mutableListOf(gpsOffset to structuralEnd)
        for (entryIndex in 0 until gpsEntryCount) {
            val entry = gpsOffset + 2 + entryIndex * IFD_ENTRY_SIZE
            val type = u16At(data, tiffStart + entry.toInt() + 2, bigEndian)
            val unit = EXIF_TYPE_BYTE_SIZES.getOrNull(type) ?: return null
            val valueBytes = u32At(data, tiffStart + entry.toInt() + 4, bigEndian) * unit
            if (valueBytes <= 4) continue // value was stored inline within the IFD entry
            val valueOffset = u32At(data, tiffStart + entry.toInt() + 8, bigEndian)
            if (valueOffset < 2 || valueOffset + valueBytes > tiffSize) return null
            rangesToZero.add(valueOffset to valueOffset + valueBytes)
        }

        val scrubbed = data.copyOfRange(tiffStart, tiffEnd)
        for ((from, to) in rangesToZero) {
            Arrays.fill(scrubbed, from.toInt(), to.toInt(), 0)
        }
        return scrubbed
    }

    /**
     * Reads an IFD entry count at [ifdOffset], validating that the whole IFD (entries plus
     * next-IFD pointer) fits inside the TIFF block; `null` when it does not.
     */
    private fun readIfdEntryCount(
        data: ByteArray,
        tiffStart: Int,
        tiffSize: Int,
        ifdOffset: Long,
        bigEndian: Boolean
    ): Int? {
        if (ifdOffset < 2 || ifdOffset + 2 > tiffSize) return null
        val entryCount = u16At(data, tiffStart + ifdOffset.toInt(), bigEndian)
        if (ifdOffset + 2 + entryCount * IFD_ENTRY_SIZE + 4 > tiffSize) return null
        return entryCount
    }

    // endregion

    // region PNG

    private fun isPng(data: ByteArray): Boolean =
        data.size >= PNG_SIGNATURE.size && data.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

    private fun scrubPng(data: ByteArray): ByteArray {
        val kept = mutableListOf(PNG_SIGNATURE)
        var changed = false
        var i = PNG_SIGNATURE.size
        while (i < data.size) {
            if (i + 12 > data.size) return data // length + type + CRC cannot fit
            val length = u32At(data, i, bigEndian = true)
            if (length > Int.MAX_VALUE || i + 12 + length > data.size) return data
            val chunkEnd = i + 12 + length.toInt()
            val type = chunkType(data, i + 4)
            if (type in PNG_METADATA_CHUNK_TYPES) {
                changed = true
            } else {
                kept.add(data.copyOfRange(i, chunkEnd))
            }
            i = chunkEnd
            if (type == "IEND") break
        }
        return concat(kept, changed, data)
    }

    private fun chunkType(data: ByteArray, offset: Int): String =
        String(data, offset, 4, Charsets.ISO_8859_1)

    // endregion

    // region WebP

    private fun isWebP(data: ByteArray): Boolean =
        data.size >= 12 &&
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()

    private fun scrubWebP(data: ByteArray): ByteArray {
        val riffSize = u32At(data, 4, bigEndian = false)
        // The RIFF size field must describe the exact remaining bytes; otherwise leave as-is.
        if (riffSize < 4 || 8 + riffSize != data.size.toLong()) return data
        val kept = mutableListOf(riffHeader())
        var changed = false
        var vp8xKeptIndex = -1
        var i = 12
        while (i < data.size) {
            if (i + 8 > data.size) return data
            val fourcc = chunkType(data, i)
            val length = u32At(data, i + 4, bigEndian = false)
            if (length > Int.MAX_VALUE || i + 8 + length > data.size) return data
            val chunkEnd = i + 8 + length.toInt()
            if (fourcc == "EXIF" || fourcc == "XMP ") {
                changed = true
            } else {
                if (fourcc == "VP8X") vp8xKeptIndex = kept.size
                kept.add(data.copyOfRange(i, chunkEnd))
            }
            i = chunkEnd + (length and 1L).toInt() // odd-sized chunks carry a pad byte
        }
        if (!changed) return data
        val output = concat(kept, changed = true, original = data)
        writeU32(output, 4, output.size - 8, bigEndian = false)
        if (vp8xKeptIndex >= 0) {
            // kept[0] is the 12-byte RIFF/WEBP header; VP8X chunk data follows the kept chunks
            // ahead of it, and the flags byte sits after the 8-byte chunk header.
            var flagsOffset = 0
            for (index in 0 until vp8xKeptIndex) flagsOffset += kept[index].size
            // Bits 2 and 3 are the EXIF and XMP presence flags in both common bit layouts;
            // clear them while leaving ICC (0x20) and alpha (0x10) untouched.
            if (flagsOffset + 8 < output.size) {
                output[flagsOffset + 8] = (output[flagsOffset + 8].toInt() and 0xF3).toByte()
            }
        }
        return output
    }

    /** RIFF file header with a placeholder size field, patched after chunk reassembly. */
    private fun riffHeader(): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        0, 0, 0, 0,
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
    )

    // endregion

    // region shared byte helpers

    private fun startsWith(data: ByteArray, offset: Int, prefix: String): Boolean {
        if (offset + prefix.length > data.size) return false
        for (index in prefix.indices) {
            if (data[offset + index] != prefix[index].code.toByte()) return false
        }
        return true
    }

    private fun u16At(data: ByteArray, offset: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        } else {
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        }

    private fun u32At(data: ByteArray, offset: Int, bigEndian: Boolean): Long {
        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        return if (bigEndian) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int, bigEndian: Boolean) {
        if (bigEndian) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        } else {
            target[offset] = value.toByte()
            target[offset + 1] = (value ushr 8).toByte()
            target[offset + 2] = (value ushr 16).toByte()
            target[offset + 3] = (value ushr 24).toByte()
        }
    }

    private fun concat(chunks: List<ByteArray>, changed: Boolean, original: ByteArray): ByteArray {
        if (!changed) return original
        val output = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, output, offset, chunk.size)
            offset += chunk.size
        }
        return output
    }

    // endregion
}
