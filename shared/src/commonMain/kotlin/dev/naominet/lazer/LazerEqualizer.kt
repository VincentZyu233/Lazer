package dev.naominet.lazer

/**
 * The shape of one equalizer band. The names mirror the native engine's band kinds so the same
 * preset can drive the platform DSP and the in-process software EQ without a second mapping table.
 */
enum class LazerEqBandKind {
    Peak,
    LowShelf,
    HighShelf,
    LowPass,
    HighPass,
    Notch,
    AllPass,
}

/**
 * One band of the listener's equalizer. [frequencyHz] is the centre (or corner) frequency, [gainDb]
 * is the boost or cut, and [q] is the bandwidth. A band the listener turned off stays in the list
 * so its slider keeps its place.
 */
data class LazerEqBand(
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val kind: LazerEqBandKind = LazerEqBandKind.Peak,
    val enabled: Boolean = true,
)

/** The whole equalizer: whether it is on, its preamp, its limiter, and its bands. */
data class LazerEqualizerState(
    val enabled: Boolean = false,
    val preampDb: Double = 0.0,
    val limiterEnabled: Boolean = true,
    val bands: List<LazerEqBand> = defaultLazerEqBands(),
)

/** The ten ISO octave centres, which is the band layout every preset below is written against. */
val LazerEqBandFrequencies: List<Double> =
    listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)

/** The lowest and highest gain a slider offers, in decibels. */
const val MIN_LAZER_EQ_GAIN_DB: Double = -12.0
const val MAX_LAZER_EQ_GAIN_DB: Double = 12.0
const val MIN_LAZER_EQ_PREAMP_DB: Double = -12.0
const val MAX_LAZER_EQ_PREAMP_DB: Double = 12.0

/** Edge bands use shelves and the rest use peaks, which is the layout a listener expects. */
fun defaultLazerEqBands(): List<LazerEqBand> = LazerEqBandFrequencies.mapIndexed { index, frequency ->
    LazerEqBand(
        frequencyHz = frequency,
        kind = when (index) {
            0 -> LazerEqBandKind.LowShelf
            LazerEqBandFrequencies.lastIndex -> LazerEqBandKind.HighShelf
            else -> LazerEqBandKind.Peak
        },
    )
}

/** A named set of gains, indexed against [LazerEqBandFrequencies]. */
data class LazerEqualizerPreset(
    val id: String,
    val gainsDb: List<Double>,
    val preampDb: Double = 0.0,
)

val LazerEqualizerPresets: List<LazerEqualizerPreset> = listOf(
    LazerEqualizerPreset("flat", List(LazerEqBandFrequencies.size) { 0.0 }),
    LazerEqualizerPreset(
        "pop",
        listOf(-1.0, -0.5, 0.0, 2.0, 3.5, 3.0, 1.0, -0.5, -1.0, -1.5),
        preampDb = -1.0,
    ),
    LazerEqualizerPreset(
        "rock",
        listOf(4.0, 3.0, 1.0, -1.0, -1.5, 0.5, 2.5, 3.5, 3.0, 2.5),
        preampDb = -2.0,
    ),
    LazerEqualizerPreset(
        "classical",
        listOf(3.0, 2.5, 1.5, 0.5, -0.5, -0.5, 0.0, 1.5, 2.5, 3.0),
        preampDb = -1.5,
    ),
    LazerEqualizerPreset(
        "jazz",
        listOf(3.0, 2.0, 0.5, 1.0, -0.5, -0.5, 0.5, 1.5, 2.0, 2.5),
        preampDb = -1.5,
    ),
    LazerEqualizerPreset(
        "bass",
        listOf(7.0, 6.0, 4.5, 2.5, 0.5, -0.5, -1.0, -1.5, -2.0, -2.5),
        preampDb = -3.0,
    ),
    LazerEqualizerPreset(
        "vocal",
        listOf(-2.0, -1.5, -0.5, 1.0, 3.0, 3.5, 2.5, 1.0, -0.5, -1.5),
        preampDb = -2.0,
    ),
    LazerEqualizerPreset(
        "treble",
        listOf(-2.5, -2.0, -1.5, -1.0, -0.5, 0.5, 2.0, 3.5, 5.0, 6.0),
        preampDb = -2.5,
    ),
)

/** The preset whose gains match [states], or null when the bands are a custom curve. */
fun LazerEqualizerState.matchingPresetId(): String? {
    val tolerance = 0.05
    return LazerEqualizerPresets.firstOrNull { preset ->
        preset.gainsDb.size == bands.size &&
            preset.gainsDb.zip(bands).all { (gain, band) -> kotlin.math.abs(gain - band.gainDb) < tolerance }
    }?.id
}

/** Replaces the gains with a preset, keeping the listener's band shapes and enabled flags. */
fun LazerEqualizerState.withPreset(preset: LazerEqualizerPreset): LazerEqualizerState {
    if (preset.gainsDb.size != bands.size) return this
    return copy(
        preampDb = preset.preampDb,
        bands = bands.mapIndexed { index, band -> band.copy(gainDb = preset.gainsDb[index]) },
    )
}

fun clampLazerEqGainDb(value: Double): Double = value.coerceIn(MIN_LAZER_EQ_GAIN_DB, MAX_LAZER_EQ_GAIN_DB)

fun clampLazerEqPreampDb(value: Double): Double = value.coerceIn(MIN_LAZER_EQ_PREAMP_DB, MAX_LAZER_EQ_PREAMP_DB)

/**
 * A compact, forward-compatible encoding for one preference string. It is deliberately hand-rolled
 * rather than JSON: the value is read on every platform and a malformed string must fall back to a
 * flat curve instead of throwing.
 */
fun LazerEqualizerState.serialize(): String = buildString {
    append(if (enabled) '1' else '0')
    append('|')
    append(preampDb)
    append('|')
    append(if (limiterEnabled) '1' else '0')
    append('|')
    bands.joinTo(this, ";") { band ->
        "${band.frequencyHz}:${band.gainDb}:${band.q}:${band.kind.name}:${if (band.enabled) 1 else 0}"
    }
}

fun parseLazerEqualizer(value: String?): LazerEqualizerState {
    if (value.isNullOrBlank()) return LazerEqualizerState()
    val parts = value.split('|')
    if (parts.size < 4) return LazerEqualizerState()
    val enabled = parts[0] == "1"
    val preampDb = clampLazerEqPreampDb(parts[1].toDoubleOrNull() ?: 0.0)
    val limiterEnabled = parts[2] != "0"
    val bands = parts[3].split(';').mapNotNull { entry ->
        val fields = entry.split(':')
        if (fields.size < 5) return@mapNotNull null
        val frequency = fields[0].toDoubleOrNull() ?: return@mapNotNull null
        val gain = clampLazerEqGainDb(fields[1].toDoubleOrNull() ?: 0.0)
        val q = (fields[2].toDoubleOrNull() ?: 1.0).coerceIn(0.1, 10.0)
        val kind = LazerEqBandKind.entries.firstOrNull { it.name == fields[3] } ?: LazerEqBandKind.Peak
        LazerEqBand(frequency, gain, q, kind, fields[4] == "1")
    }
    if (bands.isEmpty()) return LazerEqualizerState()
    return LazerEqualizerState(enabled, preampDb, limiterEnabled, bands)
}
