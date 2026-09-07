package io.graspfolio.app

import android.view.MotionEvent

/** Public MotionEvent APIs only. SDK always sees pointer index/id 0, including when palm lands first. */
internal class PenEventNormalizer {
    private val properties = arrayOf(MotionEvent.PointerProperties())
    private val coordinates = arrayOf(MotionEvent.PointerCoords())
    fun obtain(source: MotionEvent, index: Int, penDownTime: Long, action: Int): MotionEvent {
        source.getPointerProperties(index, properties[0]); properties[0].id = 0
        val history = if (action == MotionEvent.ACTION_MOVE) source.historySize else 0
        if (history > 0) source.getHistoricalPointerCoords(index, 0, coordinates[0]) else source.getPointerCoords(index, coordinates[0])
        val result = MotionEvent.obtain(penDownTime, if (history > 0) source.getHistoricalEventTime(0) else source.eventTime,
            action, 1, properties, coordinates, source.metaState, source.buttonState, source.xPrecision, source.yPrecision,
            source.deviceId, source.edgeFlags, source.source, source.flags)
        if (history > 0) {
            for (h in 1 until history) {
                source.getHistoricalPointerCoords(index, h, coordinates[0])
                result.addBatch(source.getHistoricalEventTime(h), coordinates, source.metaState)
            }
            source.getPointerCoords(index, coordinates[0]); result.addBatch(source.eventTime, coordinates, source.metaState)
        }
        return result
    }
}
