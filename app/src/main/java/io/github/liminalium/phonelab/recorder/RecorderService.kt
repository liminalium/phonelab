package io.github.liminalium.phonelab.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.liminalium.phonelab.MainActivity
import io.github.liminalium.phonelab.R
import io.github.liminalium.phonelab.syscheck.safe

/**
 * One foreground service records the screen, the microphone and the camera
 * bubbles while you use other apps. Android 14+ rules shape the order: the
 * user consents to screen capture first, the service then goes foreground
 * with the matching types while the app is still visible, and only then asks
 * for the MediaProjection.
 */
class RecorderService : Service() {
    private var recording: ScreenRecording? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (recording != null) finish()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun start(intent: Intent) {
        if (recording != null) return
        val mic = intent.getBooleanExtra(EXTRA_MIC, true) && granted(Manifest.permission.RECORD_AUDIO)
        val bubbles = intent.getBooleanExtra(EXTRA_BUBBLES, true) &&
            granted(Manifest.permission.CAMERA) && Settings.canDrawOverlays(this)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30) {
            if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (bubbles) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        val startedAt = System.currentTimeMillis()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(startedAt, mic, bubbles), types)
        } catch (e: Exception) {
            fail("Android refused to start the recording service: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            fail("screen capture permission was missing")
            return
        }
        val r = ScreenRecording(this, resultCode, data, mic, bubbles) { finish() }
        recording = r
        try {
            r.start()
            RecorderState.set(RecorderState.State.Recording(startedAt, mic, bubbles))
            val without = listOfNotNull("mic".takeIf { !mic }, "camera bubbles".takeIf { !bubbles })
            RecorderState.event("recording started" + if (without.isEmpty()) "" else " without ${without.joinToString(" and ")}")
        } catch (e: Exception) {
            r.stop()
            recording = null
            fail("could not start recording: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun finish() {
        val r = recording
        recording = null
        if (r != null) {
            val saved = r.stop()
            if (saved != null) {
                RecorderState.set(RecorderState.State.Saved(saved, r.name))
                RecorderState.event("saved Movies/PhoneLab/${r.name}")
            } else {
                RecorderState.set(RecorderState.State.Failed("the recording was too short or empty, so it was discarded"))
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        RecorderState.set(RecorderState.State.Failed(message))
        RecorderState.event(message)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(startedAt: Long, mic: Boolean, bubbles: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while PhoneLab records the screen"
            },
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val parts = listOfNotNull("screen", "mic".takeIf { mic }, "cameras".takeIf { bubbles })
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("PhoneLab is recording")
            .setContentText("Recording " + parts.joinToString(" + "))
            .setOngoing(true)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_START = "io.github.liminalium.phonelab.recorder.START"
        private const val ACTION_STOP = "io.github.liminalium.phonelab.recorder.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_MIC = "mic"
        private const val EXTRA_BUBBLES = "bubbles"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Call right after the user allows screen capture, while the app is still visible. */
        fun start(ctx: Context, resultCode: Int, data: Intent, mic: Boolean, bubbles: Boolean) {
            val intent = Intent(ctx, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_MIC, mic)
                .putExtra(EXTRA_BUBBLES, bubbles)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** The MediaProjection, the encoder and the bubbles for one recording. */
private class ScreenRecording(
    private val ctx: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val mic: Boolean,
    private val bubbles: Boolean,
    private val onEndedBySystem: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: Recordings.Pending? = null
    private var cameraBubbles: CameraBubbles? = null
    private var started = false
    private var stopped = false

    val name: String get() = output?.name ?: ""

    fun start() {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: error("screen capture was not allowed")
        projection = p
        // Android 14+ requires the callback before the virtual display exists.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { if (!stopped) onEndedBySystem() }
            }
        }, main)
        val size = VideoSizes.pick(ctx)
        val out = Recordings.create(ctx)
        output = out
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        recorder = r
        if (mic) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(size.width, size.height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(size.bitrate)
        if (mic) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(128_000)
        }
        r.setOutputFile(out.fd.fileDescriptor)
        r.prepare()
        display = p.createVirtualDisplay(
            "PhoneLab", size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main,
        )
        r.start()
        started = true
        RecorderState.event("encoding ${size.width}x${size.height} at ${size.bitrate / 1_000_000} Mbit/s")
        if (bubbles) cameraBubbles = CameraBubbles(ctx).also { it.show() }
    }

    /** Stops everything; returns the saved video, or null if nothing usable was recorded. */
    fun stop(): android.net.Uri? {
        if (stopped) return null
        stopped = true
        cameraBubbles?.dismiss()
        var keep = false
        recorder?.let { r ->
            // stop() throws when no frame was ever encoded.
            keep = started && safe { r.stop() } != null
            safe { r.release() }
        }
        safe { display?.release() }
        safe { projection?.stop() }
        return output?.let { Recordings.finish(ctx, it, keep) }
    }
}
