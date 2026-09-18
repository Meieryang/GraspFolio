package io.graspfolio.app

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable container: streaming annotation JSON plus deduplicated, verified audio bytes. */
internal object SidecarBundle {
    fun isBundle(input: InputStream): Boolean = input.read() == 0x50 && input.read() == 0x4b
    fun write(output: OutputStream, identity: String, document: InkDocument, assets: AudioAssets) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("document.json"))
            val writer = zip.writer(Charsets.UTF_8)
            StreamingInk.write(writer, identity, document.strokes, document.progress, document.audio)
            writer.flush(); zip.closeEntry()
            document.audio.distinctBy { it.hash }.forEach { note ->
                zip.putNextEntry(ZipEntry("audio/${note.hash}.audio"))
                assets.writeEmbedded(note, zip)
                zip.closeEntry()
            }
        }
    }
    fun read(input: InputStream, identity: String, blobs: File, assets: AudioAssets): InkDocument {
        ZipInputStream(input).use { zip ->
            require(zip.nextEntry?.name == "document.json") { "旁文件缺少文档索引" }
            val reader = JsonReader(zip.reader(Charsets.UTF_8).buffered())
            val document = StreamingInk.read(reader, identity, blobs)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "旁文件索引尾部无效" }
            zip.closeEntry()
            require(document.audio.groupBy { it.hash }.values.all { entries -> entries.map { it.bytes }.distinct().size == 1 }) { "相同音频的长度信息冲突" }
            val expected = document.audio.distinctBy { it.hash }.associateBy { "audio/${it.hash}.audio" }
            val seen = hashSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                val note = expected[entry.name] ?: error("旁文件含有未知附件")
                require(!entry.isDirectory && seen.add(entry.name)) { "旁文件附件重复" }
                assets.readEmbedded(note, zip)
                zip.closeEntry()
            }
            require(seen == expected.keys) { "旁文件缺少音频数据" }
            return document
        }
    }
}
