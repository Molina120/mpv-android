package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

object MatroskaSubExtractor {
    private const val TAG = "MatroskaSubExtractor"

    data class SubCue(val startMs: Long, var endMs: Long, val text: String)

    fun extractAndNormalize(context: Context, videoPathOrUri: String): File? {
        var pfd: ParcelFileDescriptor? = null
        var channel: FileChannel? = null

        try {
            pfd = if (videoPathOrUri.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(videoPathOrUri), "r")
            } else {
                val f = File(videoPathOrUri)
                if (!f.exists() || !f.canRead()) return null
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: return null

            channel = FileInputStream(pfd.fileDescriptor).channel
            val fileSize = channel.size()
            val buf = ByteBuffer.allocateDirect(128 * 1024)

            // ۱. پیدا کردن تِرَک زیرنویس در بخش Tracks
            var targetTrackNum = -1L
            var isAss = false

            fun readVint(): Long {
                if (!buf.hasRemaining()) {
                    buf.clear()
                    channel.read(buf)
                    buf.flip()
                    if (!buf.hasRemaining()) return -1L
                }
                val first = buf.get().toInt() and 0xFF
                var mask = 0x80
                var len = 1
                while ((first and mask) == 0 && len <= 8) {
                    mask = mask ushr 1
                    len++
                }
                var value = (first and mask.inv()).toLong()
                for (i in 1 until len) {
                    if (!buf.hasRemaining()) {
                        buf.clear()
                        channel.read(buf)
                        buf.flip()
                    }
                    value = (value shl 8) or (buf.get().toLong() and 0xFFL)
                }
                return value
            }

            fun readId(): Long {
                if (!buf.hasRemaining()) {
                    buf.clear()
                    channel.read(buf)
                    buf.flip()
                    if (!buf.hasRemaining()) return -1L
                }
                val first = buf.get().toInt() and 0xFF
                var mask = 0x80
                var len = 1
                while ((first and mask) == 0 && len <= 4) {
                    mask = mask ushr 1
                    len++
                }
                var id = first.toLong()
                for (i in 1 until len) {
                    if (!buf.hasRemaining()) {
                        buf.clear()
                        channel.read(buf)
                        buf.flip()
                    }
                    id = (id shl 8) or (buf.get().toLong() and 0xFFL)
                }
                return id
            }

            fun skipBytes(bytes: Long) {
                if (bytes <= 0) return
                if (bytes <= buf.remaining()) {
                    buf.position((buf.position() + bytes).toInt())
                } else {
                    val remainingInBuf = buf.remaining()
                    val toSeek = bytes - remainingInBuf
                    channel.position(channel.position() + toSeek)
                    buf.clear().flip()
                }
            }

            fun readString(length: Int): String {
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    if (!buf.hasRemaining()) {
                        buf.clear()
                        channel.read(buf)
                        buf.flip()
                    }
                    val count = Math.min(length - read, buf.remaining())
                    buf.get(bytes, read, count)
                    read += count
                }
                return String(bytes, Charsets.UTF_8)
            }

            // خواندن اولیه فایل و یافتن Tracks
            buf.clear().flip()
            var currentPos = 0L

            while (currentPos < fileSize) {
                val id = readId()
                val size = readVint()
                if (id == -1L || size == -1L) break

                if (id == 0x1654AE6BL) { // Tracks
                    val tracksEnd = channel.position() - buf.remaining() + size
                    while ((channel.position() - buf.remaining()) < tracksEnd) {
                        val tId = readId()
                        val tSize = readVint()
                        if (tId == 0xAEL) { // TrackEntry
                            val entryEnd = channel.position() - buf.remaining() + tSize
                            var tNum = -1L
                            var tType = -1L
                            var codec = ""
                            var lang = ""

                            while ((channel.position() - buf.remaining()) < entryEnd) {
                                val eId = readId()
                                val eSize = readVint()
                                when (eId) {
                                    0xD7L -> tNum = readVint() // TrackNumber
                                    0x83L -> tType = readVint() // TrackType (17 = Subtitle)
                                    0x86L -> codec = readString(eSize.toInt()) // CodecID
                                    0x22B59CL -> lang = readString(eSize.toInt()) // Language
                                    else -> skipBytes(eSize)
                                }
                            }

                            if (tType == 17L || tType == 0x11L) {
                                if (targetTrackNum == -1L || lang.contains("per") || lang.contains("fa")) {
                                    targetTrackNum = tNum
                                    isAss = codec.contains("ASS", ignoreCase = true)
                                }
                            }
                        } else {
                            skipBytes(tSize)
                        }
                    }
                    break
                } else if (id == 0x1F43B675L) { // وارد شدن زودهنگام به Cluster
                    break
                } else {
                    skipBytes(size)
                }
                currentPos = channel.position() - buf.remaining()
            }

            if (targetTrackNum == -1L) return null

            // ۲. بازخوانی فایل و استخراج بلاک‌های زیرنویس از درون Clusterها
            channel.position(0)
            buf.clear().flip()
            val cues = mutableListOf<SubCue>()
            var clusterTimecode = 0L

            while ((channel.position() - buf.remaining()) < fileSize) {
                val id = readId()
                val size = readVint()
                if (id == -1L || size == -1L) break

                if (id == 0x1F43B675L) { // Cluster
                    val clusterEnd = channel.position() - buf.remaining() + size
                    while ((channel.position() - buf.remaining()) < clusterEnd) {
                        val cId = readId()
                        val cSize = readVint()
                        when (cId) {
                            0xE7L -> clusterTimecode = readVint() // Timecode
                            0xA3L, 0xA1L -> { // SimpleBlock یا Block
                                val blockStart = channel.position() - buf.remaining()
                                val blockTrack = readVint()
                                if (blockTrack == targetTrackNum) {
                                    val relTime = if (buf.remaining() >= 2) buf.short.toLong() else 0L
                                    val flags = if (buf.hasRemaining()) buf.get() else 0.toByte()
                                    val readHeaderSize = (channel.position() - buf.remaining()) - blockStart
                                    val payloadSize = (cSize - readHeaderSize).toInt()
                                    if (payloadSize > 0) {
                                        var text = readString(payloadSize).trim()
                                        if (isAss && text.contains(",")) {
                                            // حذف فیلدهای اختصاصی ASS و استخراج متن خالص
                                            val parts = text.split(",", limit = 9)
                                            if (parts.size >= 9) text = parts[8].replace("\\N", "\n").replace("\\n", "\n")
                                        }
                                        val startMs = clusterTimecode + relTime
                                        cues.add(SubCue(startMs, startMs + 3500L, text))
                                    }
                                } else {
                                    val readHeaderSize = (channel.position() - buf.remaining()) - blockStart
                                    skipBytes(cSize - readHeaderSize)
                                }
                            }
                            else -> skipBytes(cSize)
                        }
                    }
                } else {
                    skipBytes(size)
                }
            }

            if (cues.isEmpty()) return null

            // ۳. مرتب‌سازی و تولید فرمت استاندارد SRT
            cues.sortBy { it.startMs }
            for (i in 0 until cues.size) {
                val cur = cues[i]
                if (i + 1 < cues.size && cues[i + 1].startMs > cur.startMs) {
                    val gap = cues[i + 1].startMs - cur.startMs
                    cur.endMs = cur.startMs + gap.coerceAtMost(5000L)
                }
            }

            val srtBuilder = StringBuilder()
            var counter = 1
            for (cue in cues) {
                srtBuilder.append(counter++).append("\n")
                srtBuilder.append(formatTime(cue.startMs)).append(" --> ").append(formatTime(cue.endMs)).append("\n")
                srtBuilder.append(cue.text).append("\n\n")
            }

            // ۴. نرمالایز کردن کامل متن و ذخیره فایل نهایی
            val fixedText = SubtitleNormalizer.normalizeSrt(srtBuilder.toString())
            val cacheFile = File(context.cacheDir, "softsub_norm_${System.currentTimeMillis()}.srt")
            cacheFile.writeText(fixedText, Charsets.UTF_8)
            return cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Matroska extraction failed", e)
            return null
        } finally {
            channel?.close()
            pfd?.close()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
