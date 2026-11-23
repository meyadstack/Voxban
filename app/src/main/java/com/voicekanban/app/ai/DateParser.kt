package com.voicekanban.app.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Understands everyday deadline phrases: "today", "tomorrow at 5pm",
 * "next friday", "in 3 days", "in 2 hours", "dec 5", "5/12", ISO dates
 * ("2026-11-25", "2026-11-25T17:00"). Date-only phrases default to 5 pm.
 */
object DateParser {
    private val DEFAULT_TIME: LocalTime = LocalTime.of(17, 0)

    /** Matches a deadline clause at the end of a command, e.g. "… due friday at 3pm". */
    val TRAILING_DUE = Regex(
        """\s*,?\s+(?:due|by|before|deadline|on)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        val s = input.trim().lowercase(Locale.getDefault()).removeSuffix(".")
        if (s.isEmpty() || s in setOf("none", "no deadline", "null")) return null

        // ISO forms from models.
        runCatching { return ZonedDateTime.parse(input.trim()).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(input.trim()).atZone(now.zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(input.trim()).atTime(DEFAULT_TIME).atZone(now.zone).toInstant().toEpochMilli() }

        // Relative durations: "in 3 days", "in 2 hours", "in 30 minutes", "in a week".
        Regex("""^in\s+(a|an|\d+)\s+(minute|min|hour|hr|day|week|month)s?$""").find(s)?.let { m ->
            val n = m.groupValues[1].let { if (it == "a" || it == "an") 1L else it.toLong() }
            val t = when (m.groupValues[2]) {
                "minute", "min" -> now.plusMinutes(n)
                "hour", "hr" -> now.plusHours(n)
                "day" -> now.plusDays(n).with(DEFAULT_TIME)
                "week" -> now.plusWeeks(n).with(DEFAULT_TIME)
                else -> now.plusMonths(n).with(DEFAULT_TIME)
            }
            return t.toInstant().toEpochMilli()
        }

        // Split "<date part> at <time part>".
        val timeRe = Regex("""(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$|(noon|midnight|morning|evening|tonight)$""")
        var datePart = s
        var time: LocalTime? = null
        timeRe.find(s)?.let { m ->
            val word = m.groupValues[4]
            val parsed: LocalTime? = when {
                word == "noon" -> LocalTime.NOON
                word == "midnight" -> LocalTime.of(23, 59)
                word == "morning" -> LocalTime.of(9, 0)
                word == "evening" || word == "tonight" -> LocalTime.of(20, 0)
                m.groupValues[1].isNotEmpty() && (m.groupValues[3].isNotEmpty() || m.groupValues[2].isNotEmpty() || s.contains(" at ")) -> {
                    var h = m.groupValues[1].toInt()
                    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                    when (m.groupValues[3]) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
                    if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
                }
                else -> null
            }
            if (parsed != null) {
                time = parsed
                datePart = s.substring(0, m.range.first).trim().removeSuffix(" at").trim()
            }
        }

        val today = now.toLocalDate()
        val date: LocalDate? = when {
            datePart.isEmpty() || datePart == "today" || datePart == "tonight" -> today
            datePart == "tomorrow" || datePart == "tmrw" -> today.plusDays(1)
            datePart == "day after tomorrow" -> today.plusDays(2)
            datePart == "this weekend" || datePart == "weekend" -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            datePart == "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            datePart == "end of week" || datePart == "eow" -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
            datePart == "end of month" || datePart == "eom" -> today.with(TemporalAdjusters.lastDayOfMonth())
            else -> weekday(datePart, today) ?: monthDay(datePart, today) ?: numeric(datePart, today)
        }
        if (date == null) return null

        var result = date.atTime(time ?: DEFAULT_TIME).atZone(now.zone)
        // "at 9am" with no date that has already passed today means tomorrow.
        if (datePart.isEmpty() && time != null && result.isBefore(now)) result = result.plusDays(1)
        return result.toInstant().toEpochMilli()
    }

    private fun weekday(s: String, today: LocalDate): LocalDate? {
        val next = s.startsWith("next ")
        val name = s.removePrefix("next ").removePrefix("this ").removePrefix("on ").trim()
        val dow = DayOfWeek.entries.firstOrNull {
            val full = it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()
            name == full || name == full.take(3) || (name.length >= 3 && full.startsWith(name))
        } ?: return null
        val d = today.with(TemporalAdjusters.nextOrSame(dow))
        // "next friday" on a Friday means a week from today; otherwise the coming one.
        return if (next && d == today) d.plusWeeks(1) else d
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun monthDay(s: String, today: LocalDate): LocalDate? {
        // "dec 5", "december 5th", "5 dec", "5th of december"
        val m1 = Regex("""^([a-z]+)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""").find(s)
        val m2 = Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?([a-z]+)(?:,?\s+(\d{4}))?$""").find(s)
        val (monthName, day, year) = when {
            m1 != null -> Triple(m1.groupValues[1], m1.groupValues[2].toInt(), m1.groupValues[3])
            m2 != null -> Triple(m2.groupValues[2], m2.groupValues[1].toInt(), m2.groupValues[3])
            else -> return null
        }
        val month = months.indexOfFirst { monthName.startsWith(it) } + 1
        if (month == 0) return null
        return runCatching {
            var d = LocalDate.of(year.ifEmpty { today.year.toString() }.toInt(), month, day)
            if (year.isEmpty() && d.isBefore(today)) d = d.plusYears(1)
            d
        }.getOrNull()
    }

    private fun numeric(s: String, today: LocalDate): LocalDate? {
        // "5/12" or "5/12/2026" (day/month as on most Android locales outside the US is ambiguous;
        // we follow the device locale's order via a short-date pattern).
        val m = Regex("""^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?$""").find(s) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        val usOrder = Locale.getDefault().country in setOf("US", "PH", "CA")
        val (month, day) = if (usOrder) a to b else b to a
        val yr = m.groupValues[3].let { if (it.isEmpty()) today.year else if (it.length == 2) 2000 + it.toInt() else it.toInt() }
        return runCatching {
            var d = LocalDate.of(yr, month, day)
            if (m.groupValues[3].isEmpty() && d.isBefore(today)) d = d.plusYears(1)
            d
        }.getOrNull()
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** Short human label: "Today 5:00 PM", "Tomorrow 9:00 AM", "Fri 28 Nov 5:00 PM". */
    fun label(millis: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        val t = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val day = when (t.toLocalDate()) {
            now.toLocalDate() -> "Today"
            now.toLocalDate().plusDays(1) -> "Tomorrow"
            now.toLocalDate().minusDays(1) -> "Yesterday"
            else -> t.format(dayFmt)
        }
        return "$day ${t.format(timeFmt)}"
    }

    /** "in 3h", "2d overdue", … */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = millis - now
        val abs = kotlin.math.abs(diff)
        val unit = when {
            abs < 3_600_000 -> "${(abs / 60_000).coerceAtLeast(1)}m"
            abs < 86_400_000 -> "${abs / 3_600_000}h"
            else -> "${abs / 86_400_000}d"
        }
        return if (diff >= 0) "in $unit" else "$unit overdue"
    }

    /** ISO local date-time for model prompts. */
    fun iso(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString()
}
