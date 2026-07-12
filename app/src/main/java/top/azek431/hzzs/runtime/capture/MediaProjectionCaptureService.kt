package top.azek431.hzzs.runtime.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import top.azek431.hzzs.R

/**
 * MediaProjection 帧源。
 *
 * - 启动前先进入前台；
 * - 注册 MediaProjection.Callback 后再创建 VirtualDisplay；
 * - ImageReader 在独立 HandlerThread 上取最新帧，避免主线程复制像素；
 * - 显式处理 rowStride/pixelStride 产生的右侧 padding；
 * - 配置变化时复用同一 VirtualDisplay 并替换 Surface，不复用授权创建第二个会话。
 */
class MediaProjectionCaptureService : Service() {
    companion object {
        const val ACTION_START = "top.azek431.hzzs.capture.START"
        const val ACTION_STOP = "top.azek431.hzzs.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL = "hzzs_capture"
        private const val NOTIFICATION_ID = 4312
        private val latestLock = Any()

        @Volatile private var latest: Bitmap? = null
        @Volatile private var ready = false

        fun isReady(): Boolean = ready && synchronized(latestLock) { latest != null }

        fun latestCopy(): Bitmap? = synchronized(latestLock) {
            val current = latest ?: return@synchronized null
            if (current.isRecycled) null else current.copy(Bitmap.Config.ARGB_8888, false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var releasing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture(stopProjection = false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        captureThread = HandlerThread("hzzs-media-projection").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseCapture(stopProjection = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, CHANNEL)
                        .setContentTitle("HZZS 屏幕采集")
                        .setContentText("正在为本地视觉分析采集画面")
                        .setSmallIcon(R.drawable.ic_stat_hzzs)
                        .setOngoing(true)
                        .build(),
                )
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = resultData(intent)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startProjection(resultCode, resultData)
                } else {
                    releaseCapture(stopProjection = true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    @Synchronized
    private fun startProjection(resultCode: Int, resultData: Intent) {
        releaseCapture(stopProjection = true)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, captureHandler)

        val metrics = currentDisplayMetrics()
        val imageReader = createReader(metrics.widthPixels, metrics.heightPixels)
        reader = imageReader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "hzzs-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            captureHandler,
        )
        ready = virtualDisplay != null
    }

    private fun createReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener(
                { source ->
                    source.acquireLatestImage()?.use { image ->
                        updateLatest(image, width, height)
                    }
                },
                captureHandler,
            )
        }

    private fun updateLatest(image: Image, requestedWidth: Int, requestedHeight: Int) {
        if (!ready) return
        val plane = image.planes.firstOrNull() ?: return
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4 || rowStride <= 0) return

        val paddedWidth = rowStride / pixelStride
        if (paddedWidth < requestedWidth) return

        val padded = Bitmap.createBitmap(paddedWidth, requestedHeight, Bitmap.Config.ARGB_8888)
        val cropped = try {
            val buffer = plane.buffer
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == requestedWidth) {
                padded
            } else {
                Bitmap.createBitmap(
                    padded,
                    0,
                    0,
                    requestedWidth.coerceAtMost(padded.width),
                    requestedHeight.coerceAtMost(padded.height),
                )
            }
        } catch (throwable: Throwable) {
            padded.recycle()
            throw throwable
        }

        if (cropped !== padded && !padded.isRecycled) padded.recycle()
        synchronized(latestLock) {
            latest?.let { previous -> if (!previous.isRecycled) previous.recycle() }
            latest = cropped
        }
    }

    @Synchronized
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        ready = false
        val metrics = currentDisplayMetrics()
        val replacement = createReader(metrics.widthPixels, metrics.heightPixels)
        val previous = reader
        reader = replacement
        display.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        display.setSurface(replacement.surface)
        previous?.setOnImageAvailableListener(null, null)
        previous?.close()
        synchronized(latestLock) {
            latest?.let { previous -> if (!previous.isRecycled) previous.recycle() }
            latest = null
        }
        ready = true
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayMetrics = DisplayMetrics().also { metrics ->
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
    }

    @Synchronized
    private fun releaseCapture(stopProjection: Boolean) {
        if (releasing) return
        releasing = true
        try {
            ready = false
            virtualDisplay?.release()
            virtualDisplay = null
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
            reader = null

            val current = projection
            projection = null
            runCatching { current?.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { current?.stop() }

            synchronized(latestLock) {
                latest?.takeUnless(Bitmap::isRecycled)?.recycle()
                latest = null
            }
        } finally {
            releasing = false
        }
    }

    override fun onDestroy() {
        releaseCapture(stopProjection = true)
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HZZS 屏幕采集", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
