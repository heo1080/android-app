package com.byd.dolphin.autoassistant.next.vehicle

object SignalResolver {
    data class Resolved<T>(val value: T?, val confidence: Confidence)

    fun gear(raw: Int?): Resolved<String> = when (raw) {
        0 -> Resolved("N", Confidence.VERIFIED)
        1 -> Resolved("R", Confidence.VERIFIED)
        2 -> Resolved("D", Confidence.VERIFIED)
        3 -> Resolved("P", Confidence.VERIFIED)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    fun regen(raw: Int?): Resolved<String> = when (raw) {
        1 -> Resolved("STANDARD", Confidence.BETA)
        2 -> Resolved("HIGH", Confidence.BETA)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    fun snow(raw: Int?): Resolved<Boolean> = when (raw) {
        2 -> Resolved(true, Confidence.BETA)
        0, 1 -> Resolved(false, Confidence.BETA)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    /**
     * Two independent raw sources are kept. ECO/SPORT transitions have been
     * observed on the target car; NORMAL remains BETA until the new Next logs
     * correlate both sources on the Korean Dolphin.
     */
    fun driveMode(instrumentRaw: Int?, operationRaw: Int?): Resolved<String> {
        val instrument = when (instrumentRaw) {
            1 -> "ECO"
            2 -> "NORMAL"
            3 -> "SPORT"
            else -> null
        }
        val operation = when (operationRaw) {
            1 -> "ECO"
            2 -> "SPORT"
            0, 3 -> "NORMAL"
            else -> null
        }
        return when {
            instrument != null && operation != null && instrument == operation ->
                Resolved(instrument, if (instrument == "NORMAL") Confidence.BETA else Confidence.VERIFIED)
            operation == "ECO" || operation == "SPORT" ->
                Resolved(operation, Confidence.VERIFIED)
            instrument == "ECO" || instrument == "SPORT" ->
                Resolved(instrument, Confidence.VERIFIED)
            instrument == "NORMAL" || operation == "NORMAL" ->
                Resolved("NORMAL", Confidence.BETA)
            else -> Resolved(null, Confidence.UNKNOWN)
        }
    }

    fun icc(raw: Int?): Resolved<Boolean> = when (raw) {
        0, 1 -> Resolved(false, Confidence.BETA)
        2, 3 -> Resolved(true, Confidence.BETA)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    fun turn(raw: Int?): Resolved<String> = when (raw) {
        1 -> Resolved("OFF", Confidence.BETA)
        2, 3 -> Resolved("LEFT", Confidence.BETA)
        4, 5 -> Resolved("RIGHT", Confidence.BETA)
        6, 7, 8 -> Resolved("HAZARD", Confidence.BETA)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    fun seatHeat(raw: Int?): Resolved<Int> = when (raw) {
        1 -> Resolved(0, Confidence.VERIFIED)
        2 -> Resolved(1, Confidence.VERIFIED)
        3 -> Resolved(2, Confidence.VERIFIED)
        else -> Resolved(null, Confidence.UNKNOWN)
    }

    fun steeringHeat(raw: Int?): Resolved<Boolean> = when (raw) {
        1 -> Resolved(false, Confidence.VERIFIED)
        2 -> Resolved(true, Confidence.VERIFIED)
        else -> Resolved(null, Confidence.UNKNOWN)
    }
}
