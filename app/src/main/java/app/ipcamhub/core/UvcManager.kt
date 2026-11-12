package app.ipcamhub.core

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UvcState(
    val opened: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val message: String = Strings.get(R.string.uvc_waiting),
)

/**
 * Owns the single UVC capture device. Any number of surfaces (screen preview, encoder) can be
 * attached; the UVC library renders every frame to all of them.
 */
object UvcManager {
    private const val TAG = "UvcManager"

    private val _state = MutableStateFlow(UvcState())
    val state: StateFlow<UvcState> = _state

    private val main = Handler(Looper.getMainLooper())
    private var helper: ICameraHelper? = null
    private var current: UsbDevice? = null
    private var selecting = false
    private var cameraOpen = false
    private var preview: SurfaceTexture? = null
    private val extraSurfaces = mutableListOf<Surface>()

    private var appContext: Context? = null

    /** The card's modes are only readable while the camera is open, so they are remembered. */
    private var knownSizes: List<Size> = emptyList()
    private var lastTried: String? = null

    /** Android refuses USB access to a capture card unless the app holds the CAMERA permission. */
    fun hasCameraPermission(): Boolean =
        appContext?.let {
            androidx.core.content.ContextCompat.checkSelfPermission(it, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } ?: false

    /** Tries again to open the card, for example right after the camera permission was granted. */
    fun retry() {
        main.post {
            if (!cameraOpen) {
                selecting = false
                helper?.deviceList?.firstOrNull { isVideoDevice(it) }?.let { select(it) }
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (helper != null) return
        UVCUtils.init(context.applicationContext)
        val h = CameraHelper()
        helper = h
        h.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (isVideoDevice(device)) select(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                appContext?.let { releaseKernelVideoDriver(it, device) }
                val sizes = h.supportedSizeList?.takeIf { it.isNotEmpty() } ?: knownSizes
                Log.i(TAG, "capture card sizes at device open: ${sizes.size}")
                val best = pickPreferred(sizes) ?: pickBest(sizes)
                if (best != null) h.openCamera(best) else h.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice) {
                h.startPreview()
                val size = h.previewSize
                // The list of modes is only complete once the camera is open.
                h.supportedSizeList?.takeIf { it.isNotEmpty() }?.let { knownSizes = it.toList() }
                val modes = knownSizes.map { modeOf(it) }.distinct()
                Log.i(TAG, "capture card modes: $modes; preview ${size?.width}x${size?.height}@${size?.let { maxFpsOf(it) }}")
                main.post { if (modes.isNotEmpty()) _modes.value = modes }
                // The mode list is empty when the device opens, so the wanted mode is applied here.
                val target = pickPreferred(knownSizes) ?: pickBest(knownSizes)
                val targetKey = target?.let { "${it.width}x${it.height}/${it.type}" }
                if (target != null && size != null && (target.width != size.width || target.height != size.height || target.type != size.type) && lastTried != targetKey) {
                    lastTried = targetKey
                    main.post { reopenAfterClose = true; h.closeCamera() }
                    return
                }
                // Count raw frames straight from the card, before any decoding or rendering.
                h.setFrameCallback({ frameCount.incrementAndGet() }, UVCCamera.PIXEL_FORMAT_RAW)
                main.post {
                    lastCountMs = 0L
                    main.removeCallbacks(fpsTick)
                    main.postDelayed(fpsTick, 2000)
                }
                main.post {
                    cameraOpen = true
                    selecting = false
                    _state.value = UvcState(
                        opened = true,
                        width = size?.width ?: 0,
                        height = size?.height ?: 0,
                        fps = size?.let { maxFps(it) } ?: 0,
                        message = Strings.get(R.string.uvc_connected),
                    )
                    attachAll()
                }
            }

            override fun onCameraClose(device: UsbDevice) {
                main.post {
                    cameraOpen = false
                    _state.value = UvcState(message = Strings.get(R.string.uvc_closed))
                    if (reopenAfterClose) {
                        // A different mode was chosen: open the camera again with it.
                        reopenAfterClose = false
                        // The kernel driver may have taken the card back when it was released.
                        appContext?.let { releaseKernelVideoDriver(it, device) }
                        val size = pickPreferred(knownSizes) ?: pickBest(knownSizes)
                        Log.i(TAG, "reopening with ${size?.width}x${size?.height} type ${size?.type}")
                        main.postDelayed({ if (size != null) h.openCamera(size) else h.openCamera() }, 400)
                        // Closing the camera can close the whole device; select it again if nothing opened.
                        main.postDelayed({ if (!cameraOpen) { selecting = false; current?.let { select(it) } } }, 2500)
                    }
                }
            }

            override fun onDeviceClose(device: UsbDevice) {}

            override fun onDetach(device: UsbDevice) {
                main.post {
                    cameraOpen = false
                    selecting = false
                    current = null
                    _modes.value = emptyList()
                    lastTried = null
                    _state.value = UvcState(message = Strings.get(R.string.uvc_detached))
                }
            }

            override fun onCancel(device: UsbDevice) {
                main.post {
                    selecting = false
                    _state.value = UvcState(message = Strings.get(R.string.uvc_permission_denied))
                }
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                Log.w(TAG, "camera error ${e.code}", e)
                main.post {
                    selecting = false
                    _state.value = UvcState(message = Strings.get(R.string.uvc_error, e.code))
                }
            }
        })
        // Devices that were plugged in before the app started.
        main.postDelayed({
            if (!cameraOpen && !selecting) {
                h.deviceList?.firstOrNull { isVideoDevice(it) }?.let { select(it) }
            }
        }, 1500)
    }

    /**
     * Some phones bind their own kernel driver to a capture card's video interfaces, so the
     * library's claim fails with "device busy". Claiming the interface with force detaches that
     * driver; releasing it again leaves it free for the library. Audio interfaces are left alone.
     */
    private fun releaseKernelVideoDriver(context: Context, device: UsbDevice) {
        val usb = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val connection = try {
            usb.openDevice(device)
        } catch (e: Exception) {
            null
        } ?: return
        try {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_VIDEO) continue
                if (connection.claimInterface(intf, false)) {
                    Log.i(TAG, "video interface ${intf.id} was free")
                } else {
                    val forced = connection.claimInterface(intf, true)
                    Log.i(TAG, "video interface ${intf.id} was busy; forced claim succeeded=$forced")
                }
                connection.releaseInterface(intf)
            }
        } finally {
            connection.close()
        }
    }

    private fun select(device: UsbDevice) {
        if (selecting || cameraOpen) return
        if (!hasCameraPermission()) {
            _state.value = UvcState(message = Strings.get(R.string.uvc_needs_camera_permission))
            return
        }
        selecting = true
        current = device
        _state.value = UvcState(message = Strings.get(R.string.uvc_requesting_permission))
        helper?.selectDevice(device)
    }

    fun isVideoDevice(d: UsbDevice): Boolean =
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    private val _modes = MutableStateFlow<List<String>>(emptyList())

    /** Resolution and frame rate combinations the capture card offers, such as "1920x1080@60". */
    val modes: StateFlow<List<String>> = _modes

    private var preferredMode = ""
    private var reopenAfterClose = false

    private val _measuredFps = MutableStateFlow(0.0)

    /** Frames per second the capture card is really delivering (not the requested mode). */
    val measuredFps: StateFlow<Double> = _measuredFps
    private val frameCount = java.util.concurrent.atomic.AtomicLong()
    private var lastCount = 0L
    private var lastCountMs = 0L

    private val fpsTick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val count = frameCount.get()
            if (lastCountMs != 0L && now > lastCountMs) {
                _measuredFps.value = (count - lastCount) * 1000.0 / (now - lastCountMs)
                Log.i(TAG, "capture card delivers %.1f fps".format(_measuredFps.value))
            }
            lastCount = count
            lastCountMs = now
            if (cameraOpen) main.postDelayed(this, 2000)
        }
    }

    private fun maxFpsOf(s: Size): Int = s.fpsList?.maxOrNull() ?: s.fps

    private fun modeOf(s: Size) = "${s.width}x${s.height}@${maxFpsOf(s)}"

    private fun pickPreferred(list: List<Size>?): Size? {
        if (preferredMode.isEmpty()) return null
        val match = list?.filter { modeOf(it) == preferredMode }
            ?.maxByOrNull { if (it.type == UVCCamera.UVC_VS_FRAME_MJPEG) 1 else 0 } ?: return null
        return Size(match.type, match.width, match.height, maxFpsOf(match), match.fpsList)
    }

    /** Chooses the capture mode ("" = automatic). Restarts the camera when it is already open. */
    fun setPreferredMode(mode: String) {
        if (mode == preferredMode) return
        preferredMode = mode
        if (cameraOpen) {
            reopenAfterClose = true
            helper?.closeCamera()
        }
    }

    fun setPreview(texture: SurfaceTexture?) {
        val old = preview
        preview = texture
        if (old != null && old !== texture) helper?.removeSurface(old)
        if (texture != null && cameraOpen) helper?.addSurface(texture, false)
    }

    fun addSurface(surface: Surface) {
        extraSurfaces.add(surface)
        if (cameraOpen) helper?.addSurface(surface, true)
    }

    fun removeSurface(surface: Surface) {
        extraSurfaces.remove(surface)
        helper?.removeSurface(surface)
    }

    private fun attachAll() {
        preview?.let { helper?.addSurface(it, false) }
        extraSurfaces.forEach { helper?.addSurface(it, true) }
    }

    private fun maxFps(s: Size): Int = s.fpsList?.maxOrNull() ?: s.fps

    /**
     * Prefers 720p: on tested phones the card delivers a full 60 fps at 1280x720 but only about 8 fps in
     * any larger MJPEG mode, so 1080p stays a manual choice. Then the highest frame rate up to 60, then MJPEG.
     */
    private fun pickBest(list: List<Size>?): Size? {
        if (list.isNullOrEmpty()) return null
        val hd = list.filter { it.width == 1280 && it.height == 720 }
        val pool = hd.ifEmpty { list }
        val best = pool.maxWithOrNull(
            compareBy<Size>(
                { it.width * it.height },
                { maxFps(it).coerceAtMost(60) },
                { if (it.type == UVCCamera.UVC_VS_FRAME_MJPEG) 1 else 0 },
            )
        ) ?: return null
        return Size(best.type, best.width, best.height, maxFps(best).coerceAtMost(60), best.fpsList)
    }
}
