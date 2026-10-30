package dev.cupthread.feedback

import dev.cupthread.feedback.TestImageFixtures.EOI
import dev.cupthread.feedback.TestImageFixtures.PNG_SIGNATURE
import dev.cupthread.feedback.TestImageFixtures.SCAN_BYTES
import dev.cupthread.feedback.TestImageFixtures.SOI
import dev.cupthread.feedback.TestImageFixtures.SOS_SEGMENT
import dev.cupthread.feedback.TestImageFixtures.exifApp1Payload
import dev.cupthread.feedback.TestImageFixtures.exifTiff
import dev.cupthread.feedback.TestImageFixtures.findApp1Payload
import dev.cupthread.feedback.TestImageFixtures.findGpsIfdOffset
import dev.cupthread.feedback.TestImageFixtures.ifd0Offset
import dev.cupthread.feedback.TestImageFixtures.ifdInlineShort
import dev.cupthread.feedback.TestImageFixtures.indexOf
import dev.cupthread.feedback.TestImageFixtures.isAllZero
import dev.cupthread.feedback.TestImageFixtures.jpeg
import dev.cupthread.feedback.TestImageFixtures.jpegSegment
import dev.cupthread.feedback.TestImageFixtures.jpegWithExif
import dev.cupthread.feedback.TestImageFixtures.jfifPayload
import dev.cupthread.feedback.TestImageFixtures.nextIfdOffset
import dev.cupthread.feedback.TestImageFixtures.png
import dev.cupthread.feedback.TestImageFixtures.pngChunk
import dev.cupthread.feedback.TestImageFixtures.u16At
import dev.cupthread.feedback.TestImageFixtures.u32At
import dev.cupthread.feedback.TestImageFixtures.u32Bytes
import dev.cupthread.feedback.TestImageFixtures.vp8xPayload
import dev.cupthread.feedback.TestImageFixtures.webp
import dev.cupthread.feedback.TestImageFixtures.webpChunk
import dev.cupthread.feedback.internal.ImageMetadataScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMetadataScrubberTest {

    // region JPEG / EXIF

    @Test
    fun jpegGpsIfdIsZeroedWhileDisplayTagsSurviveLittleEndian() {
        val original = jpegWithExif(bigEndian = false, includeGps = true)
        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // GPS zeroing preserves the file size and overall structure.
        assertEquals(original.size, scrubbed.size)
        assertTrue(scrubbed[0] == 0xFF.toByte() && scrubbed[1] == 0xD8.toByte())
        assertTrue(indexOf(scrubbed, "Exif".toByteArray(Charsets.US_ASCII)) >= 0)

        val tiff = exifTiffOf(scrubbed)
        val gpsOffset = findGpsIfdOffset(tiff, bigEndian = false)
        assertTrue(gpsOffset > 0)
        // The GPS IFD is now a valid, empty IFD: entry count 0 and its original structure
        // (5 entries + next-IFD pointer) wiped to zeros.
        assertEquals(0, u16At(tiff, gpsOffset, bigEndian = false))
        assertTrue(isAllZero(tiff, gpsOffset, gpsOffset + 2 + 5 * 12 + 4))
        // The out-of-line GPS coordinate rationals are unrecoverable...
        assertFalse(
            indexOf(scrubbed, TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = false)) >= 0
        )
        assertFalse(
            indexOf(scrubbed, TestImageFixtures.fixtureGpsLongitudeBytes(bigEndian = false)) >= 0
        )
        // ...while Orientation (inline SHORT value 6) and the camera Make string survive.
        assertEquals(6, ifdInlineShort(tiff, ifd0Offset(tiff, false), 0x0112, bigEndian = false))
        assertTrue(
            indexOf(scrubbed, "CupThreadCam".toByteArray(Charsets.US_ASCII)) >= 0
        )
    }

    @Test
    fun jpegGpsIfdIsZeroedWhileDisplayTagsSurviveBigEndian() {
        val original = jpegWithExif(bigEndian = true, includeGps = true)
        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertEquals(original.size, scrubbed.size)
        val tiff = exifTiffOf(scrubbed)
        val gpsOffset = findGpsIfdOffset(tiff, bigEndian = true)
        assertTrue(gpsOffset > 0)
        assertEquals(0, u16At(tiff, gpsOffset, bigEndian = true))
        assertTrue(isAllZero(tiff, gpsOffset, gpsOffset + 2 + 5 * 12 + 4))
        assertFalse(
            indexOf(scrubbed, TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = true)) >= 0
        )
        assertFalse(
            indexOf(scrubbed, TestImageFixtures.fixtureGpsLongitudeBytes(bigEndian = true)) >= 0
        )
        assertEquals(6, ifdInlineShort(tiff, ifd0Offset(tiff, true), 0x0112, bigEndian = true))
        assertTrue(
            indexOf(scrubbed, "CupThreadCam".toByteArray(Charsets.US_ASCII)) >= 0
        )
    }

    @Test
    fun jpegThumbnailIfdGpsPointerSeesTheSameZeroedIfd() {
        val original = jpegWithExif(
            bigEndian = false,
            includeGps = true,
            includeThumbnailIfd = true
        )
        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        val tiff = exifTiffOf(scrubbed)
        val gpsOffset = findGpsIfdOffset(tiff, bigEndian = false)
        assertTrue(gpsOffset > 0)
        assertEquals(0, u16At(tiff, gpsOffset, bigEndian = false))
        assertTrue(isAllZero(tiff, gpsOffset, gpsOffset + 2 + 5 * 12 + 4))
        assertFalse(
            indexOf(scrubbed, TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = false)) >= 0
        )
        // IFD1 structure survives: it is reachable and still reports its 3 entries.
        val ifd0 = ifd0Offset(tiff, bigEndian = false)
        val ifd0Count = u16At(tiff, ifd0, bigEndian = false)
        val ifd1 = nextIfdOffset(tiff, ifd0, ifd0Count, bigEndian = false)
        assertTrue(ifd1 > 0)
        assertEquals(3, u16At(tiff, ifd1, bigEndian = false))
    }

    @Test
    fun jpegExifWithoutGpsIsReturnedUnchanged() {
        val original = jpegWithExif(bigEndian = false, includeGps = false)
        assertSame(original, ImageMetadataScrubber.scrub(original))
    }

    @Test
    fun jpegXmpIptcCommentsAndUnknownAppSegmentsAreDropped() {
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.US_ASCII)
        val iptc = "Photoshop 3.0\u00008BIM".toByteArray(Charsets.US_ASCII)
        val comment = "captured on device XYZ".toByteArray(Charsets.US_ASCII)
        val unknownApp = "OLYMPUS\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val original = jpeg(
            jpegSegment(0xE0, jfifPayload()),
            jpegSegment(0xE1, exifApp1Payload(exifTiff(bigEndian = false, includeGps = false))),
            jpegSegment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)),
            jpegSegment(0xE1, xmp),
            jpegSegment(0xED, iptc),
            jpegSegment(0xE5, unknownApp),
            jpegSegment(0xFE, comment)
        )

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // Everything needed for faithful display survives...
        assertTrue(indexOf(scrubbed, jfifPayload()) >= 0)
        assertTrue(indexOf(scrubbed, "ICC_PROFILE".toByteArray(Charsets.US_ASCII)) >= 0)
        assertTrue(indexOf(scrubbed, "Exif".toByteArray(Charsets.US_ASCII)) >= 0)
        // ...and every metadata carrier is gone.
        assertFalse(indexOf(scrubbed, xmp) >= 0)
        assertFalse(indexOf(scrubbed, iptc) >= 0)
        assertFalse(indexOf(scrubbed, comment) >= 0)
        assertFalse(indexOf(scrubbed, unknownApp) >= 0)
    }

    @Test
    fun jpegMalformedExifDropsTheWholeSegment() {
        val malformed = "not-a-tiff-at-all!".toByteArray(Charsets.US_ASCII)
        val original = jpeg(
            jpegSegment(0xE0, jfifPayload()),
            jpegSegment(0xE1, exifApp1Payload(malformed))
        )

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertTrue(indexOf(scrubbed, malformed) < 0)
        assertTrue(indexOf(scrubbed, jfifPayload()) >= 0)
        assertTrue(indexOf(scrubbed, SCAN_BYTES) >= 0)
        assertTrue(indexOf(scrubbed, EOI) >= 0)
    }

    @Test
    fun jpegGpsIfdBeyondTiffBoundsDropsTheExifSegment() {
        val tiff = exifTiff(bigEndian = false, includeGps = true)
        val bogus = tiff.copyOf()
        val ifd0 = ifd0Offset(bogus, bigEndian = false)
        val entryCount = u16At(bogus, ifd0, bigEndian = false)
        var patched = false
        for (i in 0 until entryCount) {
            val entry = ifd0 + 2 + i * 12
            if (u16At(bogus, entry, bigEndian = false) == 0x8825) {
                val raw = u32Bytes(tiff.size + 4096, bigEndian = false)
                bogus[entry + 8] = raw[0]
                bogus[entry + 9] = raw[1]
                bogus[entry + 10] = raw[2]
                bogus[entry + 11] = raw[3]
                patched = true
            }
        }
        assertTrue(patched)
        val original = jpeg(jpegSegment(0xE0, jfifPayload()), jpegSegment(0xE1, exifApp1Payload(bogus)))

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // Fail closed: the unparsable EXIF block is removed instead of uploaded with GPS intact.
        assertTrue(indexOf(scrubbed, "Exif".toByteArray(Charsets.US_ASCII)) < 0)
        assertTrue(indexOf(scrubbed, jfifPayload()) >= 0)
    }

    @Test
    fun jpegScanDataIsCopiedVerbatim() {
        val original = jpegWithExif(bigEndian = false, includeGps = true)
        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        val sosInOriginal = indexOf(original, SOS_SEGMENT)
        val sosInScrubbed = indexOf(scrubbed, SOS_SEGMENT)
        assertTrue(sosInOriginal >= 0 && sosInScrubbed >= 0)
        assertEquals(original.size - sosInOriginal, scrubbed.size - sosInScrubbed)
        for (i in 0 until original.size - sosInOriginal) {
            assertEquals(original[sosInOriginal + i], scrubbed[sosInScrubbed + i])
        }
    }

    @Test
    fun jpegWithoutAnyMetadataIsReturnedUnchanged() {
        val original = jpeg(jpegSegment(0xE0, jfifPayload()))
        assertSame(original, ImageMetadataScrubber.scrub(original))
    }

    @Test
    fun jpegMetadataBetweenScansIsStripped() {
        // ITU-T T.81 allows marker segments between scans, so a progressive file can hide
        // metadata after the first scan. The scan walker must resume segment parsing there.
        val hiddenComment = "note hidden after the first scan".toByteArray(Charsets.US_ASCII)
        val hiddenXmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray(Charsets.US_ASCII)
        // Scan bytes exercising byte stuffing (0xFF 0x00) and a restart marker (RST7) —
        // neither terminates a scan — plus legal 0xFF fill bytes ahead of the next marker.
        val secondScan = byteArrayOf(
            0xAB.toByte(), 0xFF.toByte(), 0x00, 0xCD.toByte(),
            0xFF.toByte(), 0xD7.toByte(), 0x11
        )
        val original = SOI +
            jpegSegment(0xE0, jfifPayload()) +
            SOS_SEGMENT + SCAN_BYTES +
            jpegSegment(0xFE, hiddenComment) +
            SOS_SEGMENT + secondScan +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) +
            jpegSegment(0xE1, hiddenXmp) +
            EOI

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // Both scans survive byte-for-byte (the walker did not cut them early)...
        assertTrue(indexOf(scrubbed, secondScan) >= 0)
        // ...while metadata in the marker segments between and after scans is gone.
        assertFalse(indexOf(scrubbed, hiddenComment) >= 0)
        assertFalse(indexOf(scrubbed, hiddenXmp) >= 0)
        assertTrue(
            scrubbed[scrubbed.size - 2] == 0xFF.toByte() && scrubbed[scrubbed.size - 1] == 0xD9.toByte()
        )
    }

    @Test
    fun jpegScanRunningToEofIsRejected() {
        // A scan without a terminating marker means the file is truncated; bytes after SOS
        // were never parsed, so they must not be uploaded.
        val base = jpegWithExif(bigEndian = false, includeGps = true)
        val sosInBase = indexOf(base, SOS_SEGMENT)
        val truncated = base.copyOfRange(0, sosInBase + SOS_SEGMENT.size + 4)
        // The cut lands mid-scan: no EOI follows.
        assertTrue(truncated[truncated.size - 1] != 0xD9.toByte())

        assertNull(ImageMetadataScrubber.scrub(truncated))
    }

    @Test
    fun jpegWithCorruptStructureIsRejected() {
        val corrupt = SOI + jpegSegment(0xE0, jfifPayload()) + byteArrayOf(0x12, 0x34) + SCAN_BYTES + EOI
        assertNull(ImageMetadataScrubber.scrub(corrupt))
    }

    @Test
    fun truncatedJpegIsRejected() {
        val truncated = SOI + jpegSegment(0xE0, jfifPayload()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x10, 0x00, 0x45, 0x78) // promises 4096 bytes
        assertNull(ImageMetadataScrubber.scrub(truncated))
    }

    @Test
    fun jpegExtendedXmpApp1IsDropped() {
        // The extended-XMP namespace URI is 34 chars — longer than the previous 32-byte magic
        // read window — so those segments must still be recognized via the longest prefix.
        val guid = "B2A3B4C5D6E7F8091122334455667788" // 32-char GUID as the spec prescribes
        val extendedXmp = "http://ns.adobe.com/xmp/extension/$guid"
            .toByteArray(Charsets.US_ASCII) + "xmp-chunk-bytes".toByteArray(Charsets.US_ASCII)
        val original = jpeg(jpegSegment(0xE0, jfifPayload()), jpegSegment(0xE1, extendedXmp))

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertTrue(indexOf(scrubbed, jfifPayload()) >= 0)
        assertFalse(indexOf(scrubbed, extendedXmp) >= 0)
        assertFalse(indexOf(scrubbed, "xmp-chunk-bytes".toByteArray(Charsets.US_ASCII)) >= 0)
    }

    @Test
    fun jpegTrailingContentAfterEoiIsDropped() {
        // Motion-photo style files append an embedded MP4 — which can carry its own GPS
        // track — after the EOI. Only the parsed still image may leave the device.
        val base = jpegWithExif(bigEndian = false, includeGps = true)
        val motionData = "ftypmp42-motion-photo-video-with-gps".toByteArray(Charsets.US_ASCII)
        val original = base + motionData

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertFalse(indexOf(scrubbed, motionData) >= 0)
        assertFalse(indexOf(scrubbed, TestImageFixtures.fixtureGpsLatitudeBytes(bigEndian = false)) >= 0)
        assertTrue(
            scrubbed[scrubbed.size - 2] == 0xFF.toByte() && scrubbed[scrubbed.size - 1] == 0xD9.toByte()
        )
        // The output equals the scrub of the trailer-free file: identical size, GPS zeroed.
        assertEquals(base.size, scrubbed.size)
        assertTrue(scrubbed.contentEquals(ImageMetadataScrubber.scrub(base)!!))
    }

    @Test
    fun jpegWithoutMetadataStillDropsTrailingBytes() {
        val clean = jpeg(jpegSegment(0xE0, jfifPayload()))
        val original = clean + byteArrayOf(1, 2, 3)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertTrue(scrubbed.contentEquals(clean))
    }

    // endregion

    // region PNG

    @Test
    fun pngMetadataChunksAreDroppedAndImageChunksKept() {
        val ihdr = pngChunk("IHDR", ByteArray(13) { (it * 3).toByte() })
        val text = pngChunk("tEXt", "Comment\u0000my home town".toByteArray(Charsets.US_ASCII))
        val itxt = pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000<x/>".toByteArray(Charsets.US_ASCII))
        val ztxt = pngChunk("zTXt", "Title\u0000\u0000compressed".toByteArray(Charsets.US_ASCII))
        val exif = pngChunk("eXIf", exifTiff(bigEndian = true, includeGps = true))
        val time = pngChunk("tIME", byteArrayOf(7, 38, 1, 2, 3, 4, 5))
        val idat = pngChunk("IDAT", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val iend = pngChunk("IEND", ByteArray(0))
        val original = png(ihdr, text, itxt, ztxt, exif, time, idat, iend)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertTrue(indexOf(scrubbed, ihdr) >= 0)
        assertTrue(indexOf(scrubbed, idat) >= 0)
        assertTrue(indexOf(scrubbed, iend) >= 0)
        assertFalse(indexOf(scrubbed, text) >= 0)
        assertFalse(indexOf(scrubbed, itxt) >= 0)
        assertFalse(indexOf(scrubbed, ztxt) >= 0)
        assertFalse(indexOf(scrubbed, exif) >= 0)
        assertFalse(indexOf(scrubbed, time) >= 0)
        // IDAT payload bytes are untouched.
        assertTrue(indexOf(scrubbed, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) >= 0)
    }

    @Test
    fun pngWithoutMetadataIsReturnedUnchanged() {
        val original = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("IDAT", byteArrayOf(1, 2, 3)),
            pngChunk("IEND", ByteArray(0))
        )
        assertSame(original, ImageMetadataScrubber.scrub(original))
    }

    @Test
    fun pngWithOverrunningChunkIsRejected() {
        val broken = PNG_SIGNATURE +
            u32Bytes(1024, bigEndian = true) + // chunk length far beyond the data
            "tEXt".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(1, 2, 3)
        assertNull(ImageMetadataScrubber.scrub(broken))
    }

    @Test
    fun pngTrailingContentAfterIendIsDropped() {
        val trailing = byteArrayOf(1, 2, 3, 4)
        val original = png(
            pngChunk("IHDR", ByteArray(13)),
            pngChunk("IDAT", byteArrayOf(9, 8, 7)),
            pngChunk("IEND", ByteArray(0))
        ) + trailing

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertFalse(indexOf(scrubbed, trailing) >= 0)
        assertTrue(indexOf(scrubbed, byteArrayOf(9, 8, 7)) >= 0)
    }

    // endregion

    // region WebP

    @Test
    fun webpExifAndXmpChunksAreDroppedAndRiffSizeRepaired() {
        val exif = webpChunk("EXIF", exifTiff(bigEndian = false, includeGps = true))
        val xmp = webpChunk("XMP ", "<x:xmpmeta/>".toByteArray(Charsets.US_ASCII))
        val vp8 = webpChunk("VP8 ", byteArrayOf(1, 2, 3, 4, 5))
        val original = webp(webpChunk("VP8X", vp8xPayload()), exif, xmp, vp8)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertEquals("RIFF", String(scrubbed, 0, 4, Charsets.ISO_8859_1))
        assertEquals("WEBP", String(scrubbed, 8, 4, Charsets.ISO_8859_1))
        // The RIFF size field describes the scrubbed payload exactly.
        assertEquals((scrubbed.size - 8).toLong(), u32At(scrubbed, 4, bigEndian = false))
        assertTrue(indexOf(scrubbed, vp8) >= 0)
        assertFalse(indexOf(scrubbed, exif) >= 0)
        assertFalse(indexOf(scrubbed, xmp) >= 0)
        // The kept VP8X chunk carries the EXIF/XMP presence flags cleared.
        val scrubbedVp8x = webpChunk("VP8X", vp8xPayload().also { it[0] = 0 })
        assertTrue(indexOf(scrubbed, scrubbedVp8x) >= 0)
    }

    @Test
    fun webpWithoutMetadataIsReturnedUnchanged() {
        val original = webp(webpChunk("VP8 ", byteArrayOf(1, 2, 3, 4)))
        assertSame(original, ImageMetadataScrubber.scrub(original))
    }

    @Test
    fun webpWithInconsistentRiffSizeIsRejected() {
        val body = "WEBP".toByteArray(Charsets.US_ASCII) + webpChunk("VP8 ", byteArrayOf(1, 2, 3, 4))
        val broken = "RIFF".toByteArray(Charsets.US_ASCII) +
            u32Bytes(body.size + 999, bigEndian = false) +
            body
        assertNull(ImageMetadataScrubber.scrub(broken))
    }

    @Test
    fun webpKeptOddLengthChunksRetainTheirPadByte() {
        // A lossy WebP with alpha: VP8X, odd-length ALPH, EXIF, odd-length VP8 data.
        val alph = webpChunk("ALPH", byteArrayOf(9, 8, 7)) // odd length -> carries a pad byte
        val alphWithPad = alph + byteArrayOf(0)
        val exif = webpChunk("EXIF", exifTiff(bigEndian = false, includeGps = true))
        val vp8 = webpChunk("VP8 ", byteArrayOf(1, 2, 3, 4, 5))
        val original = webp(webpChunk("VP8X", vp8xPayload()), alph, exif, vp8)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // The kept ALPH chunk still carries its pad byte...
        assertTrue(indexOf(scrubbed, alphWithPad) >= 0)
        assertFalse(indexOf(scrubbed, exif) >= 0)
        // ...and every remaining chunk header sits at an even offset, so the file still parses
        // as RIFF: walk the chunks exactly like a decoder would.
        assertEquals("RIFF", String(scrubbed, 0, 4, Charsets.ISO_8859_1))
        assertEquals((scrubbed.size - 8).toLong(), u32At(scrubbed, 4, bigEndian = false))
        var i = 12
        val seen = mutableListOf<String>()
        while (i < scrubbed.size) {
            assertTrue(i + 8 <= scrubbed.size)
            val fourcc = String(scrubbed, i, 4, Charsets.ISO_8859_1)
            val length = u32At(scrubbed, i + 4, bigEndian = false)
            assertTrue(i + 8 + length <= scrubbed.size)
            seen.add(fourcc)
            i += 8 + length.toInt() + (length and 1L).toInt()
        }
        assertEquals(i, scrubbed.size)
        assertEquals(listOf("VP8X", "ALPH", "VP8 "), seen)
    }

    // endregion

    // region GIF

    @Test
    fun gifCommentAndSubBlockEncodedXmpAreDropped() {
        val comment = TestImageFixtures.gifCommentExtension("taken at home address")
        val xmpApp = TestImageFixtures.gifApplicationExtension("XMP Data", "", "<x/>".toByteArray(Charsets.US_ASCII))
        val netscape = TestImageFixtures.gifNetscapeExtension()
        val gce = TestImageFixtures.gifGraphicControlExtension()
        val image = TestImageFixtures.gifImageBlock(byteArrayOf(1, 2, 3))
        val original = TestImageFixtures.gif(gce, netscape, comment, xmpApp, image)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertTrue(indexOf(scrubbed!!, netscape) >= 0) // animation looping survives
        assertTrue(indexOf(scrubbed, gce) >= 0)
        assertTrue(indexOf(scrubbed, image) >= 0)
        assertTrue(scrubbed.last() == TestImageFixtures.GIF_TRAILER_INT.toByte())
        assertFalse(indexOf(scrubbed, comment) >= 0)
        assertFalse(indexOf(scrubbed, xmpApp) >= 0)
        assertFalse(indexOf(scrubbed, "taken at home".toByteArray(Charsets.US_ASCII)) >= 0)
    }

    @Test
    fun gifSpecConformantXmpDataExtensionIsDroppedWhole() {
        // The XMP spec (part 3, §GIF) stores the packet raw — no sub-block length prefixes —
        // closed by the 258-byte magic trailer. The whole extension must still be removed.
        val packet = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?>" +
            "<x:xmpmeta xmlns:x='adobe:ns:meta/'>taken at home</x:xmpmeta><?xpacket end='w'?>"
        val xmpApp = TestImageFixtures.gifXmpApplicationExtension(packet)
        val netscape = TestImageFixtures.gifNetscapeExtension()
        val gce = TestImageFixtures.gifGraphicControlExtension()
        val image = TestImageFixtures.gifImageBlock(byteArrayOf(1, 2, 3))
        val original = TestImageFixtures.gif(gce, netscape, xmpApp, image)

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        // The surviving blocks are exactly the pre-XMP ones, byte-for-byte.
        val expected = TestImageFixtures.gif(gce, netscape, image)
        assertTrue(scrubbed!!.contentEquals(expected))
        assertFalse(indexOf(scrubbed, packet.toByteArray(Charsets.UTF_8)) >= 0)
    }

    @Test
    fun gifWithoutMetadataIsReturnedUnchanged() {
        val original = TestImageFixtures.gif(
            TestImageFixtures.gifGraphicControlExtension(),
            TestImageFixtures.gifNetscapeExtension(),
            TestImageFixtures.gifImageBlock(byteArrayOf(1, 2, 3))
        )
        assertSame(original, ImageMetadataScrubber.scrub(original))
    }

    @Test
    fun gifWithMalformedSubBlocksIsRejected() {
        // Comment extension whose sub-block chain never terminates within the file.
        val broken = TestImageFixtures.gif(
            TestImageFixtures.gifGraphicControlExtension(),
            byteArrayOf(0x21, 0xFE.toByte(), 0x10, 1, 2, 3) // claims 16 data bytes, file ends
        )
        assertNull(ImageMetadataScrubber.scrub(broken))
    }

    @Test
    fun gifTrailingContentAfterTrailerIsDropped() {
        val trailing = "extra-blocks-after-the-end-marker".toByteArray(Charsets.US_ASCII)
        val original = TestImageFixtures.gif(
            TestImageFixtures.gifGraphicControlExtension(),
            TestImageFixtures.gifImageBlock(byteArrayOf(1, 2, 3))
        ) + trailing

        val scrubbed = ImageMetadataScrubber.scrub(original)!!

        assertFalse(indexOf(scrubbed, trailing) >= 0)
        assertTrue(scrubbed.last() == TestImageFixtures.GIF_TRAILER_INT.toByte())
    }

    // endregion

    // region pass-through content

    @Test
    fun nonImageAndUnknownContentIsNeverTouched() {
        val log = "INFO everything is fine".toByteArray(Charsets.US_ASCII)
        assertSame(log, ImageMetadataScrubber.scrub(log))
        val heicLike = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray(Charsets.US_ASCII)
        assertSame(heicLike, ImageMetadataScrubber.scrub(heicLike))

        val empty = ByteArray(0)
        assertSame(empty, ImageMetadataScrubber.scrub(empty))
        val tooShort = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertSame(tooShort, ImageMetadataScrubber.scrub(tooShort))
    }

    // endregion

    /** Extracts the TIFF block from the first APP1 segment of a JPEG produced by [jpeg]. */
    private fun exifTiffOf(data: ByteArray): ByteArray {
        val payload = findApp1Payload(data) ?: error("no APP1 segment found in fixture output")
        return payload.copyOfRange(6, payload.size)
    }
}
