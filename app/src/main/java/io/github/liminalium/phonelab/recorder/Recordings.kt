package io.github.liminalium.phonelab.recorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import io.github.liminalium.phonelab.syscheck.safe
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Recordings live in Movies/PhoneLab, so they show up in the gallery. */
object Recordings {
    private const val FOLDER = "PhoneLab"
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    class Pending(val uri: Uri, val fd: ParcelFileDescriptor, val name: String)

    data class Item(val uri: Uri, val name: String, val sizeBytes: Long, val durationMs: Long)

    /** Creates a hidden entry that becomes visible once [finish] marks it complete. */
    fun create(ctx: Context): Pending {
        val name = "PhoneLab-${LocalDateTime.now().format(nameFormat)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("the Movies folder refused a new file")
        val fd = resolver.openFileDescriptor(uri, "rw") ?: run {
            resolver.delete(uri, null, null)
            error("could not open the new video file")
        }
        return Pending(uri, fd, name)
    }

    /** Publishes the file, or deletes it when the recording produced nothing usable. */
    fun finish(ctx: Context, pending: Pending, keep: Boolean): Uri? {
        safe { pending.fd.close() }
        val resolver = ctx.contentResolver
        return if (keep) {
            resolver.update(pending.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            pending.uri
        } else {
            safe { resolver.delete(pending.uri, null, null) }
            null
        }
    }

    /** This app's recordings, newest first. */
    fun list(ctx: Context): List<Item> = safe {
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
        )
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            columns,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Item(
                            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                            name = c.getString(1),
                            sizeBytes = c.getLong(2),
                            durationMs = c.getLong(3),
                        ),
                    )
                }
            }
        }
    }.orEmpty()
}

/** The video size and bitrate to encode the screen at. */
data class VideoSize(val width: Int, val height: Int, val dpi: Int, val bitrate: Int)

object VideoSizes {
    /**
     * The largest size the H.264 encoder accepts, at the screen's shape. Phone
     * screens such as 1080x2400 are often bigger than the encoder allows, so it
     * steps down until the encoder agrees. Encoders list their limits for
     * landscape (say 1920x1088) and record portrait the same way, so a size
     * counts as supported in either orientation.
     */
    fun pick(ctx: Context): VideoSize {
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && "video/avc" in it.supportedTypes }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstNotNullOfOrNull { info -> safe { info.getCapabilitiesForType("video/avc").videoCapabilities } }
        for (scale in listOf(1.0, 0.8, 0.75, 2.0 / 3, 0.5)) {
            val w = (m.widthPixels * scale).toInt() / 16 * 16
            val h = (m.heightPixels * scale).toInt() / 16 * 16
            if (caps == null || caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)) {
                val bitrate = (w.toLong() * h * 30 / 10).toInt().coerceIn(2_000_000, 12_000_000)
                return VideoSize(w, h, m.densityDpi, bitrate)
            }
        }
        return VideoSize(720, 1280, m.densityDpi, 4_000_000)
    }
}
