package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtBuddyHeartRateDecoderTest {
    @Test
    fun `complete frame returns validated sample and injected timestamps`() {
        val result = decoder().feed(heartRateFrame(bpm = 78, sequence = 17))

        assertEquals(1, result.relatedFrameCount)
        assertEquals(0, result.rejectedFrameCount)
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, result.rawLoggingPolicy)
        assertTrue(result.passthroughPackets.isEmpty())

        val sample = result.samples.single()
        assertEquals(78, sample.bpm)
        assertEquals(17, sample.sequence)
        assertEquals(WALL_CLOCK_MILLIS, sample.receivedAtMillis)
        assertEquals(ELAPSED_REALTIME_MILLIS, sample.receivedAtElapsedRealtime)
    }

    @Test
    fun `frame decodes across every socket split boundary`() {
        val frame = heartRateFrame(bpm = 79)

        for (splitAt in 1 until frame.size) {
            val decoder = decoder()
            val first = decoder.feed(frame.copyOfRange(0, splitAt))
            val second = decoder.feed(frame.copyOfRange(splitAt, frame.size))

            assertTrue(first.samples.isEmpty(), "split=$splitAt")
            assertTrue(first.passthroughPackets.isEmpty(), "split=$splitAt")
            assertEquals(
                if (splitAt >= 5) {
                    HeartRateRawLoggingPolicy.SUPPRESS
                } else {
                    HeartRateRawLoggingPolicy.ALLOW
                },
                first.rawLoggingPolicy,
                "split=$splitAt",
            )
            assertEquals(1, second.samples.size, "split=$splitAt")
            assertTrue(second.passthroughPackets.isEmpty(), "split=$splitAt")
            assertEquals(
                HeartRateRawLoggingPolicy.SUPPRESS,
                second.rawLoggingPolicy,
                "split=$splitAt",
            )
        }
    }

    @Test
    fun `sticky frames decode independently`() {
        val result = decoder().feed(
            heartRateFrame(bpm = 80, sequence = 1) +
                heartRateFrame(bpm = 81, sequence = 2),
        )

        assertEquals(2, result.relatedFrameCount)
        assertEquals(listOf(1, 2), result.samples.map(HeartRateSample::sequence))
        assertTrue(result.passthroughPackets.isEmpty())
        assertTrue(result.suppressRawLogging)
    }

    @Test
    fun `ordinary packet remains passthrough and loggable`() {
        val ordinary = byteArrayOf(0x01, 0x22, 0x03, 0x44)

        val result = decoder().feed(ordinary)

        assertEquals(0, result.relatedFrameCount)
        assertTrue(result.samples.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.ALLOW, result.rawLoggingPolicy)
        assertEquals(1, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets.single().contentEquals(ordinary))
    }

    @Test
    fun `ordinary bytes around a complete heart rate frame remain passthrough`() {
        val before = byteArrayOf(0x31, 0x32, 0x33)
        val after = byteArrayOf(0x41, 0x42)

        val result = decoder().feed(before + heartRateFrame(bpm = 82) + after)

        assertEquals(1, result.samples.size)
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, result.rawLoggingPolicy)
        assertEquals(2, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets[0].contentEquals(before))
        assertTrue(result.passthroughPackets[1].contentEquals(after))
    }

    @Test
    fun `heart rate frame after ordinary bytes can continue in the next read`() {
        val ordinary = byteArrayOf(0x31, 0x32, 0x33)
        val frame = heartRateFrame(bpm = 82)
        val decoder = decoder()

        val first = decoder.feed(ordinary + frame.copyOfRange(0, 8))
        val second = decoder.feed(frame.copyOfRange(8, frame.size))

        assertTrue(first.samples.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, first.rawLoggingPolicy)
        assertEquals(1, first.passthroughPackets.size)
        assertTrue(first.passthroughPackets.single().contentEquals(ordinary))
        assertEquals(1, second.samples.size)
        assertTrue(second.passthroughPackets.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, second.rawLoggingPolicy)
    }

    @Test
    fun `prefix-like bytes inside an ordinary packet never truncate it`() {
        val ordinary = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x55, 0x00, 0x7F) +
            RTBUDDY_PREFIX.copyOf(5) +
            RTBUDDY_PREFIX +
            byteArrayOf(0x41, 0x42, 0x43)

        val result = decoder().feed(ordinary)

        assertTrue(result.samples.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.ALLOW, result.rawLoggingPolicy)
        assertEquals(1, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets.single().contentEquals(ordinary))
    }

    @Test
    fun `nested command and payload wrappers are traversed within bounds`() {
        val result = decoder().feed(
            heartRateFrame(
                bpm = 83,
                commandWrapperDepth = 2,
                payloadWrapperDepth = 2,
            ),
        )

        assertEquals(1, result.samples.size)
        assertEquals(0, result.rejectedFrameCount)
        assertTrue(result.passthroughPackets.isEmpty())
    }

    @Test
    fun `zero payload length is consumed as invalid and never passed through`() {
        val result = decoder().feed(rtbFrame(ByteArray(0)))

        assertRejected(
            result,
            HeartRateRejectionReason.INVALID_FRAME_LENGTH,
            requiresTransportReset = true,
        )
    }

    @Test
    fun `oversized declared length requests transport reset and hides following data`() {
        val oversizedHeader = RTBUDDY_PREFIX + byteArrayOf(0x01, 0x40)
        val followingFrame = heartRateFrame(bpm = 84)

        val result = decoder().feed(oversizedHeader + followingFrame)

        assertRejected(
            result,
            HeartRateRejectionReason.INVALID_FRAME_LENGTH,
            requiresTransportReset = true,
        )
    }

    @Test
    fun `invalid length remains fail closed until explicit reset`() {
        val decoder = decoder()
        val oversizedHeader = RTBUDDY_PREFIX + byteArrayOf(0x01, 0x40)

        val invalid = decoder.feed(oversizedHeader)
        val poisoned = decoder.feed(
            byteArrayOf(0x55, 0x66) + heartRateFrame(bpm = 84),
        )

        assertRejected(
            invalid,
            HeartRateRejectionReason.INVALID_FRAME_LENGTH,
            requiresTransportReset = true,
        )
        assertTrue(poisoned.requiresTransportReset)
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, poisoned.rawLoggingPolicy)
        assertTrue(poisoned.samples.isEmpty())
        assertTrue(poisoned.passthroughPackets.isEmpty())

        decoder.reset()
        val recovered = decoder.feed(heartRateFrame(bpm = 84))
        assertEquals(1, recovered.samples.size)
        assertFalse(recovered.requiresTransportReset)
    }

    @Test
    fun `malformed unrelated SensorDataWX remains passthrough but raw logging is suppressed`() {
        val frame = rtbFrame(byteArrayOf(0x80.toByte()))
        val result = decoder().feed(frame)

        assertEquals(0, result.relatedFrameCount)
        assertTrue(result.samples.isEmpty())
        assertEquals(1, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets.single().contentEquals(frame))
        assertTrue(result.suppressRawLogging)
    }

    @Test
    fun `wrong inner service is not decoded and remains exact passthrough`() {
        val frame = heartRateFrame(bpm = 84, service = 18)
        val result = decoder().feed(frame)

        assertEquals(0, result.relatedFrameCount)
        assertTrue(result.samples.isEmpty())
        assertEquals(1, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets.single().contentEquals(frame))
        assertTrue(result.suppressRawLogging)
    }

    @Test
    fun `non heart rate service does not recurse into a fake service 19 payload`() {
        val fakeHeartRateCommand = varintField(fieldNumber = 1, value = 19) +
            lengthDelimitedField(
                fieldNumber = 3,
                value = heartRatePayload(bpm = 84, statusTail = VALID_STATUS_TAILS.first()),
            )
        val service14Command = varintField(fieldNumber = 1, value = 14) +
            lengthDelimitedField(fieldNumber = 3, value = fakeHeartRateCommand)
        val frame = rtbFrame(
            varintField(fieldNumber = 1, value = 7) +
                varintField(fieldNumber = 2, value = 1) +
                lengthDelimitedField(fieldNumber = 5, value = service14Command),
        )

        val result = decoder().feed(frame)

        assertEquals(0, result.relatedFrameCount)
        assertTrue(result.samples.isEmpty())
        assertEquals(1, result.passthroughPackets.size)
        assertTrue(result.passthroughPackets.single().contentEquals(frame))
        assertTrue(result.suppressRawLogging)
    }

    @Test
    fun `documented service 14 and 16 head tracking packets remain exact passthrough`() {
        val service14 = hex(
            "04 00 04 00 17 00 00 00 10 00 10 00 " +
                "08 A1 02 42 0B 08 0E 10 02 1A 05 01 40 9C 00 00"
        )
        val service16 = hex(
            "04 00 04 00 17 00 00 00 10 00 0F 00 " +
                "08 73 42 0B 08 10 10 02 1A 05 01 40 9C 00 00"
        )

        listOf(service14, service16).forEach { frame ->
            val result = decoder().feed(frame)

            assertEquals(0, result.relatedFrameCount)
            assertTrue(result.samples.isEmpty())
            assertEquals(1, result.passthroughPackets.size)
            assertTrue(result.passthroughPackets.single().contentEquals(frame))
            assertTrue(result.suppressRawLogging)
        }
    }

    @Test
    fun `wrong log type is sensitive consumed and not passthrough`() {
        val result = decoder().feed(heartRateFrame(bpm = 85, logType = 2))

        assertRejected(result, HeartRateRejectionReason.UNSUPPORTED_LOG_TYPE)
    }

    @Test
    fun `bpm below and above bounds are rejected without samples`() {
        val decoder = decoder()

        val below = decoder.feed(heartRateFrame(bpm = 29))
        val above = decoder.feed(heartRateFrame(bpm = 221))

        assertRejected(below, HeartRateRejectionReason.BPM_OUT_OF_RANGE)
        assertRejected(above, HeartRateRejectionReason.BPM_OUT_OF_RANGE)
    }

    @Test
    fun `all observed status tails are accepted`() {
        val frames = VALID_STATUS_TAILS.mapIndexed { index, tail ->
            heartRateFrame(bpm = 86 + index, statusTail = tail)
        }.reduce(ByteArray::plus)

        val result = decoder().feed(frames)

        assertEquals(VALID_STATUS_TAILS.size, result.samples.size)
        assertEquals(0, result.rejectedFrameCount)
        assertTrue(result.passthroughPackets.isEmpty())
    }

    @Test
    fun `unknown status tail is rejected and remains private`() {
        val result = decoder().feed(
            heartRateFrame(
                bpm = 90,
                statusTail = byteArrayOf(0x30, 0x00, 0x00),
            ),
        )

        assertRejected(result, HeartRateRejectionReason.UNRECOGNIZED_STATUS_TAIL)
    }

    @Test
    fun `sensitive prefix fragment is quarantined even when continuation mismatches`() {
        val decoder = decoder()
        val sensitiveFragment = RTBUDDY_PREFIX.copyOf(5)
        val ordinaryContinuation = byteArrayOf(0x7F, 0x21, 0x22)

        val first = decoder.feed(sensitiveFragment)
        val second = decoder.feed(ordinaryContinuation)

        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, first.rawLoggingPolicy)
        assertTrue(first.passthroughPackets.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, second.rawLoggingPolicy)
        assertEquals(1, second.passthroughPackets.size)
        assertTrue(
            second.passthroughPackets.single().contentEquals(
                sensitiveFragment + ordinaryContinuation
            )
        )
    }

    @Test
    fun `reset discards an incomplete sensitive frame`() {
        val frame = heartRateFrame(bpm = 90)
        val decoder = decoder()

        val first = decoder.feed(frame.copyOfRange(0, 15))
        decoder.reset()
        val second = decoder.feed(frame.copyOfRange(15, frame.size))

        assertTrue(first.suppressRawLogging)
        assertTrue(first.samples.isEmpty())
        assertTrue(second.samples.isEmpty())
        assertEquals(1, second.passthroughPackets.size)
        assertTrue(
            second.passthroughPackets.single().contentEquals(frame.copyOfRange(15, frame.size))
        )
    }

    @Test
    fun `sample string representation does not expose health data`() {
        val sample = decoder().feed(heartRateFrame(bpm = 91)).samples.single()

        assertEquals("HeartRateSample(redacted)", sample.toString())
        assertFalse(sample.toString().contains("bpm="))
    }

    private fun assertRejected(
        result: HeartRateDecodeResult,
        reason: HeartRateRejectionReason,
        requiresTransportReset: Boolean = false,
    ) {
        assertEquals(1, result.relatedFrameCount)
        assertEquals(1, result.rejectedFrameCount)
        assertEquals(1, result.rejectionReasons[reason])
        assertTrue(result.samples.isEmpty())
        assertTrue(result.passthroughPackets.isEmpty())
        assertEquals(HeartRateRawLoggingPolicy.SUPPRESS, result.rawLoggingPolicy)
        assertEquals(requiresTransportReset, result.requiresTransportReset)
    }

    private fun decoder(): RtBuddyHeartRateDecoder = RtBuddyHeartRateDecoder(
        wallClockMillis = { WALL_CLOCK_MILLIS },
        elapsedRealtimeMillis = { ELAPSED_REALTIME_MILLIS },
    )

    private fun heartRateFrame(
        bpm: Int,
        sequence: Int = 7,
        logType: Int = 1,
        service: Int = 19,
        statusTail: ByteArray = VALID_STATUS_TAILS.first(),
        commandWrapperDepth: Int = 0,
        payloadWrapperDepth: Int = 0,
    ): ByteArray {
        var payloadValue = heartRatePayload(bpm, statusTail)
        repeat(payloadWrapperDepth) {
            payloadValue = lengthDelimitedField(fieldNumber = 6, value = payloadValue)
        }

        var command = varintField(fieldNumber = 1, value = service) +
            lengthDelimitedField(fieldNumber = 3, value = payloadValue)
        repeat(commandWrapperDepth) {
            command = lengthDelimitedField(fieldNumber = 6, value = command)
        }

        val topLevel = varintField(fieldNumber = 1, value = sequence) +
            varintField(fieldNumber = 2, value = logType) +
            lengthDelimitedField(fieldNumber = 5, value = command)
        return rtbFrame(topLevel)
    }

    private fun heartRatePayload(bpm: Int, statusTail: ByteArray): ByteArray =
        ByteArray(18).also { payload ->
            payload[1] = bpm.toByte()
            statusTail.copyInto(payload, destinationOffset = 15)
        }

    private fun rtbFrame(payload: ByteArray): ByteArray =
        RTBUDDY_PREFIX +
            byteArrayOf(
                (payload.size and 0xFF).toByte(),
                ((payload.size ushr 8) and 0xFF).toByte(),
            ) +
            payload

    private fun varintField(fieldNumber: Int, value: Int): ByteArray =
        encodeVarint((fieldNumber shl 3).toLong()) + encodeVarint(value.toLong())

    private fun lengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray =
        encodeVarint(((fieldNumber shl 3) or 2).toLong()) +
            encodeVarint(value.size.toLong()) +
            value

    private fun encodeVarint(value: Long): ByteArray {
        var remaining = value
        val encoded = mutableListOf<Byte>()
        do {
            var next = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) next = next or 0x80
            encoded += next.toByte()
        } while (remaining != 0L)
        return encoded.toByteArray()
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        const val WALL_CLOCK_MILLIS = 1_723_456_789_000L
        const val ELAPSED_REALTIME_MILLIS = 42_000L

        val RTBUDDY_PREFIX = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x17, 0x00,
            0x00, 0x00, 0x10, 0x00,
        )
        val VALID_STATUS_TAILS = listOf(
            byteArrayOf(0x10, 0x00, 0x00),
            byteArrayOf(0x20, 0x00, 0x00),
            byteArrayOf(0x20, 0x02, 0x80.toByte()),
            byteArrayOf(0x20, 0x82.toByte(), 0x80.toByte()),
        )
    }
}
