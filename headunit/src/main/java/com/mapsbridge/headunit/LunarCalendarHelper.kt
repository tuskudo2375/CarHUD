package com.mapsbridge.headunit

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.sin

/** Vietnamese lunisolar conversion using the Ho Ngoc Duc astronomical algorithm. */
object LunarCalendarHelper {
    private const val TIME_ZONE = 7.0
    private const val SYNODIC_MONTH = 29.530588853
    private const val NEW_MOON_EPOCH = 2415021.076998695
    private const val PI = Math.PI

    fun getLunarDateString(calendar: Calendar): String {
        val solarDay = calendar.get(Calendar.DAY_OF_MONTH)
        val solarMonth = calendar.get(Calendar.MONTH) + 1
        val solarYear = calendar.get(Calendar.YEAR)
        val lunar = solarToLunar(solarDay, solarMonth, solarYear)
        return "Âm lịch: ${lunar.day}/${lunar.month}${if (lunar.isLeapMonth) " (Nhuận)" else ""}"
    }

    fun getSolarDateString(calendar: Calendar): String {
        val formatter = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi", "VN"))
        formatter.timeZone = calendar.timeZone
        return formatter.format(Date(calendar.timeInMillis))
    }

    private data class LunarDate(val day: Int, val month: Int, val year: Int, val isLeapMonth: Boolean)

    private fun solarToLunar(day: Int, month: Int, year: Int): LunarDate {
        val dayNumber = julianDayFromDate(day, month, year)
        val k = floor((dayNumber - NEW_MOON_EPOCH) / SYNODIC_MONTH).toInt()
        var monthStart = newMoonDay(k + 1)
        if (monthStart > dayNumber) monthStart = newMoonDay(k)

        var month11A = lunarMonth11(year)
        var month11B = month11A
        val lunarYear: Int
        if (month11A >= monthStart) {
            lunarYear = year
            month11A = lunarMonth11(year - 1)
        } else {
            lunarYear = year + 1
            month11B = lunarMonth11(year + 1)
        }

        val lunarDay = dayNumber - monthStart + 1
        val diff = floor((monthStart - month11A).toDouble() / 29.0).toInt()
        var lunarMonth = diff + 11
        var isLeapMonth = false
        if (month11B - month11A > 365) {
            val leapMonthDiff = leapMonthOffset(month11A)
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10
                isLeapMonth = diff == leapMonthDiff
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        var adjustedLunarYear = lunarYear
        if (lunarMonth >= 11 && diff < 4) adjustedLunarYear--
        return LunarDate(lunarDay, lunarMonth, adjustedLunarYear, isLeapMonth)
    }

    private fun julianDayFromDate(day: Int, month: Int, year: Int): Int {
        val a = floor((14 - month).toDouble() / 12.0).toInt()
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        var jd = day + floor((153 * m + 2) / 5.0).toInt() + 365 * y +
            floor(y / 4.0).toInt() - floor(y / 100.0).toInt() + floor(y / 400.0).toInt() - 32045
        if (jd < 2299161) {
            jd = day + floor((153 * m + 2) / 5.0).toInt() + 365 * y + floor(y / 4.0).toInt() - 32083
        }
        return jd
    }

    private fun newMoonDay(k: Int): Int = floor(newMoon(k) + 0.5 + TIME_ZONE / 24.0).toInt()

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val degreesToRadians = PI / 180.0
        var jd = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * degreesToRadians)
        val solarAnomaly = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val lunarAnomaly = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val latitudeArgument = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var correction = (0.1734 - 0.000393 * t) * sin(solarAnomaly * degreesToRadians) +
            0.0021 * sin(2.0 * degreesToRadians * solarAnomaly)
        correction -= 0.4068 * sin(lunarAnomaly * degreesToRadians) -
            0.0161 * sin(2.0 * degreesToRadians * lunarAnomaly)
        correction -= 0.0004 * sin(3.0 * degreesToRadians * lunarAnomaly)
        correction += 0.0104 * sin(2.0 * degreesToRadians * latitudeArgument) -
            0.0051 * sin(degreesToRadians * (solarAnomaly + lunarAnomaly))
        correction -= 0.0074 * sin(degreesToRadians * (solarAnomaly - lunarAnomaly)) -
            0.0004 * sin(degreesToRadians * (2.0 * latitudeArgument + solarAnomaly))
        correction -= 0.0004 * sin(degreesToRadians * (2.0 * latitudeArgument - solarAnomaly)) +
            0.0006 * sin(degreesToRadians * (2.0 * latitudeArgument + lunarAnomaly))
        correction += 0.0010 * sin(degreesToRadians * (2.0 * latitudeArgument - lunarAnomaly)) +
            0.0005 * sin(degreesToRadians * (2.0 * lunarAnomaly + solarAnomaly))
        val deltaT = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        } else {
            -0.000278 + 0.000265 * t + 0.000262 * t2
        }
        return jd + correction - deltaT
    }

    private fun sunLongitude(dayNumber: Int): Int {
        val t = (dayNumber - 0.5 - TIME_ZONE / 24.0 - 2451545.0) / 36525.0
        val t2 = t * t
        val degreesToRadians = PI / 180.0
        val meanAnomaly = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t2 * t
        val meanLongitude = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val equationOfCenter = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(degreesToRadians * meanAnomaly) +
            (0.019993 - 0.000101 * t) * sin(2.0 * degreesToRadians * meanAnomaly) +
            0.000290 * sin(3.0 * degreesToRadians * meanAnomaly)
        var longitude = (meanLongitude + equationOfCenter) * degreesToRadians
        longitude -= 2.0 * PI * floor(longitude / (2.0 * PI))
        return floor(longitude / PI * 6.0).toInt()
    }

    private fun lunarMonth11(year: Int): Int {
        val off = julianDayFromDate(31, 12, year) - NEW_MOON_EPOCH
        val k = floor(off / SYNODIC_MONTH).toInt()
        var month = newMoonDay(k)
        if (sunLongitude(month) >= 9) month = newMoonDay(k - 1)
        return month
    }

    private fun leapMonthOffset(month11: Int): Int {
        val k = floor(0.5 + (month11 - NEW_MOON_EPOCH) / SYNODIC_MONTH).toInt()
        var lastArc = 0
        var i = 1
        var arc = sunLongitude(newMoonDay(k + i))
        do {
            lastArc = arc
            i++
            arc = sunLongitude(newMoonDay(k + i))
        } while (arc != lastArc && i < 14)
        return i - 1
    }
}
