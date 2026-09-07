package io.graspfolio.app

import android.os.Trace
import android.util.Log

/** Counters/timings only: never log document names, URIs, or handwriting samples. */
internal object InkPerformance {
    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        Trace.beginSection("GraspFolio.$stage")
        try { return block() } finally {
            Trace.endSection()
            val ms = (System.nanoTime() - start) / 1_000_000.0
            if (ms >= 4) Log.i("GraspFolioPerf", "$stage ms=" + String.format(java.util.Locale.ROOT, "%.2f", ms))
        }
    }
}
