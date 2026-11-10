package cloud.ammarhussein.masar

import android.icu.util.IslamicCalendar
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prayer times and sunrise/sunset for a place and day, worked out on the unit
 * with no internet.
 *
 * Method: Umm al-Qura (Makkah). Fajr when the sun is 18.5° below the horizon,
 * Isha 90 minutes after Maghrib (120 in Ramadan), Asr when a shadow is as long
 * as its object. Times are within a minute or two of the published tables;
 * mosques may still differ slightly.
 */
object PrayerTimes {

    /** Hours after local midnight for each event. [isha] can pass 24. */
    class Day(
        val fajr: Double,
        val sunrise: Double,
        val dhuhr: Double,
        val asr: Double,
        val maghrib: Double,
        val isha: Double
    ) {
        /** The five prayers in order; sunrise is not one of them. */
        fun prayers(): DoubleArray = doubleArrayOf(fajr, dhuhr, asr, maghrib, isha)
    }

    const val FAJR = 0
    const val DHUHR = 1
    const val ASR = 2
    const val MAGHRIB = 3
    const val ISHA = 4

    /** Times for the calendar day [date] falls on, in [zone]. */
    fun forDay(date: Calendar, lat: Double, lon: Double, zone: TimeZone): Day {
        val year = date.get(Calendar.YEAR)
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        val offsetHours = zone.getOffset(date.timeInMillis) / 3_600_000.0
        val jd = julian(year, month, day) - lon / (15.0 * 24.0)

        val shift = offsetHours - lon / 15.0
        val fajr = angleTime(jd, lat, 18.5, 5.0 / 24.0, true) + shift
        val sunrise = angleTime(jd, lat, 0.833, 6.0 / 24.0, true) + shift
        val dhuhr = midDay(jd, 12.0 / 24.0) + shift
        val asr = asrTime(jd, lat, 13.0 / 24.0) + shift
        val sunset = angleTime(jd, lat, 0.833, 18.0 / 24.0, false) + shift
        val ishaGap = if (isRamadan(date)) 2.0 else 1.5
        return Day(fajr, sunrise, dhuhr, asr, sunset, sunset + ishaGap)
    }

    private fun isRamadan(date: Calendar): Boolean {
        return try {
            val hijri = IslamicCalendar()
            hijri.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            hijri.timeInMillis = date.timeInMillis
            hijri.get(IslamicCalendar.MONTH) == IslamicCalendar.RAMADAN
        } catch (ex: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- astronomy

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun deg(rad: Double) = Math.toDegrees(rad)
    private fun wrap(value: Double, size: Double) = value - size * floor(value / size)

    private fun julian(yearIn: Int, monthIn: Int, day: Int): Double {
        var year = yearIn
        var month = monthIn
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + b - 1524.5
    }

    /** The sun's declination in degrees, and the equation of time in hours. */
    private fun sun(jd: Double): DoubleArray {
        val d = jd - 2451545.0
        val g = wrap(357.529 + 0.98560028 * d, 360.0)
        val q = wrap(280.459 + 0.98564736 * d, 360.0)
        val l = wrap(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0
        val equation = q / 15.0 - wrap(ra, 24.0)
        val declination = deg(asin(sin(rad(e)) * sin(rad(l))))
        return doubleArrayOf(declination, equation)
    }

    private fun midDay(jd: Double, dayPart: Double): Double = wrap(12.0 - sun(jd + dayPart)[1], 24.0)

    /** When the sun is [angle] degrees below the horizon, before noon or after it. */
    private fun angleTime(jd: Double, lat: Double, angle: Double, dayPart: Double, beforeNoon: Boolean): Double {
        val declination = sun(jd + dayPart)[0]
        val noon = midDay(jd, dayPart)
        val x = (-sin(rad(angle)) - sin(rad(declination)) * sin(rad(lat))) /
            (cos(rad(declination)) * cos(rad(lat)))
        val hours = deg(acos(x.coerceIn(-1.0, 1.0))) / 15.0
        return noon + if (beforeNoon) -hours else hours
    }

    private fun asrTime(jd: Double, lat: Double, dayPart: Double): Double {
        val declination = sun(jd + dayPart)[0]
        val angle = -deg(atan(1.0 / (1.0 + tan(rad(abs(lat - declination))))))
        return angleTime(jd, lat, angle, dayPart, false)
    }
}
