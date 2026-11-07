package com.notesprint.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    @Test fun parsesSplitPacketsRunningStatusAndRealtime() {
        val messages = mutableListOf<List<Int>>()
        val parser = MidiParser { status, note, velocity -> messages.add(listOf(status, note, velocity)) }
        parser.receive(byteArrayOf(0x90.toByte(), 60), 0, 2)
        parser.receive(byteArrayOf(0xf8.toByte(), 100, 62, 0), 0, 4)
        assertEquals(listOf(listOf(0x90, 60, 100), listOf(0x90, 62, 0)), messages)
    }

    @Test fun ignoresSysExAndHandlesOneByteMessages() {
        val messages = mutableListOf<List<Int>>()
        val parser = MidiParser { s, a, b -> messages.add(listOf(s, a, b)) }
        val bytes = intArrayOf(0xf0, 1, 2, 3, 0xf7, 0xc0, 10, 0x80, 60, 0).map { it.toByte() }.toByteArray()
        parser.receive(bytes, 0, bytes.size)
        assertEquals(listOf(listOf(0xc0, 10, 0), listOf(0x80, 60, 0)), messages)
    }
}
