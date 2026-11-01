package io.github.liminalium.phonelab.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.liminalium.phonelab.syscheck.safe

/**
 * Floating front and back camera previews drawn over every app. The screen
 * recording captures them, so the video needs no merging step.
 *
 * The front camera opens first; the back one opens once the front shows a
 * frame. If a phone can't run both, the camera that fails closes its bubble
 * and the recording carries on. Previews assume the phone is held upright.
 */
class CameraBubbles(context: Context) {
    // Android 11+ wants overlay windows created from a window context.
    private val ctx: Context = if (Build.VERSION.SDK_INT >= 30) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        context
    }
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-bubbles").apply { start() }
    private val cameraHandler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val bubbles = mutableListOf<Bubble>()
    private val waiting = ArrayDeque<Bubble>()

    @Volatile
    private var closed = false

    /** Call on the main thread. */
    fun show() {
        val ids = safe { cm.cameraIdList.toList() }.orEmpty()
        fun idFor(facing: Int) = ids.firstOrNull { id ->
            safe { cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) } == facing
        }
        val density = ctx.resources.displayMetrics.density
        val w = (108 * density).toInt()
        val h = (144 * density).toInt()
        val margin = (16 * density).toInt()
        val x = ctx.resources.displayMetrics.widthPixels - w - margin
        idFor(CameraCharacteristics.LENS_FACING_FRONT)?.let { bubbles += Bubble(it, "front", x, margin * 6, w, h) }
        idFor(CameraCharacteristics.LENS_FACING_BACK)?.let { bubbles += Bubble(it, "back", x, margin * 7 + h, w, h) }
        if (bubbles.isEmpty()) RecorderState.event("no front or back camera found")
        waiting.addAll(bubbles)
        startNext()
    }

    /** Call on the main thread. */
    fun dismiss() {
        closed = true
        waiting.clear()
        bubbles.toList().forEach { it.close() }
        bubbles.clear()
        thread.quitSafely()
    }

    private fun startNext() {
        if (closed) return
        waiting.removeFirstOrNull()?.start()
    }

    private inner class Bubble(
        private val cameraId: String,
        private val label: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        private val texture = TextureView(ctx)
        private val frame = FrameLayout(ctx)
        private val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var surface: Surface? = null
        private var attached = false
        private var handedOn = false

        fun start() {
            val radius = 16 * ctx.resources.displayMetrics.density
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            frame.clipToOutline = true
            frame.setBackgroundColor(Color.BLACK)
            frame.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            frame.setOnTouchListener(DragListener())
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    // 4:3 frames shown in a 3:4 bubble: upright when the phone is held upright.
                    st.setDefaultBufferSize(640, 480)
                    surface = Surface(st)
                    open()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    if (!handedOn) {
                        handedOn = true
                        RecorderState.event("$label camera bubble on")
                        startNext()
                    }
                }
            }
            try {
                wm.addView(frame, params)
                attached = true
            } catch (e: Exception) {
                lost("overlay refused: ${e.javaClass.simpleName}")
            }
        }

        @SuppressLint("MissingPermission")
        private fun open() {
            try {
                cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) {
                            camera.close()
                            return
                        }
                        device = camera
                        createSession(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        lost("disconnected (another app or camera took it)")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        lost(if (error == ERROR_MAX_CAMERAS_IN_USE) "too many cameras open" else "camera error $error")
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                lost(e.message ?: e.javaClass.simpleName)
            }
        }

        @Suppress("DEPRECATION")
        private fun createSession(camera: CameraDevice) {
            val target = surface ?: return
            try {
                camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target) }.build()
                            s.setRepeatingRequest(request, null, cameraHandler)
                        } catch (e: Exception) {
                            lost("preview failed: ${e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        lost("preview stream rejected")
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                lost("preview failed: ${e.javaClass.simpleName}")
            }
        }

        private fun lost(reason: String) {
            main.post {
                if (closed) return@post
                RecorderState.event("$label camera bubble closed: $reason")
                close()
                bubbles.remove(this)
                if (!handedOn) {
                    handedOn = true
                    startNext()
                }
            }
        }

        fun close() {
            safe { session?.close() }
            safe { device?.close() }
            session = null
            device = null
            if (attached) {
                safe { wm.removeView(frame) }
                attached = false
            }
            safe { surface?.release() }
            surface = null
        }

        private inner class DragListener : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - touchX).toInt()
                        params.y = startY + (e.rawY - touchY).toInt()
                        if (attached) safe { wm.updateViewLayout(frame, params) }
                    }
                    MotionEvent.ACTION_UP -> v.performClick()
                }
                return true
            }
        }
    }
}
