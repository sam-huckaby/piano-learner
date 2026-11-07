package com.notesprint.app

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper

/** Android MIDI service handles USB enumeration and access; no raw USB permission flow needed. */
class UsbMidi(
    context: Context,
    private val onMessage: (String, Int, Int, Int) -> Unit,
    private val onDisconnect: (String) -> Unit,
    private val onStatus: (String, Boolean) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val devices = mutableMapOf<Int, MidiDevice>()
    private val ports = mutableMapOf<Int, List<MidiOutputPort>>()
    private val opening = mutableSetOf<Int>()
    private var active = false
    private var generation = 0
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = open(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            opening.remove(info.id)
            ports.remove(info.id)?.forEach { runCatching { it.close() } }
            devices.remove(info.id)?.let { runCatching { it.close() } }
            onDisconnect(info.id.toString())
            updateStatus()
        }
    }

    fun start() {
        if (active) return
        active = true
        if (manager == null) { onStatus("This device does not support Android MIDI.", false); return }
        @Suppress("DEPRECATION") // Required for MIDI 1.0 devices on API 25–32.
        manager.registerDeviceCallback(callback, handler)
        @Suppress("DEPRECATION")
        manager.devices.forEach(::open)
        updateStatus()
    }

    private fun open(info: MidiDeviceInfo) {
        if (!active || info.type != MidiDeviceInfo.TYPE_USB || info.outputPortCount == 0 || devices.containsKey(info.id) || !opening.add(info.id)) return
        val session = generation
        try {
            manager?.openDevice(info, { device ->
                if (session != generation) { device?.close(); return@openDevice }
                opening.remove(info.id)
                if (!active || device == null) {
                    device?.close()
                    if (active) onStatus("Could not open the USB piano. Reconnect it and try again.", devices.isNotEmpty())
                    return@openDevice
                }
                // Ignore a late callback for a device that has already been unplugged.
                @Suppress("DEPRECATION")
                val present = manager.devices.any { it.id == info.id }
                if (!present) { device.close(); return@openDevice }
                val opened = mutableListOf<MidiOutputPort>()
                for (portInfo in info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }) {
                    val port = device.openOutputPort(portInfo.portNumber) ?: continue
                    val source = "${info.id}:${portInfo.portNumber}"
                    val parser = MidiParser { status, note, velocity ->
                        handler.post { if (active && session == generation && devices.containsKey(info.id)) onMessage(source, status, note, velocity) }
                    }
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            parser.receive(msg, offset, count)
                        }
                    })
                    opened.add(port)
                }
                if (opened.isEmpty()) device.close()
                else { devices[info.id] = device; ports[info.id] = opened }
                updateStatus()
            }, handler)
        } catch (_: SecurityException) {
            opening.remove(info.id)
            onStatus("Unable to access the USB piano. Reconnect it and try again.", false)
        }
    }

    private fun updateStatus() {
        val names = devices.values.map { it.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB piano" }
        onStatus(if (names.isEmpty()) "Connect a USB MIDI piano using a USB-C cable or host adapter." else "Connected: ${names.joinToString()}", names.isNotEmpty())
    }

    override fun close() {
        active = false
        generation++
        manager?.unregisterDeviceCallback(callback)
        ports.values.flatten().forEach { runCatching { it.close() } }
        devices.values.forEach { runCatching { it.close() } }
        devices.keys.forEach { onDisconnect(it.toString()) }
        ports.clear(); devices.clear(); opening.clear()
    }
}
