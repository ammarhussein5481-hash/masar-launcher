package cloud.ammarhussein.masar

/**
 * Plain-language names for the fault codes seen most often. The generic codes
 * (P0xxx) mean the same on every make; anything not listed falls back to the
 * system the code belongs to.
 */
object Dtc {

    fun describe(code: String): String = NAMES[code] ?: family(code)

    private fun family(code: String): String {
        if (code.length < 3) return ""
        return when (code[0]) {
            'P' -> when (code[2]) {
                '0' -> "Fuel, air or emissions"
                '1' -> "Fuel and air metering"
                '2' -> "Injector circuit"
                '3' -> "Ignition or misfire"
                '4' -> "Emission controls"
                '5' -> "Speed or idle control"
                '6' -> "Engine computer"
                '7', '8', '9' -> "Transmission"
                else -> "Engine or transmission"
            }
            'C' -> "Chassis: brakes, steering or suspension"
            'B' -> "Body: airbags, climate or lighting"
            'U' -> "Communication between computers"
            else -> ""
        }
    }

    private val NAMES = mapOf(
        "P0010" to "Intake camshaft actuator circuit (bank 1)",
        "P0011" to "Intake camshaft timing over-advanced (bank 1)",
        "P0013" to "Exhaust camshaft actuator circuit (bank 1)",
        "P0014" to "Exhaust camshaft timing over-advanced (bank 1)",
        "P0016" to "Crankshaft and camshaft positions do not agree",
        "P0030" to "Oxygen sensor heater circuit (bank 1, sensor 1)",
        "P0036" to "Oxygen sensor heater circuit (bank 1, sensor 2)",
        "P0087" to "Fuel rail pressure too low",
        "P0101" to "Air flow sensor reading out of range",
        "P0102" to "Air flow sensor signal low",
        "P0103" to "Air flow sensor signal high",
        "P0106" to "Manifold pressure sensor out of range",
        "P0112" to "Intake air temperature sensor low",
        "P0113" to "Intake air temperature sensor high",
        "P0116" to "Coolant temperature sensor out of range",
        "P0117" to "Coolant temperature sensor low",
        "P0118" to "Coolant temperature sensor high",
        "P0121" to "Throttle position sensor out of range",
        "P0128" to "Engine not reaching temperature (thermostat)",
        "P0131" to "Oxygen sensor voltage low (bank 1, sensor 1)",
        "P0133" to "Oxygen sensor slow to respond (bank 1, sensor 1)",
        "P0135" to "Oxygen sensor heater (bank 1, sensor 1)",
        "P0137" to "Oxygen sensor voltage low (bank 1, sensor 2)",
        "P0138" to "Oxygen sensor voltage high (bank 1, sensor 2)",
        "P0141" to "Oxygen sensor heater (bank 1, sensor 2)",
        "P0151" to "Oxygen sensor voltage low (bank 2, sensor 1)",
        "P0153" to "Oxygen sensor slow to respond (bank 2, sensor 1)",
        "P0155" to "Oxygen sensor heater (bank 2, sensor 1)",
        "P0161" to "Oxygen sensor heater (bank 2, sensor 2)",
        "P0171" to "Mixture too lean (bank 1)",
        "P0172" to "Mixture too rich (bank 1)",
        "P0174" to "Mixture too lean (bank 2)",
        "P0175" to "Mixture too rich (bank 2)",
        "P0191" to "Fuel rail pressure sensor out of range",
        "P0201" to "Injector circuit, cylinder 1",
        "P0202" to "Injector circuit, cylinder 2",
        "P0203" to "Injector circuit, cylinder 3",
        "P0204" to "Injector circuit, cylinder 4",
        "P0205" to "Injector circuit, cylinder 5",
        "P0206" to "Injector circuit, cylinder 6",
        "P0207" to "Injector circuit, cylinder 7",
        "P0208" to "Injector circuit, cylinder 8",
        "P0300" to "Misfire on several cylinders",
        "P0301" to "Misfire, cylinder 1",
        "P0302" to "Misfire, cylinder 2",
        "P0303" to "Misfire, cylinder 3",
        "P0304" to "Misfire, cylinder 4",
        "P0305" to "Misfire, cylinder 5",
        "P0306" to "Misfire, cylinder 6",
        "P0307" to "Misfire, cylinder 7",
        "P0308" to "Misfire, cylinder 8",
        "P0325" to "Knock sensor circuit (bank 1)",
        "P0335" to "Crankshaft position sensor circuit",
        "P0340" to "Camshaft position sensor circuit",
        "P0401" to "Exhaust gas recirculation flow too low",
        "P0420" to "Catalytic converter efficiency low (bank 1)",
        "P0430" to "Catalytic converter efficiency low (bank 2)",
        "P0440" to "Fuel vapour system fault",
        "P0441" to "Fuel vapour purge flow incorrect",
        "P0442" to "Fuel vapour system small leak",
        "P0446" to "Fuel vapour vent control",
        "P0449" to "Fuel vapour vent valve circuit",
        "P0455" to "Fuel vapour system large leak (check the fuel cap)",
        "P0456" to "Fuel vapour system very small leak",
        "P0461" to "Fuel level sensor out of range",
        "P0496" to "Fuel vapour flow when it should be closed",
        "P0500" to "Vehicle speed sensor",
        "P0506" to "Idle speed lower than expected",
        "P0507" to "Idle speed higher than expected",
        "P0521" to "Oil pressure sensor out of range",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0601" to "Engine computer memory fault",
        "P0606" to "Engine computer processor fault",
        "P0700" to "Transmission control system fault",
        "P0711" to "Transmission fluid temperature sensor out of range",
        "P0717" to "Transmission input speed sensor: no signal",
        "P0722" to "Transmission output speed sensor: no signal",
        "P0741" to "Torque converter clutch stuck off",
        "P0751" to "Shift solenoid A stuck off",
        "P0756" to "Shift solenoid B stuck off",
        "P0776" to "Pressure control solenoid B stuck off",
        "P0796" to "Pressure control solenoid C stuck off",
        "P2135" to "Throttle position sensors do not agree",
        "P2138" to "Accelerator pedal sensors do not agree",
        "P219A" to "Air-fuel ratio imbalance (bank 1)",
        "P219B" to "Air-fuel ratio imbalance (bank 2)",
        "P2227" to "Barometric pressure sensor out of range",
        "P3400" to "Cylinder deactivation system (bank 1)",
        "U0073" to "Communication bus off",
        "U0100" to "Lost communication with the engine computer",
        "U0101" to "Lost communication with the transmission computer",
        "U0121" to "Lost communication with the ABS computer",
        "U0140" to "Lost communication with the body computer"
    )
}
