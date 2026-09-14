package io.graspfolio.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel

/** One reader lease survives Activity recreation; leaving a document releases it immediately. */
internal class ReaderSessions : ViewModel() {
    private val stores = mutableMapOf<String, AnnotationStore>()
    fun open(context: Context, uri: Uri): AnnotationStore = stores.getOrPut(uri.toString()) {
        AnnotationStore.obtain(context.applicationContext, uri)
    }
    fun close(uri: Uri) { stores.remove(uri.toString())?.release() }
    override fun onCleared() { stores.values.forEach { it.release() }; stores.clear() }
}
