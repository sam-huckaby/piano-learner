package com.notesprint.app

/** MIDI 1.0 stream parser: running status, split packets, real-time and SysEx. */
class MidiParser(private val message: (Int, Int, Int) -> Unit) {
    private var status = 0
    private val data = IntArray(2)
    private var count = 0

    fun receive(bytes: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xff
            if (value >= 0xf8) continue
            if (value >= 0x80) {
                status = if (value < 0xf0) value else 0
                count = 0
                continue
            }
            if (status == 0) continue
            data[count++] = value
            val expected = if (status and 0xf0 in listOf(0xc0, 0xd0)) 1 else 2
            if (count == expected) {
                message(status, data[0], if (expected == 2) data[1] else 0)
                count = 0
            }
        }
    }
}
