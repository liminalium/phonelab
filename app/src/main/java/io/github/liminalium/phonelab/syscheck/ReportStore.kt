package io.github.liminalium.phonelab.syscheck

import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reports live in app storage (one file per run) and are copied to Download/PhoneLab. */
object ReportStore {
    private val idFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    fun idFor(time: Instant): String = idFormat.format(time)

    private fun dir(ctx: Context) = File(ctx.filesDir, "syscheck").apply { mkdirs() }

    fun save(ctx: Context, id: String, report: JSONObject): File =
        File(dir(ctx), "syscheck-$id.json").also { it.writeText(report.toString(2)) }

    fun latest(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.name.startsWith("syscheck-") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.name }

    /** Writes [file] to Download/PhoneLab, overwriting [existing] when the copy is still there. */
    fun exportToDownloads(ctx: Context, file: File, existing: Uri?): Pair<Uri, String> {
        val resolver = ctx.contentResolver
        fun write(uri: Uri) {
            val out = resolver.openOutputStream(uri, "wt") ?: error("cannot open $uri")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
        }
        val path = "${Environment.DIRECTORY_DOWNLOADS}/PhoneLab/${file.name}"
        if (existing != null && safe { write(existing) } != null) return existing to path
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PhoneLab")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("the Downloads folder refused the file")
        write(uri)
        return uri to path
    }

    /** The Download/PhoneLab copy of [file] made by an earlier run, if it still exists. */
    fun findExport(ctx: Context, file: File): Pair<Uri, String>? = safe {
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(file.name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) to
                    "${Environment.DIRECTORY_DOWNLOADS}/PhoneLab/${file.name}"
            } else {
                null
            }
        }
    }

    /** Shares the JSON file, with the text summary for apps that only take text. */
    fun shareIntent(ctx: Context, file: File, summary: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, summary)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share system check")
    }
}
