package com.ascent.app.core

import java.time.LocalDate

const val JOURNEY_STEPS = 90

data class JourneyState(
    val position: Int,
    val streak: Int,
    val missedDays: Int,
    val consecutiveMisses: Int,
    val reached: Boolean,
)

/**
 * Pure progress rules. [days] = finalised days in chronological order, true = all 3 non-negotiables done.
 * Complete day: +1 (max one per day). Miss k-in-a-row: -3k. k resets after a complete day. Floor 0, cap [total].
 */
fun evaluateDays(days: List<Boolean>, total: Int = JOURNEY_STEPS): JourneyState {
    var pos = 0
    var streak = 0
    var missed = 0
    var k = 0
    for (done in days) {
        if (pos >= total) break
        if (done) {
            pos += 1; streak += 1; k = 0
        } else {
            k += 1; missed += 1; streak = 0
            pos = maxOf(0, pos - 3 * k)
        }
    }
    pos = pos.coerceAtMost(total)
    return JourneyState(pos, streak, missed, k, pos >= total)
}

/** Days from [start] until yesterday (missing = miss); today counts only once complete. */
fun journeyFrom(complete: Map<LocalDate, Boolean>, start: LocalDate, today: LocalDate): JourneyState {
    val list = generateSequence(start) { it.plusDays(1) }
        .takeWhile { it.isBefore(today) }
        .map { complete[it] ?: false }
        .toMutableList()
    if (!start.isAfter(today) && complete[today] == true) list += true
    return evaluateDays(list)
}
