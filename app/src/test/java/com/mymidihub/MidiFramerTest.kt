package com.mymidihub

import org.junit.Assert.*
import org.junit.Test

class MidiFramerTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun `every note number channel and representative velocity passes unchanged`() {
        var expected = byteArrayOf()
        var received = 0
        val framer = MidiFramer { actual, _ ->
            assertArrayEquals("MIDI payload must not be transposed or otherwise altered", expected, actual)
            received++
        }
        for (kind in listOf(0x80, 0x90, 0xA0)) {
            for (channel in 0..15) for (note in 0..127) for (velocity in listOf(0, 1, 64, 127)) {
                expected = bytes(kind or channel, note, velocity)
                for (split in 0..3) {
                    framer.reset()
                    val before = received
                    framer.accept(expected.copyOfRange(0, split), 123)
                    framer.accept(expected.copyOfRange(split, 3), 123)
                    assertEquals("Exactly one message per input", before + 1, received)
                }
            }
        }
        assertEquals(98304, received)
    }

    @Test fun `running status preserves all 128 note numbers on all 16 channels`() {
        for (kind in listOf(0x80, 0x90)) for (channel in 0..15) {
            var expectedNote = 0
            val framer = MidiFramer { actual, _ ->
                assertArrayEquals(bytes(kind or channel, expectedNote, 100), actual)
                expectedNote++
            }
            framer.accept(bytes(kind or channel), 0)
            for (note in 0..127) {
                framer.accept(bytes(note), 0)
                framer.accept(bytes(100), 0)
            }
            assertEquals(128, expectedNote)
        }
    }

    @Test fun `fragmented notes and running status become complete messages`() {
        val messages = mutableListOf<List<Int>>()
        val framer = MidiFramer { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        framer.accept(bytes(0x90, 60), 100)
        assertTrue(messages.isEmpty())
        framer.accept(bytes(100, 62, 80, 0x80, 60, 0), 200)
        assertEquals(listOf(listOf(0x90, 60, 100), listOf(0x90, 62, 80), listOf(0x80, 60, 0)), messages)
    }

    @Test fun `merging sources cannot inherit another source running status`() {
        val messages = mutableListOf<List<Int>>()
        val emit: (ByteArray, Long) -> Unit = { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        val keyboard = MidiFramer(emit)
        val controller = MidiFramer(emit)
        keyboard.accept(bytes(0x90, 60, 90), 0)
        controller.accept(bytes(0xB1, 7, 100), 0)
        keyboard.accept(bytes(62, 80), 0)
        controller.accept(bytes(7, 60), 0)
        assertEquals(listOf(listOf(0x90, 60, 90), listOf(0xB1, 7, 100), listOf(0x90, 62, 80), listOf(0xB1, 7, 60)), messages)
    }

    @Test fun `realtime bytes do not disturb incomplete messages`() {
        val messages = mutableListOf<List<Int>>()
        val times = mutableListOf<Long>()
        val framer = MidiFramer { data, time -> messages.add(data.map { it.toInt() and 255 }); times.add(time) }
        framer.accept(bytes(0x90, 60, 0xF8, 100, 0xFE), 456)
        assertEquals(listOf(listOf(0xF8), listOf(0x90, 60, 100), listOf(0xFE)), messages)
        assertEquals(listOf(456L, 456L, 456L), times)
    }

    @Test fun `sysex is buffered atomically while realtime remains immediate`() {
        val messages = mutableListOf<List<Int>>()
        val times = mutableListOf<Long>()
        val framer = MidiFramer { data, time -> messages.add(data.map { it.toInt() and 255 }); times.add(time) }
        framer.accept(bytes(0xF0, 0x7D, 1), 100)
        framer.accept(bytes(2, 0xF8, 3, 0xF7), 200)
        assertEquals(listOf(listOf(0xF8), listOf(0xF0, 0x7D, 1, 2, 3, 0xF7)), messages)
        assertEquals(listOf(200L, 100L), times)
    }

    @Test fun `program change channel pressure and system common lengths`() {
        val messages = mutableListOf<List<Int>>()
        val framer = MidiFramer { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        framer.accept(bytes(0xC0, 5, 6, 0xD0, 12, 0xF1, 1, 0xF2, 1, 2, 0xF3, 4, 0xF6), 0)
        assertEquals(listOf(listOf(0xC0, 5), listOf(0xC0, 6), listOf(0xD0, 12), listOf(0xF1, 1),
            listOf(0xF2, 1, 2), listOf(0xF3, 4), listOf(0xF6)), messages)
    }

    @Test fun `system common clears channel running status`() {
        val messages = mutableListOf<List<Int>>()
        val framer = MidiFramer { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        framer.accept(bytes(0x90, 60, 100, 0xF1, 3, 62, 80), 0)
        assertEquals(listOf(listOf(0x90, 60, 100), listOf(0xF1, 3)), messages)
    }

    @Test fun `oversized or interrupted sysex does not poison subsequent messages`() {
        val messages = mutableListOf<List<Int>>()
        val framer = MidiFramer { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        framer.accept(bytes(0xF0), 0)
        framer.accept(ByteArray(MidiFramer.MAX_SYSEX + 1), 0)
        framer.accept(bytes(0xF7, 0x90, 60, 100, 0xF0, 1, 0x80, 60, 0), 0)
        assertEquals(listOf(listOf(0x90, 60, 100), listOf(0x80, 60, 0)), messages)
    }

    @Test fun `reset drops a partial message and running status`() {
        val messages = mutableListOf<List<Int>>()
        val framer = MidiFramer { data, _ -> messages.add(data.map { it.toInt() and 255 }) }
        framer.accept(bytes(0x90, 60), 0); framer.reset()
        framer.accept(bytes(100, 62, 80), 0)
        assertTrue(messages.isEmpty())
        framer.accept(bytes(0x90, 64, 90), 0)
        assertEquals(listOf(listOf(0x90, 64, 90)), messages)
    }

    @Test fun `fragmented messages retain timestamp of their first byte`() {
        val times = mutableListOf<Long>()
        val framer = MidiFramer { _, time -> times.add(time) }
        framer.accept(bytes(0x90), 100)
        framer.accept(bytes(60, 100), 200)
        framer.accept(bytes(62), 300)
        framer.accept(bytes(100), 400)
        assertEquals(listOf(100L, 300L), times)
    }

    @Test fun `fragmentation is equivalent for every split point`() {
        val stream = bytes(0x90, 60, 100, 62, 100, 0xB0, 7, 100, 0xF0, 0x7D, 1, 0xF8, 2, 0xF7, 0x80, 60, 0)
        fun framed(parts: List<ByteArray>): List<List<Int>> {
            val result = mutableListOf<List<Int>>()
            val framer = MidiFramer { data, _ -> result.add(data.map { it.toInt() and 255 }) }
            parts.forEach { framer.accept(it, 123) }
            return result
        }
        val expected = framed(listOf(stream))
        for (split in 0..stream.size) assertEquals(expected, framed(listOf(stream.copyOfRange(0, split), stream.copyOfRange(split, stream.size))))
        assertEquals(expected, framed(stream.map { byteArrayOf(it) }))
    }
}
