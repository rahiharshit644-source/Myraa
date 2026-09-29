package com.soltini.app.scheduler

import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * Result of Natural Language Time Parsing.
 */
sealed class TimeParseResult {
    data class Success(
        val timestampMs: Long,
        val humanDescription: String,
        val cleanCommandWithoutTime: String,
        val timeExpression: String
    ) : TimeParseResult()

    data class Ambiguous(
        val clarificationQuestion: String,
        val contextHint: String,
        val cleanCommand: String
    ) : TimeParseResult()

    object NotScheduled : TimeParseResult()
}

/**
 * NaturalTimeParser
 *
 * Robust, bilingual (Hindi / Hinglish & English) parser for relative, absolute,
 * and conversational time expressions.
 */
object NaturalTimeParser {

    /**
     * Parses time information from [command].
     */
    fun parse(command: String): TimeParseResult {
        val raw = command.trim()
        if (raw.isBlank()) return TimeParseResult.NotScheduled

        val lower = raw.lowercase()

        // 1. Relative Minutes / Hours ("30 minute baad", "in 15 minutes", "2 ghante baad")
        parseRelativeTime(raw, lower)?.let { return it }

        // 2. Ambiguity Detection ("shaam ko call karna" without specific hour)
        checkAmbiguity(raw, lower)?.let { return it }

        // 3. Absolute Time ("shaam 7 baje", "kal subah 8 baje", "tomorrow at 6 pm", "raat 9:30 baje")
        parseAbsoluteTime(raw, lower)?.let { return it }

        return TimeParseResult.NotScheduled
    }

    private fun parseRelativeTime(raw: String, lower: String): TimeParseResult? {
        val now = Calendar.getInstance()

        // "30 minute baad", "15 minutes baad", "10 min baad", "in 30 minutes", "after 45 minutes", "aadhe ghante baad"
        val halfHourRegex = Regex("""(?i)\b(aadhe|aadha)\s+ghante?\s+(baad|me|mein)\b""")
        if (halfHourRegex.containsMatchIn(lower)) {
            val target = (now.clone() as Calendar).apply { add(Calendar.MINUTE, 30) }
            val clean = raw.replace(halfHourRegex, "").cleanCommand()
            return TimeParseResult.Success(
                timestampMs = target.timeInMillis,
                humanDescription = "30 minute baad (${formatTime(target)})",
                cleanCommandWithoutTime = clean,
                timeExpression = "aadhe ghante baad"
            )
        }

        val minRegex = Regex("""(?i)\b(\d+)\s*(minute|minutes|min|mins)\s*(baad|me|mein|later)?\b|\bin\s*(\d+)\s*(minute|minutes|min|mins)\b|\bafter\s*(\d+)\s*(minute|minutes|min|mins)\b""")
        val minMatch = minRegex.find(lower)
        if (minMatch != null) {
            val numStr = minMatch.groupValues[1].ifBlank { minMatch.groupValues[4].ifBlank { minMatch.groupValues[6] } }
            val minutes = numStr.toIntOrNull()
            if (minutes != null && minutes > 0) {
                val target = (now.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }
                val clean = raw.replace(minRegex, "").cleanCommand()
                return TimeParseResult.Success(
                    timestampMs = target.timeInMillis,
                    humanDescription = "$minutes minute baad (${formatTime(target)})",
                    cleanCommandWithoutTime = clean,
                    timeExpression = minMatch.value.trim()
                )
            }
        }

        // "2 ghante baad", "1 ghante baad", "ek ghante baad", "do ghante baad", "in 2 hours", "after 1 hour"
        val wordHourRegex = Regex("""(?i)\b(ek|do|teen|chaar|paanch)\s*ghante?\s*(baad|me|mein)\b""")
        val wordHourMatch = wordHourRegex.find(lower)
        if (wordHourMatch != null) {
            val hours = when (wordHourMatch.groupValues[1].lowercase()) {
                "ek" -> 1
                "do" -> 2
                "teen" -> 3
                "chaar" -> 4
                "paanch" -> 5
                else -> 1
            }
            val target = (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, hours) }
            val clean = raw.replace(wordHourRegex, "").cleanCommand()
            return TimeParseResult.Success(
                timestampMs = target.timeInMillis,
                humanDescription = "$hours ghante baad (${formatTime(target)})",
                cleanCommandWithoutTime = clean,
                timeExpression = wordHourMatch.value.trim()
            )
        }

        val hourRegex = Regex("""(?i)\b(\d+)\s*(ghante|ghanta|hour|hours|hr|hrs)\s*(baad|me|mein|later)?\b|\bin\s*(\d+)\s*(ghante|ghanta|hour|hours|hr|hrs)\b|\bafter\s*(\d+)\s*(hour|hours|hr|hrs)\b""")
        val hourMatch = hourRegex.find(lower)
        if (hourMatch != null) {
            val numStr = hourMatch.groupValues[1].ifBlank { hourMatch.groupValues[4].ifBlank { hourMatch.groupValues[6] } }
            val hours = numStr.toIntOrNull()
            if (hours != null && hours > 0) {
                val target = (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, hours) }
                val clean = raw.replace(hourRegex, "").cleanCommand()
                return TimeParseResult.Success(
                    timestampMs = target.timeInMillis,
                    humanDescription = "$hours ghante baad (${formatTime(target)})",
                    cleanCommandWithoutTime = clean,
                    timeExpression = hourMatch.value.trim()
                )
            }
        }

        return null
    }

    private fun checkAmbiguity(raw: String, lower: String): TimeParseResult? {
        // If user says "shaam ko call karna", "raat ko message bhejna", "kal message bhejna",
        // but NO specific number or hour is present:
        val hasSpecificNumber = Regex("""\b(\d{1,2}(:\d{2})?)\s*(baje|am|pm|o'clock)?\b|\b(\d+)\s*(min|minute|ghanta|hour)\b""").containsMatchIn(lower)
        if (hasSpecificNumber) return null

        val isCall = lower.contains("call") || lower.contains("phone")
        val isMessage = lower.contains("message") || lower.contains("msg") || lower.contains("sms")
        val isReminder = lower.contains("remind") || lower.contains("yaad")

        val actionName = when {
            isCall -> "call"
            isMessage -> "message"
            isReminder -> "remind"
            else -> "task"
        }

        if (lower.contains("shaam ko") || lower.contains("shaam mein") || lower.contains("in the evening")) {
            return TimeParseResult.Ambiguous(
                clarificationQuestion = "Boss, shaam ko kis time $actionName karna hai?",
                contextHint = "evening_unspecified",
                cleanCommand = raw
            )
        }
        if (lower.contains("subah ko") || lower.contains("subah mein") || lower.contains("in the morning")) {
            return TimeParseResult.Ambiguous(
                clarificationQuestion = "Boss, subah kis time $actionName karna hai?",
                contextHint = "morning_unspecified",
                cleanCommand = raw
            )
        }
        if (lower.contains("raat ko") || lower.contains("raat mein") || lower.contains("at night")) {
            return TimeParseResult.Ambiguous(
                clarificationQuestion = "Boss, raat ko kis samay $actionName karna hai?",
                contextHint = "night_unspecified",
                cleanCommand = raw
            )
        }
        if (lower.contains("dopahar ko") || lower.contains("dopahar mein") || lower.contains("in the afternoon")) {
            return TimeParseResult.Ambiguous(
                clarificationQuestion = "Boss, dopahar ko kis time $actionName karna hai?",
                contextHint = "afternoon_unspecified",
                cleanCommand = raw
            )
        }

        return null
    }

    private fun parseAbsoluteTime(raw: String, lower: String): TimeParseResult? {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var isTomorrow = false
        var isDayAfter = false
        var dayOfWeekOffset = 0

        // Day detection
        if (lower.contains("parso") || lower.contains("day after tomorrow")) {
            isDayAfter = true
            target.add(Calendar.DAY_OF_YEAR, 2)
        } else if (lower.contains("kal") || lower.contains("tomorrow")) {
            isTomorrow = true
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Specific weekday ("somwar ko", "monday ko")
        val weekdays = mapOf(
            "monday" to Calendar.MONDAY, "somwar" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY, "mangalwar" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "budhwar" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "guruwar" to Calendar.THURSDAY, "veervar" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "shukrawar" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY, "shaniwar" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY, "ravivar" to Calendar.SUNDAY, "itwar" to Calendar.SUNDAY
        )
        for ((name, calDay) in weekdays) {
            if (lower.contains(name)) {
                var daysUntil = (calDay - target.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (daysUntil == 0) daysUntil = 7
                target.add(Calendar.DAY_OF_YEAR, daysUntil)
                break
            }
        }

        // Time regex matching patterns:
        // "shaam 7 baje", "shaam ko 7:30 baje", "7 pm", "7:30 pm", "subah 8 baje", "8 am", "raat 9 baje"
        val timePattern = Regex("""(?i)(subah|morning|dopahar|afternoon|shaam|evening|raat|night)?\s*(ko)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje|o'clock)?""")
        val matches = timePattern.findAll(lower)

        for (m in matches) {
            val period = m.groupValues[1].lowercase()
            val hourStr = m.groupValues[3]
            val minStr = m.groupValues[4]
            val amPmOrBaje = m.groupValues[5].lowercase()

            val rawHour = hourStr.toIntOrNull() ?: continue
            val rawMinute = if (minStr.isNotBlank()) minStr.toIntOrNull() ?: 0 else 0

            // Determine 24-hour format
            var hour24 = rawHour
            val isPm = amPmOrBaje == "pm" || period in listOf("shaam", "evening", "raat", "night", "dopahar", "afternoon")
            val isAm = amPmOrBaje == "am" || period in listOf("subah", "morning")

            if (isPm) {
                if (hour24 in 1..11) {
                    // E.g. "dopahar 12 baje" is 12, "dopahar 1 baje" is 13
                    if (period in listOf("dopahar", "afternoon") && hour24 == 12) {
                        hour24 = 12
                    } else {
                        hour24 += 12
                    }
                }
            } else if (isAm) {
                if (hour24 == 12) hour24 = 0
            } else {
                // Default heuristic if period not specified but "baje" used
                // If 7, 8, 9, 10, 11 and lower contains evening/night words
                if (lower.contains("shaam") || lower.contains("raat") || lower.contains("pm")) {
                    if (hour24 in 1..11) hour24 += 12
                }
            }

            target.set(Calendar.HOUR_OF_DAY, hour24)
            target.set(Calendar.MINUTE, rawMinute)

            // If time is already past today and neither "kal" nor another day was specified, advance to tomorrow
            if (!isTomorrow && !isDayAfter && target.before(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val timeExpr = m.value.trim()
            val clean = raw.replace(Regex("(?i)\\b(kal|parso|tomorrow|aaj|today)\\b"), "")
                .replace(Regex("(?i)" + Pattern.quote(timeExpr)), "")
                .cleanCommand()

            val dayDesc = if (target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) + 1) "Kal " else if (target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) "Aaj " else ""
            val humanDesc = "$dayDesc${formatTime(target)}"

            return TimeParseResult.Success(
                timestampMs = target.timeInMillis,
                humanDescription = humanDesc,
                cleanCommandWithoutTime = clean,
                timeExpression = timeExpr
            )
        }

        return null
    }

    private fun formatTime(cal: Calendar): String {
        val sdf = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())
        return sdf.format(cal.time)
    }

    private fun String.cleanCommand(): String {
        return this
            .replace(Regex("(?i)^(myra|soltini|hey myra),?\\s*"), "")
            .replace(Regex("(?i)^(please|kripya)\\s*"), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
