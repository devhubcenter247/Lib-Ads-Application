package com.lib.ads.gma.ads.event

/** Pure revenue arithmetic, kept free of Android types so it is unit-testable. All figures are micros. */
internal object RevenueMath {
    /** 0.01 of the currency unit — the bucket `paid_ad_impression_value_001` reports. */
    const val BUCKET_001_MICROS = 10_000L

    data class BucketResult(val accumulatorMicros: Long, val payoutMicros: Long?)

    /**
     * Adds [addMicros] to the running 0.01 bucket. When it reaches the threshold the whole
     * accumulated amount is paid out and the bucket restarts at 0.
     */
    fun addToBucket(currentMicros: Long, addMicros: Long, thresholdMicros: Long = BUCKET_001_MICROS): BucketResult {
        val total = currentMicros + addMicros.coerceAtLeast(0L)
        return if (total >= thresholdMicros) BucketResult(0L, total) else BucketResult(total, null)
    }

    /** Legacy totals were stored as Float USD; convert once into micros. */
    fun legacyUsdToMicros(usd: Float): Long = Math.round(usd.toDouble() * 1_000_000.0)

    /** Legacy 0.01 bucket was stored as Float micros. */
    fun legacyMicrosToMicros(micros: Float): Long = Math.round(micros.toDouble())

    fun microsToUnit(micros: Long): Double = micros / 1_000_000.0
}
