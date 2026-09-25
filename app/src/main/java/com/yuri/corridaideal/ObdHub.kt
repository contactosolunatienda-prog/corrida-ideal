package com.yuri.corridaideal

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ObdHub {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)

    fun connect(context: Context, address: String) {
        disconnect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Permita acesso a dispositivos próximos", false)
            return
        }

        worker = Thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Bluetooth indisponível")
                val device = adapter.getRemoteDevice(address)
                setStatus("Conectando a ${device.name ?: address}…", false)
                val s = device.createRfcommSocketToServiceRecord(sppUuid)
                socket = s
                s.connect()
                val input = s.inputStream
                val output = s.outputStream

                initializeElm(input, output)
                running.set(true)
                setStatus("OBD conectado", true)
                val profile = EfficiencyProfile(context.applicationContext)

                var loopCount = 0
                while (running.get() && s.isConnected) {
                    loopCount++
                    val speed = querySpeed(input, output)
                    val fuelRate = queryFuelRate(input, output)
                    val maf = if (fuelRate == null) queryMaf(input, output) else null
                    if (loopCount % 8 == 1) {
                        queryFuelLevel(input, output)?.let { RuntimeState.fuelLevelPercent = it }
                    }
                    val consumption = when {
                        speed != null && speed >= 1.0 && fuelRate != null && fuelRate > 0.05 -> speed / fuelRate
                        speed != null && speed >= 1.0 && maf != null && maf > 0.1 -> {
                            // Gasoline estimate: 14.7:1 stoichiometric AFR, ~745 g/L density.
                            val litersPerHour = maf * 3600.0 / (14.7 * 745.0)
                            if (litersPerHour > 0.05) speed / litersPerHour else null
                        }
                        else -> null
                    }?.takeIf { it in 1.0..60.0 }

                    if (speed != null) RuntimeState.obdSpeedKmh = speed
                    if (consumption != null) {
                        // Light smoothing prevents the display from jumping too much.
                        val old = RuntimeState.obdConsumptionKml
                        val smooth = if (old == null) consumption else old * 0.70 + consumption * 0.30
                        RuntimeState.obdConsumptionKml = smooth
                        if (speed != null) profile.record(speed, smooth)
                        RuntimeState.recalculate(smooth)
                    } else {
                        RuntimeState.notifyChanged()
                    }
                    Thread.sleep(1200)
                }
            } catch (t: Throwable) {
                setStatus("OBD: ${t.message ?: "falha na conexão"}", false)
            } finally {
                running.set(false)
                runCatching { socket?.close() }
                socket = null
                RuntimeState.obdConnected = false
                RuntimeState.notifyChanged()
            }
        }.apply { name = "CorridaIdeal-OBD"; start() }
    }

    fun disconnect() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        worker = null
        RuntimeState.obdConnected = false
        RuntimeState.obdStatus = "OBD desconectado"
        RuntimeState.notifyChanged()
    }

    private fun setStatus(text: String, connected: Boolean) {
        RuntimeState.obdStatus = text
        RuntimeState.obdConnected = connected
        RuntimeState.notifyChanged()
    }

    private fun initializeElm(input: InputStream, output: OutputStream) {
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach {
            sendCommand(input, output, it, 1800)
            Thread.sleep(120)
        }
    }

    private fun querySpeed(input: InputStream, output: OutputStream): Double? {
        val response = sendCommand(input, output, "010D")
        val hex = compact(response)
        val match = Regex("410D([0-9A-F]{2})").find(hex) ?: return null
        return match.groupValues[1].toInt(16).toDouble()
    }

    private fun queryFuelRate(input: InputStream, output: OutputStream): Double? {
        val response = sendCommand(input, output, "015E")
        val hex = compact(response)
        if (hex.contains("NODATA")) return null
        val match = Regex("415E([0-9A-F]{4})").find(hex) ?: return null
        return match.groupValues[1].toInt(16) / 20.0 // L/h, SAE PID 5E scale 0.05
    }


    private fun queryFuelLevel(input: InputStream, output: OutputStream): Double? {
        val response = sendCommand(input, output, "012F")
        val hex = compact(response)
        if (hex.contains("NODATA")) return null
        val match = Regex("412F([0-9A-F]{2})").find(hex) ?: return null
        val a = match.groupValues[1].toInt(16)
        return a * 100.0 / 255.0
    }

    private fun queryMaf(input: InputStream, output: OutputStream): Double? {
        val response = sendCommand(input, output, "0110")
        val hex = compact(response)
        val match = Regex("4110([0-9A-F]{4})").find(hex) ?: return null
        return match.groupValues[1].toInt(16) / 100.0 // g/s
    }

    private fun compact(value: String): String = value.uppercase()
        .replace("SEARCHING...", "")
        .replace(Regex("[^0-9A-Z]"), "")

    private fun sendCommand(
        input: InputStream,
        output: OutputStream,
        command: String,
        timeoutMs: Long = 1300
    ): String {
        while (input.available() > 0) input.read()
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val result = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (input.available() > 0) {
                val ch = input.read()
                if (ch < 0) break
                val c = ch.toChar()
                result.append(c)
                if (c == '>') return result.toString()
            }
            Thread.sleep(20)
        }
        return result.toString()
    }
}
