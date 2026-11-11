package cloud.ammarhussein.masar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.util.UUID

/**
 * Reads the car's own figures through a Bluetooth ELM327 dongle in the OBD port.
 *
 * One background thread does everything: it connects to the dongle chosen in
 * the settings menu (or the first paired device with "OBD" in its name), sets
 * the ELM327 up, then asks the engine computer for one value after another and
 * keeps the latest answers in the fields below for the Car page to draw.
 * A value the car does not report stays null and gets no tile.
 *
 * It stays connected for the whole drive. While Masar is on screen it asks
 * several times a second; behind another app it only checks the few values
 * the warnings need ([HandleService] shows those) every few seconds.
 */
@SuppressLint("MissingPermission")
object Obd {

    const val PREF_MAC = "obd_mac"

    const val NO_BLUETOOTH = 0
    const val NO_DEVICE = 1
    const val CONNECTING = 2
    const val CONNECTED = 3
    const val FAILED = 4

    @Volatile var state = NO_DEVICE
        private set
    @Volatile var deviceName = ""
        private set

    @Volatile var rpm: Int? = null
        private set
    @Volatile var speed: Int? = null
        private set
    @Volatile var coolant: Int? = null
        private set
    @Volatile var intake: Int? = null
        private set
    @Volatile var load: Int? = null
        private set
    @Volatile var fuel: Int? = null
        private set
    @Volatile var volts: Float? = null
        private set
    /** Transmission fluid temperature, read with a GM-specific request. */
    @Volatile var trans: Int? = null
        private set
    /** Fuel being burned right now, litres per hour, worked out from the air flow. */
    @Volatile var litersPerHour: Float? = null
        private set
    /** The car's own oil life monitor, percent left (GM-specific). */
    @Volatile var oilLife: Int? = null
        private set
    /** Tyre pressures in psi: front left, front right, rear right, rear left (GM-specific). */
    @Volatile var tyres: IntArray? = null
        private set
    /** What the car answered to each GM-specific request at connection, for the report in settings. */
    @Volatile var report = ""
        private set

    /** Stored fault codes ("P0420"), or null until they have been read. */
    @Volatile var codes: List<String>? = null
        private set
    /** Wall-clock time [codes] was read. */
    @Volatile var codesAt = 0L
        private set

    @Volatile private var wantCodes = true
    @Volatile private var wantClear = false
    @Volatile private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    /** PIDs 0x01 to 0x60 the engine computer says it supports. */
    private val supported = BooleanArray(0x61)
    private var transWorks = false
    private var oilWorks = 0
    private var tyresWork = 0
    private var mafAt = 0L
    /** Set if asking the body computer ever left the dongle deaf to the engine. */
    private var skipBody = false
    private var failures = 0
    private const val CODES_EVERY_MS = 120_000L

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun start(context: Context) {
        val running = thread
        if (running != null && running.isAlive) return
        val app = context.applicationContext
        thread = Thread({ run(app) }, "masar-obd").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Drops the connection so the thread starts over, after another dongle was chosen. */
    fun reconnect() {
        close()
    }

    fun readCodes() {
        wantCodes = true
    }

    fun clearCodes() {
        wantClear = true
    }

    /** Paired Bluetooth devices, as name and address, or null if the unit has no Bluetooth Android can use. */
    fun paired(): List<Pair<String, String>>? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            adapter.bondedDevices.map { (it.name ?: it.address) to it.address }
        } catch (ex: Exception) {
            null
        }
    }

    private fun pick(context: Context): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = adapter.bondedDevices
        val saved = Options.prefs(context).getString(PREF_MAC, "") ?: ""
        if (saved.isNotEmpty()) return bonded.firstOrNull { it.address == saved }
        return bonded.firstOrNull { (it.name ?: "").contains("OBD", ignoreCase = true) }
            ?: bonded.firstOrNull { (it.name ?: "").contains("ELM", ignoreCase = true) }
    }

    // ---------------------------------------------------------------- thread

    private fun run(context: Context) {
        while (true) {
            try {
                session(context)
            } catch (ex: Exception) {
                if (state == CONNECTED || state == CONNECTING) state = FAILED
            }
            close()
            blank()
            // Dialling a dongle that isn't there keeps the Bluetooth radio busy,
            // so the retries thin out, quickest while Masar is on screen.
            failures = if (state == FAILED) failures + 1 else 0
            val waitMs = if (HandleService.homeVisible) 8000L else minOf(8000L * (1 + failures), 60_000L)
            pause(waitMs)
        }
    }

    private fun session(context: Context) {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (ex: Exception) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            state = NO_BLUETOOTH
            return
        }
        val device = pick(context)
        if (device == null) {
            state = NO_DEVICE
            deviceName = ""
            return
        }
        deviceName = device.name ?: device.address
        state = CONNECTING
        try {
            adapter.cancelDiscovery()
        } catch (ex: Exception) {
            // Not discovering
        }
        var link: BluetoothSocket
        try {
            link = device.createRfcommSocketToServiceRecord(SPP)
            socket = link
            link.connect()
        } catch (ex: IOException) {
            close()
            // Cheap dongles often refuse the secure channel but take this one.
            link = device.createInsecureRfcommSocketToServiceRecord(SPP)
            socket = link
            link.connect()
        }

        setUp(link)
        state = CONNECTED
        wantCodes = true

        failures = 0
        var turn = 0
        var codesDue = SystemClock.elapsedRealtime() + CODES_EVERY_MS
        while (true) {
            // New fault codes are looked for every couple of minutes.
            if (SystemClock.elapsedRealtime() > codesDue) wantCodes = true
            if (wantCodes) codesDue = SystemClock.elapsedRealtime() + CODES_EVERY_MS
            if (!HandleService.homeVisible) {
                // Behind another app: just what the warnings need, slowly.
                if (wantCodes) {
                    wantCodes = false
                    codes = parseCodes(ask(link, "03", 5000))
                    codesAt = System.currentTimeMillis()
                }
                rpm = value(link, 0x0C)?.let { if (it.size >= 2) (it[0] * 256 + it[1]) / 4 else null }
                speed = value(link, 0x0D)?.getOrNull(0)
                fuelFlow(link)
                // The rest take turns, so the fuel count is sampled every two seconds.
                when (turn++ % 4) {
                    0 -> coolant = value(link, 0x05)?.getOrNull(0)?.minus(40)
                    1 -> volts = voltage(link)
                    2 -> if (transWorks) trans = transTemp(link)
                    3 -> if (turn % 40 == 3) extras(link)
                }
                pause(2000)
                continue
            }
            if (wantClear) {
                wantClear = false
                ask(link, "04", 5000)
                wantCodes = true
            }
            if (wantCodes) {
                wantCodes = false
                codes = parseCodes(ask(link, "03", 5000))
                codesAt = System.currentTimeMillis()
            }
            // RPM and speed every round; the slow-moving values take turns.
            rpm = value(link, 0x0C)?.let { if (it.size >= 2) (it[0] * 256 + it[1]) / 4 else null }
            speed = value(link, 0x0D)?.getOrNull(0)
            fuelFlow(link)
            if (turn % 120 == 60) extras(link)
            when (turn++ % 6) {
                0 -> coolant = value(link, 0x05)?.getOrNull(0)?.minus(40)
                1 -> intake = value(link, 0x0F)?.getOrNull(0)?.minus(40)
                2 -> load = value(link, 0x04)?.getOrNull(0)?.let { it * 100 / 255 }
                3 -> fuel = value(link, 0x2F)?.getOrNull(0)?.let { it * 100 / 255 }
                4 -> volts = voltage(link)
                5 -> {
                    // The transmission computer sleeps with the key off, so a
                    // failed first try is repeated now and then.
                    if (transWorks || turn % 60 == 0) {
                        trans = transTemp(link)
                        if (trans != null) transWorks = true
                    }
                }
            }
            pause(40)
        }
    }

    /** Resets the ELM327, switches off everything that clutters its answers, and finds the car's protocol. */
    private fun setUp(link: BluetoothSocket) {
        ask(link, "ATZ", 4000)
        ask(link, "ATE0", 2000)
        ask(link, "ATL0", 2000)
        ask(link, "ATS0", 2000)
        ask(link, "ATH0", 2000)
        ask(link, "ATSP0", 2000)
        supported.fill(false)
        // Several computers answer these (engine, transmission), each with its
        // own list, in no fixed order. A value counts as available if any of
        // them has it.
        var first: List<IntArray> = emptyList()
        // The first request also makes the dongle search for the protocol, which can take a few tries.
        for (attempt in 0 until 4) {
            first = allBytes(ask(link, "0100", 9000), "4100")
            if (first.isNotEmpty()) break
            pause(1500)
        }
        if (first.isEmpty()) throw IOException("The car did not answer")
        for (bits in first) mark(bits, 0x00)
        if (supported[0x20]) for (bits in allBytes(ask(link, "0120", 3000), "4120")) mark(bits, 0x20)
        if (supported[0x40]) for (bits in allBytes(ask(link, "0140", 3000), "4140")) mark(bits, 0x40)
        transWorks = transTemp(link) != null
        probe(link)
        mafAt = 0L
    }

    /**
     * Fuel flow from the air the engine is breathing: a petrol engine burns
     * one part fuel to 14.7 parts air, and a litre of petrol weighs about
     * 745 g. Good to within roughly a tenth. Each sample is also added to the
     * trip's fuel total.
     */
    private fun fuelFlow(link: BluetoothSocket) {
        val raw = value(link, 0x10)
        val now = SystemClock.elapsedRealtime()
        if (raw == null || raw.size < 2) {
            litersPerHour = null
            mafAt = 0L
            return
        }
        val gramsPerSecond = (raw[0] * 256 + raw[1]) / 100.0
        val litersPerSecond = gramsPerSecond / (14.7 * 745.0)
        litersPerHour = (litersPerSecond * 3600.0).toFloat()
        val running = (rpm ?: 0) > 300
        if (running && mafAt != 0L && now - mafAt < 8000L) Trip.addFuel(litersPerSecond * (now - mafAt) / 1000.0)
        mafAt = now
    }

    // ---------------------------------------------------------------- GM extras

    /**
     * One GM-specific value: request 22 [pid] sent to the computer at [header],
     * whose answers come from [reply] ("" for the engine and transmission
     * computers, which the dongle listens to anyway). Returns the data bytes.
     * The dongle is put back to normal afterwards.
     */
    private fun extended(link: BluetoothSocket, header: String, reply: String, pid: String, log: StringBuilder? = null): IntArray? {
        var out: IntArray? = null
        try {
            ask(link, "ATSH$header", 2000)
            if (reply.isNotEmpty()) ask(link, "ATCRA$reply", 2000)
            val answer = ask(link, "22$pid", 2500)
            if (log != null) {
                log.append(header).append(" 22").append(pid).append(" -> ")
                    .append(answer.replace('\r', ' ').trim()).append('\n')
            }
            out = bytes(answer, "62$pid")
        } finally {
            if (reply.isNotEmpty()) ask(link, "ATAR", 2000)
            ask(link, "ATSH7DF", 2000)
        }
        return out
    }

    /** At connection: finds out which of the GM extras this car answers, and keeps the raw answers. */
    private fun probe(link: BluetoothSocket) {
        val log = StringBuilder()
        oilWorks = 0
        tyresWork = 0
        if (extended(link, "7E0", "", "119F", log)?.getOrNull(0)?.let { it in 0..100 } == true) oilWorks = 1
        else if (extended(link, "7E0", "", "4DC0", log) != null) oilWorks = 2
        // Tyres: the body computer on newer trucks, the engine computer on older ones.
        var body: IntArray? = null
        if (!skipBody) {
            body = extended(link, "241", "641", "5005", log)
            // Listening to the body computer means changing the dongle's filter.
            // If this dongle can't change it back, the engine goes silent: start
            // over and leave the body computer alone from then on.
            if (bytes(ask(link, "0100", 4000), "4100") == null) {
                skipBody = true
                throw IOException("Dongle stuck on the body computer")
            }
        } else {
            log.append("241 skipped: this dongle cannot switch back\n")
        }
        if (body != null && body.size >= 2) tyresWork = 1
        else if (extended(link, "7E0", "", "248E", log)?.getOrNull(0)?.let { it in 1..254 } == true) tyresWork = 2
        log.append("oil=").append(oilWorks).append(" tyres=").append(tyresWork).append(" trans=").append(transWorks)
        report = log.toString()
        extras(link)
    }

    /** Oil life and tyre pressures: slow-moving, so read only now and then. */
    private fun extras(link: BluetoothSocket) {
        when (oilWorks) {
            1 -> oilLife = extended(link, "7E0", "", "119F")?.getOrNull(0)?.takeIf { it in 0..100 }
            2 -> oilLife = extended(link, "7E0", "", "4DC0")?.getOrNull(0)?.let { it * 100 / 255 }
        }
        if (tyresWork == 0) return
        val psi = IntArray(4)
        for (i in 0 until 4) {
            val reading = if (tyresWork == 1) {
                // Sixteenths of a kilopascal.
                extended(link, "241", "641", TYRE_BODY[i])?.let { if (it.size >= 2) ((it[0] * 256 + it[1]) / 16.0 * 0.145038).toInt() else null }
            } else {
                extended(link, "7E0", "", TYRE_ENGINE[i])?.getOrNull(0)
            }
            // No sensor signal yet reads as zero or full scale.
            if (reading == null || reading <= 0 || reading >= 120) {
                tyres = null
                return
            }
            psi[i] = reading
        }
        tyres = psi
    }

    // Front left, front right, rear right, rear left.
    private val TYRE_BODY = arrayOf("5005", "5006", "5007", "5008")
    private val TYRE_ENGINE = arrayOf("248E", "248F", "2490", "2491")

    /**
     * System voltage. The engine computer's own figure (PID 42) is the one to
     * trust; the dongle's built-in voltmeter (ATRV) is only a fallback, because
     * on cheap dongles it can read a volt or two off.
     */
    private fun voltage(link: BluetoothSocket): Float? {
        if (supported[0x42]) {
            val exact = value(link, 0x42)
            val volts = if (exact != null && exact.size >= 2) (exact[0] * 256 + exact[1]) / 1000f else 0f
            // With the key off the computer reports zero: show nothing rather than "0.0 V".
            return if (volts in 6f..20f) volts else null
        }
        return Regex("(\\d+\\.\\d+)").find(ask(link, "ATRV", 2000))?.value?.toFloatOrNull()
    }

    private fun mark(bits: IntArray, base: Int) {
        for (i in 0 until 32) {
            val byte = bits.getOrNull(i / 8) ?: return
            if (byte and (0x80 shr (i % 8)) != 0 && base + i + 1 < supported.size) supported[base + i + 1] = true
        }
    }

    /** The data bytes of a mode 01 value, or null if the car doesn't have it or didn't answer. */
    private fun value(link: BluetoothSocket, pid: Int): IntArray? {
        if (!supported[pid]) return null
        val hex = String.format("%02X", pid)
        return bytes(ask(link, "01$hex", 2500), "41$hex")
    }

    /**
     * Transmission fluid temperature on GM trucks: request 22 1940 to the
     * transmission computer (7E1), answer 62 1940 xx, degrees = xx - 40.
     * The header goes back to the general one afterwards.
     */
    private fun transTemp(link: BluetoothSocket): Int? {
        var result: Int? = null
        try {
            if (ask(link, "ATSH7E1", 2000).contains("OK")) {
                val raw = bytes(ask(link, "221940", 2500), "621940")?.getOrNull(0)
                if (raw != null && raw != 0xFF) result = raw - 40
            }
        } finally {
            ask(link, "ATSH7DF", 2000)
        }
        return if (result != null && result in -40..200) result else null
    }

    // ---------------------------------------------------------------- wire

    /** Sends one command and returns the answer up to the ELM327's ">" prompt, without spaces. */
    private fun ask(link: BluetoothSocket, command: String, timeoutMs: Long): String {
        val input = link.inputStream
        while (input.available() > 0) input.read()
        link.outputStream.write((command + "\r").toByteArray())
        link.outputStream.flush()
        val text = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Dongle closed the link")
                if (c == '>'.code) return text.toString()
                if (c != ' '.code && c != 0) text.append(c.toChar())
            } else {
                Thread.sleep(8)
            }
        }
        if (!link.isConnected) throw IOException("Dongle gone")
        return text.toString()
    }

    /** The bytes that follow [prefix] in the first line of [answer] that has it. */
    private fun bytes(answer: String, prefix: String): IntArray? = allBytes(answer, prefix).firstOrNull()

    /** The same for every line that has [prefix]: one entry per computer that answered. */
    private fun allBytes(answer: String, prefix: String): List<IntArray> {
        val all = ArrayList<IntArray>()
        for (line in answer.split('\r', '\n')) {
            val at = line.indexOf(prefix)
            if (at < 0) continue
            val hex = line.substring(at + prefix.length)
            val out = IntArray(hex.length / 2)
            try {
                for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16)
            } catch (ex: NumberFormatException) {
                continue
            }
            all.add(out)
        }
        return all
    }

    /**
     * Fault codes from a mode 03 answer. On CAN each computer answers
     * "43 nn cccc cccc …"; a long answer comes as numbered lines ("0:", "1:")
     * that are joined first.
     */
    fun parseCodes(answer: String): List<String> {
        val lines = answer.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val messages = ArrayList<String>()
        val joined = StringBuilder()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon in 1..2) {
                if (line.startsWith("0:") && joined.isNotEmpty()) {
                    messages.add(joined.toString())
                    joined.setLength(0)
                }
                joined.append(line.substring(colon + 1))
            } else if (line.startsWith("43")) {
                messages.add(line)
            }
        }
        if (joined.isNotEmpty()) messages.add(joined.toString())

        val found = LinkedHashSet<String>()
        for (message in messages) {
            if (!message.startsWith("43") || message.length < 4) continue
            val count = message.substring(2, 4).toIntOrNull(16) ?: continue
            var at = 4
            var read = 0
            while (read < count && at + 4 <= message.length) {
                val raw = message.substring(at, at + 4).toIntOrNull(16)
                at += 4
                read++
                if (raw == null || raw == 0) continue
                val letter = "PCBU"[raw shr 14]
                found.add(String.format("%c%d%03X", letter, (raw shr 12) and 3, raw and 0xFFF))
            }
        }
        return found.toList()
    }

    private fun blank() {
        rpm = null
        speed = null
        coolant = null
        intake = null
        load = null
        fuel = null
        volts = null
        trans = null
        litersPerHour = null
        oilLife = null
        tyres = null
    }

    private fun close() {
        try {
            socket?.close()
        } catch (ex: Exception) {
            // Already closed
        }
        socket = null
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ex: InterruptedException) {
            // Carry on
        }
    }
}
