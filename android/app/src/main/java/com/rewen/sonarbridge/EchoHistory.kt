package com.rewen.sonarbridge

/**
 * Ring buffer of sonar echo columns (758 intensity samples spanning 0–80 m,
 * ≈9.475 samples/m) shared between BridgeService (producer: real REDYFC or
 * demo synth) and SonarView (consumer).
 */
object EchoHistory {
    const val SAMPLES = 758
    const val SAMPLES_PER_M = 9.475

    class Column(val seq: Long, val samples: ByteArray, val depthM: Double)

    private val cols = ArrayDeque<Column>()
    private var nextSeq = 1L

    @Synchronized
    fun push(samples: ByteArray, depthM: Double) {
        cols.addLast(Column(nextSeq++, samples, depthM))
        while (cols.size > 1024) cols.removeFirst()
    }

    @Synchronized
    fun since(seq: Long): List<Column> = cols.filter { it.seq > seq }

    @Synchronized
    fun clear() {
        cols.clear()
    }
}
