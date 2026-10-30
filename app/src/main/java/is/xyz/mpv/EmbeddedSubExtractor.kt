package `is`.xyz.mpv

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object EmbeddedSubExtractor {
    private const val TAG = "EmbeddedSubExtractor"

    fun extractAndNormalize(context: Context, videoPathOrUri: String, trackId: Int = -1): File? {
        val extractor = MediaExtractor()
        try {
            if (videoPathOrUri.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(videoPathOrUri), null)
            } else {
                val f = File(videoPathOrUri)
                if (!f.exists() || !f.canRead()) return null
                extractor.setDataSource(f.absolutePath)
            }

            var selectedTrackIndex = -1
            val totalTracks = extractor.trackCount

            for (i in 0 until totalTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val lang = format.getString(MediaFormat.KEY_LANGUAGE) ?: ""

                if (mime.contains("subrip", ignoreCase = true) || mime.contains("text", ignoreCase = true)) {
                    if (trackId != -1 && i == trackId) {
                        selectedTrackIndex = i
                        break
                    }
                    if (lang.contains("fa", ignoreCase = true) || lang.contains("per", ignoreCase = true)) {
                        selectedTrackIndex = i
                        break
                    }
                    if (selectedTrackIndex == -1) {
                        selectedTrackIndex = i
                    }
                }
            }

            if (selectedTrackIndex == -1) return null
            extractor.selectTrack(selectedTrackIndex)

            val buffer = ByteBuffer.allocate(64 * 1024)
            val srtBuilder = StringBuilder()
            var counter = 1

            data class SubCue(val startUs: Long, var endUs: Long, val text: String)
            val cues = mutableListOf<SubCue>()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val timeUs = extractor.sampleTime
                val bytes = ByteArray(sampleSize)
                buffer.get(bytes)
                val text = String(bytes, Charsets.UTF_8).trim()

                if (text.isNotEmpty()) {
                    cues.add(SubCue(timeUs, timeUs + 3_000_000L, text))
                }
                extractor.advance()
            }

            if (cues.isEmpty()) return null

            for (i in 0 until cues.size) {
                val current = cues[i]
                if (i + 1 < cues.size) {
                    val nextStart = cues[i + 1].startUs
                    if (nextStart > current.startUs) {
                        val duration = nextStart - current.startUs
                        current.endUs = current.startUs + duration.coerceAtMost(5_000_000L)
                    }
                }

                srtBuilder.append(counter++).append("\n")
                srtBuilder.append(formatSrtTime(current.startUs)).append(" --> ").append(formatSrtTime(current.endUs)).append("\n")
                srtBuilder.append(current.text).append("\n\n")
            }

            val normalizedContent = SubtitleNormalizer.normalizeSrt(srtBuilder.toString())
            val outFile = File(context.cacheDir, "softsub_norm_${System.currentTimeMillis()}.srt")
            outFile.writeText(normalizedContent, Charsets.UTF_8)
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract embedded subtitles", e)
            return null
        } finally {
            extractor.release()
        }
    }

    private fun formatSrtTime(us: Long): String {
        val totalMs = us / 1000
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val hours = totalMin / 60
        return "%02d:%02d:%02d,%03d".format(hours, min, sec, ms)
    }
}
