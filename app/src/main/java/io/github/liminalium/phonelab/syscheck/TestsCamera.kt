package io.github.liminalium.phonelab.syscheck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** One camera inside one streaming attempt. */
private class CamRun(val id: String) {
    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    var size: Size? = null
    var fps = 0
    @Volatile var opened = false
    @Volatile var error: String? = null
    @Volatile var lost: String? = null
    val frames = AtomicInteger()
    val closed = CompletableDeferred<Unit>()

    val ok get() = error == null && lost == null && fps > 0
    fun problem(): String? = error ?: lost ?: if (fps == 0) "no frames" else null
}

private fun deviceError(code: Int) = when (code) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled by policy"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
    else -> "error $code"
}

private fun accessError(e: Throwable): String = if (e is CameraAccessException) {
    when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> "camera in use"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraAccessException.CAMERA_DISABLED -> "camera disabled by policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "camera disconnected"
        CameraAccessException.CAMERA_ERROR -> "camera error"
        else -> "access error ${e.reason}"
    }
} else {
    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}

private fun pickSize(cm: CameraManager, id: String): Size {
    val sizes = cm.getCameraCharacteristics(id)
        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
    return sizes.firstOrNull { it.width == 640 && it.height == 480 }
        ?: sizes.filter { it.width >= 320 }.minByOrNull { it.width * it.height }
        ?: Size(640, 480)
}

@SuppressLint("MissingPermission")
private suspend fun openCamera(cm: CameraManager, run: CamRun, handler: Handler): CameraDevice? {
    val device = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<CameraDevice?> { cont ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    run.opened = true
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (run.opened) run.lost = "disconnected" else run.error = "disconnected"
                    camera.close()
                    if (cont.isActive) cont.resume(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (run.opened) run.lost = deviceError(error) else run.error = deviceError(error)
                    camera.close()
                    if (cont.isActive) cont.resume(null)
                }

                override fun onClosed(camera: CameraDevice) {
                    run.closed.complete(Unit)
                }
            }
            try {
                cm.openCamera(run.id, callback, handler)
            } catch (e: Exception) {
                run.error = accessError(e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }
    if (device == null && run.error == null && run.lost == null) run.error = "open timed out"
    return device
}

@Suppress("DEPRECATION")
private suspend fun configure(device: CameraDevice, surface: Surface, handler: Handler, run: CamRun): CameraCaptureSession? {
    val session = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
            try {
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        run.error = "stream configuration rejected"
                        if (cont.isActive) cont.resume(null)
                    }
                }, handler)
            } catch (e: Exception) {
                run.error = accessError(e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }
    if (session == null && run.error == null) run.error = "configure timed out"
    return session
}

/** Opens every camera in [ids] at once, streams small frames, and measures the frame rate of each. */
private suspend fun streamCameras(cm: CameraManager, ids: List<String>, handler: Handler): List<CamRun> {
    val runs = ids.map { CamRun(it) }
    try {
        for (r in runs) r.device = openCamera(cm, r, handler)
        for (r in runs) {
            val device = r.device ?: continue
            if (r.lost != null) continue
            val size = pickSize(cm, r.id)
            r.size = size
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.reader = reader
            reader.setOnImageAvailableListener({ rd ->
                try {
                    rd.acquireLatestImage()?.let {
                        it.close()
                        r.frames.incrementAndGet()
                    }
                } catch (e: Exception) {
                    // The reader can close while a frame is in flight.
                }
            }, handler)
            val session = configure(device, reader.surface, handler, r) ?: continue
            r.session = session
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply { addTarget(reader.surface) }.build()
                session.setRepeatingRequest(request, null, handler)
            } catch (e: Exception) {
                r.error = accessError(e)
            }
        }
        withTimeoutOrNull(4000) {
            while (runs.any { it.session != null && it.error == null && it.lost == null && it.frames.get() == 0 }) delay(50)
        }
        val before = runs.map { it.frames.get() }
        delay(1000)
        runs.forEachIndexed { i, r -> r.fps = r.frames.get() - before[i] }
    } finally {
        withContext(NonCancellable) {
            for (r in runs) {
                safe { r.session?.close() }
                safe { r.device?.close() }
            }
            for (r in runs) if (r.opened) withTimeoutOrNull(2000) { r.closed.await() }
            for (r in runs) safe { r.reader?.close() }
        }
    }
    return runs
}

/**
 * Streams each camera alone, then every pair. Android 10 has no API that says
 * which cameras can run together, so trying is the only way to find out.
 */
suspend fun cameraLiveTest(ctx: Context): Outcome = withContext(Dispatchers.Default) {
    val cm = ctx.getSystemService(CameraManager::class.java)
    val ids = cm.cameraIdList.toList()
    if (ids.isEmpty()) return@withContext Outcome(Status.SKIPPED, "no cameras", JSONObject())
    val facing = ids.associateWith { facingName(cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)) }
    val thread = HandlerThread("camera-test").apply { start() }
    val handler = Handler(thread.looper)
    try {
        val singles = ids.map { id -> streamCameras(cm, listOf(id), handler).first() }
        val pairs = ids.indices
            .flatMap { i -> (i + 1 until ids.size).map { j -> listOf(ids[i], ids[j]) } }
            .take(6)
            .map { pair -> pair to streamCameras(cm, pair, handler) }
        val backFront = pairs.filter { (pair, _) -> pair.map { facing[it] }.toSet() == setOf("back", "front") }
        val backFrontOk: Boolean? = if (backFront.isEmpty()) null else backFront.any { (_, runs) -> runs.all { it.ok } }
        val backFrontProblem = backFront.flatMap { it.second }.firstNotNullOfOrNull { it.problem() }
        val data = json {
            put("backFrontTogether", backFrontOk)
            put("singles", singles.map { r ->
                json {
                    put("id", r.id)
                    put("facing", facing[r.id])
                    put("ok", r.ok)
                    put("fps", r.fps)
                    put("size", r.size?.toString())
                    put("problem", r.problem())
                }
            })
            put("pairs", pairs.map { (pair, runs) ->
                json {
                    put("ids", pair.joinToString("+"))
                    put("facings", pair.joinToString("+") { facing[it] ?: "?" })
                    put("ok", runs.all { it.ok })
                    put("fps", runs.map { it.fps })
                    put("problems", runs.mapNotNull { r -> r.problem()?.let { "${r.id}: $it" } })
                }
            })
        }
        val singlesOk = singles.count { it.ok }
        val dualText = when (backFrontOk) {
            true -> "front + back together ✓"
            false -> "front + back together ✗ (${backFrontProblem ?: "failed"})"
            null -> "no front/back pair to try"
        }
        val status = when {
            singlesOk == 0 -> Status.FAIL
            backFrontOk == true -> Status.OK
            else -> Status.WARN
        }
        Outcome(status, "$dualText · $singlesOk of ${singles.size} cameras stream alone", data)
    } finally {
        thread.quitSafely()
    }
}
