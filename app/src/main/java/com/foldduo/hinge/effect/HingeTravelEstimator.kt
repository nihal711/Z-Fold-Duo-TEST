package com.foldduo.hinge.effect

enum class HingeTravel { UNKNOWN, OPENING, CLOSING }

/** Stable gesture direction. Sensor jitter can never reverse it by itself. */
class HingeTravelEstimator(
    private val reversalDegrees: Float = 8f,
    private val confirmationMs: Long = 80L,
) {
    var motion: HingeTravel = HingeTravel.UNKNOWN
        private set

    private var anchor = Float.NaN
    private var extreme = Float.NaN
    private var candidate = HingeTravel.UNKNOWN
    private var candidateSinceMs = 0L

    fun update(angle: Float, nowMs: Long): HingeTravel {
        if (!angle.isFinite()) return motion
        if (!anchor.isFinite()) {
            anchor = angle
            extreme = angle
            motion = when {
                angle <= CLOSED_ENDPOINT -> HingeTravel.CLOSING
                angle >= OPEN_ENDPOINT -> HingeTravel.OPENING
                else -> HingeTravel.UNKNOWN
            }
            return motion
        }

        when (motion) {
            HingeTravel.UNKNOWN -> {
                when {
                    angle >= anchor + reversalDegrees -> consider(HingeTravel.OPENING, angle, nowMs)
                    angle <= anchor - reversalDegrees -> consider(HingeTravel.CLOSING, angle, nowMs)
                    else -> clearCandidate()
                }
            }

            HingeTravel.OPENING -> {
                if (angle > extreme) {
                    extreme = angle
                    clearCandidate()
                } else if (angle <= extreme - reversalDegrees) {
                    consider(HingeTravel.CLOSING, angle, nowMs)
                } else {
                    clearCandidate()
                }
            }

            HingeTravel.CLOSING -> {
                if (angle < extreme) {
                    extreme = angle
                    clearCandidate()
                } else if (angle >= extreme + reversalDegrees) {
                    consider(HingeTravel.OPENING, angle, nowMs)
                } else {
                    clearCandidate()
                }
            }
        }
        return motion
    }

    fun force(next: HingeTravel, angle: Float) {
        motion = next
        if (angle.isFinite()) {
            anchor = angle
            extreme = angle
        }
        clearCandidate()
    }

    private fun consider(next: HingeTravel, angle: Float, nowMs: Long) {
        if (candidate != next) {
            candidate = next
            candidateSinceMs = nowMs
            return
        }
        if (nowMs - candidateSinceMs < confirmationMs) return
        motion = next
        anchor = angle
        extreme = angle
        clearCandidate()
    }

    private fun clearCandidate() {
        candidate = HingeTravel.UNKNOWN
        candidateSinceMs = 0L
    }

    private companion object {
        const val CLOSED_ENDPOINT = 3f
        const val OPEN_ENDPOINT = 168f
    }
}
