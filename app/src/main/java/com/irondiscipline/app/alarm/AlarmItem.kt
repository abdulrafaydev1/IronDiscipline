package com.irondiscipline.app.alarm

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Core Alarm Data Model representing an IronDiscipline hard alarm configuration.
 */
data class AlarmItem(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean = true,
    val label: String = "Hard Discipline Alarm",
    val repeatDays: Set<Int> = emptySet(), // Calendar.MONDAY (2) .. Calendar.SUNDAY (1)
    val deleteAfterRinging: Boolean = false,
    val soundIndex: Int = 0,
    val snoozeEnabled: Boolean = false, // Forced NO
    val snoozeMinutes: Int = 0, // Forced 0
    val vibrateWhileRinging: Boolean = true, // Forced ON
    val dismissChallenge: String = "MATH_HARD", // Forced MATH_HARD
    val maxVolumeOverride: Boolean = true // Forced ON
) {
    fun getFormattedTime(is24Hour: Boolean = false): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        val pattern = if (is24Hour) "HH:mm" else "hh:mm a"
        val format = SimpleDateFormat(pattern, Locale.US)
        return format.format(cal.time).uppercase(Locale.US)
    }

    val formattedTime: String
        get() = getFormattedTime(false)

    val isRepeating: Boolean
        get() = repeatDays.isNotEmpty()

    val soundDisplayName: String
        get() = HardAlarmSoundEngine.SOUND_NAMES.getOrElse(soundIndex) { "1. Nuclear Air Raid Siren" }

    val repeatDaysFormatted: String
        get() = if (repeatDays.isEmpty()) {
            if (deleteAfterRinging) "Once (Auto-Delete)" else "One-Time Alarm"
        } else if (repeatDays.size == 7) {
            "Every Day"
        } else if (repeatDays == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)) {
            "Weekdays (Mon-Fri)"
        } else if (repeatDays == setOf(Calendar.SATURDAY, Calendar.SUNDAY)) {
            "Weekends (Sat-Sun)"
        } else {
            val dayOrder = listOf(
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
                Calendar.SUNDAY to "Sun"
            )
            dayOrder.filter { it.first in repeatDays }.joinToString(", ") { it.second }
        }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("hour", hour)
            put("minute", minute)
            put("isEnabled", isEnabled)
            put("label", label)
            put("deleteAfterRinging", deleteAfterRinging)
            put("soundIndex", soundIndex)
            put("snoozeEnabled", snoozeEnabled)
            put("snoozeMinutes", snoozeMinutes)
            put("vibrateWhileRinging", vibrateWhileRinging)
            put("dismissChallenge", dismissChallenge)
            put("maxVolumeOverride", maxVolumeOverride)

            val daysArray = JSONArray()
            repeatDays.forEach { daysArray.put(it) }
            put("repeatDays", daysArray)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AlarmItem {
            val daysSet = mutableSetOf<Int>()
            val daysArray = json.optJSONArray("repeatDays")
            if (daysArray != null) {
                for (i in 0 until daysArray.length()) {
                    daysSet.add(daysArray.getInt(i))
                }
            }

            return AlarmItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                hour = json.optInt("hour", 6),
                minute = json.optInt("minute", 0),
                isEnabled = json.optBoolean("isEnabled", true),
                label = json.optString("label", "Hard Discipline Alarm"),
                repeatDays = daysSet,
                deleteAfterRinging = json.optBoolean("deleteAfterRinging", false),
                soundIndex = json.optInt("soundIndex", 0),
                snoozeEnabled = false,
                snoozeMinutes = 0,
                vibrateWhileRinging = true,
                dismissChallenge = "MATH_HARD",
                maxVolumeOverride = true
            )
        }
    }
}
