package com.irondiscipline.app.alarm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

data class MathProblem(val text: String, val answer: Int)

/**
 * Singleton state holder preserving active alarm Math Hard questions and solved count
 * across screen OFF/ON cycles, device orientation, and activity recreations.
 */
object ActiveAlarmSessionState {

    const val TOTAL_MATH_QUESTIONS = 5

    @Volatile
    var activeAlarmId: String? = null
        private set

    var solvedCount by mutableIntStateOf(0)
    var currentProblem by mutableStateOf<MathProblem?>(null)
    var problemsList: List<MathProblem> = emptyList()

    @Synchronized
    fun initSession(alarmId: String?) {
        if (activeAlarmId == alarmId && problemsList.isNotEmpty() && currentProblem != null) {
            // Existing session active, preserve solved questions and current equation
            return
        }

        activeAlarmId = alarmId
        solvedCount = 0
        problemsList = List(TOTAL_MATH_QUESTIONS) {
            generateHardMathProblem()
        }
        currentProblem = problemsList.firstOrNull()
    }

    /**
     * Increments solved count and advances to the next question.
     * Returns true when all 5 questions have been completed.
     */
    @Synchronized
    fun onQuestionSolved(): Boolean {
        val nextSolved = solvedCount + 1
        solvedCount = nextSolved
        if (nextSolved >= TOTAL_MATH_QUESTIONS) {
            return true
        }
        currentProblem = problemsList.getOrNull(nextSolved) ?: generateHardMathProblem()
        return false
    }

    @Synchronized
    fun clearSession() {
        activeAlarmId = null
        solvedCount = 0
        currentProblem = null
        problemsList = emptyList()
    }

    /**
     * Generates challenging multi-digit math equations e.g. (14 × 7) + 39.
     */
    fun generateHardMathProblem(): MathProblem {
        val a = Random.nextInt(12, 20) // 12..19
        val b = Random.nextInt(6, 15)  // 6..14
        val c = Random.nextInt(18, 65) // 18..64
        return when (Random.nextInt(0, 3)) {
            0 -> MathProblem("($a × $b) + $c", (a * b) + c)
            1 -> MathProblem("($a × $b) - $c", (a * b) - c)
            else -> MathProblem("$c + ($a × $b)", c + (a * b))
        }
    }
}
