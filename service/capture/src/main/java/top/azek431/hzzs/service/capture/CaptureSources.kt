package top.azek431.hzzs.service.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService

interface FrameSourceFactory {
    fun source(backend: CaptureBackend): FrameSource
}

@Singleton
class DefaultFrameSourceFactory @Inject constructor(
    private val automatic: AutoFrameSource,
    private val mediaProjection: MediaProjectionFrameSource,
    private val accessibility: AccessibilityFrameSource,
    private val root: RootFrameSource,
) : FrameSourceFactory {
    override fun source(backend: CaptureBackend): FrameSource = when (backend) {
        CaptureBackend.AUTO -> automatic
        CaptureBackend.MEDIA_PROJECTION -> mediaProjection
        CaptureBackend.ACCESSIBILITY -> accessibility
        CaptureBackend.ROOT -> root
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureBindings {
    @Binds
    abstract fun bindFactory(impl: DefaultFrameSourceFactory): FrameSourceFactory
}

/**
 * Chooses an already-authorized MediaProjection first, then Accessibility,
 * then Root. If none are ready it requests MediaProjection permission. The
 * active backend is stable for the session and is never switched mid-frame.
 */
@Singleton
class AutoFrameSource @Inject constructor(
    private val mediaProjection: MediaProjectionFrameSource,
    private val accessibility: AccessibilityFrameSource,
    private val root: RootFrameSource,
) : FrameSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState

    @Volatile
    private var active: FrameSource? = null
    private var stateMirror: Job? = null

    override suspend fun start() {
        active?.let { current ->
            if (current.state.value == CaptureState.Ready || current.state.value == CaptureState.RequestingPermission) {
                return
            }
        }
        stopActive()

        if (mediaProjection.state.value == CaptureState.Ready) {
            select(mediaProjection)
            return
        }

        accessibility.start()
        if (accessibility.state.value == CaptureState.Ready) {
            select(accessibility)
            return
        }

        root.start()
        if (root.state.value == CaptureState.Ready) {
            select(root)
            return
        }

        mediaProjection.start()
        select(mediaProjection)
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? =
        active?.nextFrame(afterSequence)

    override suspend fun stop() {
        stopActive()
        mutableState.value = CaptureState.Idle
    }

    private fun select(source: FrameSource) {
        active = source
        stateMirror?.cancel()
        mutableState.value = source.state.value
        stateMirror = scope.launch {
            source.state.collect { mutableState.value = it }
        }
    }

    private suspend fun stopActive() {
        stateMirror?.cancel()
        stateMirror = null
        active?.stop()
        active = null
    }
}

@Singleton
class MediaProjectionFrameSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val frames = Channel<CapturedFrame>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = CapturedFrame::close,
    )
    private val pool = IntFramePool(3)
    private val sequencer = FrameSequencer()

    override suspend fun start() {
        if (state.value == CaptureState.Ready || state.value == CaptureState.RequestingPermission) return
        mutableState.value = CaptureState.RequestingPermission
        context.startActivity(
            Intent(context, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    internal fun accept(image: Image) {
        val size = safePixelCount(image.width, image.height)
        if (size == null) {
            fail("屏幕尺寸无效：${image.width}×${image.height}", recoverable = false)
            return
        }
        val lease = pool.tryAcquire(size) ?: return
        try {
            PlaneRgbaReader.copy(image, lease.pixels)
            val (sequence, timestamp) = sequencer.next()
            val frame = CapturedFrame(
                sequence = sequence,
                elapsedRealtimeNanos = timestamp,
                width = image.width,
                height = image.height,
                pixels = lease.pixels,
                releaseLease = lease::close,
            )
            if (frames.trySend(frame).isFailure) frame.close()
        } catch (error: Exception) {
            lease.close()
            fail("屏幕帧读取失败：${error.message}", recoverable = true)
        }
    }

    internal fun ready() {
        mutableState.value = CaptureState.Ready
    }

    internal fun fail(message: String, recoverable: Boolean) {
        mutableState.value = CaptureState.Failed(message, recoverable)
    }

    internal fun idle() {
        mutableState.value = CaptureState.Idle
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? = withTimeoutOrNull(1_200L) {
        while (true) {
            val frame = frames.receive()
            if (frame.sequence > afterSequence) return@withTimeoutOrNull frame
            frame.close()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun stop() {
        context.startService(
            Intent(context, MediaProjectionCaptureService::class.java)
                .setAction(MediaProjectionCaptureService.ACTION_STOP),
        )
        drainFrames()
        idle()
    }

    private fun drainFrames() {
        while (true) frames.tryReceive().getOrNull()?.close() ?: break
    }
}

class CapturePermissionActivity : Activity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        if (!launched) {
            launched = true
            val manager = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MediaProjectionCaptureService::class.java)
                .setAction(MediaProjectionCaptureService.ACTION_START)
                .putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CAPTURE = 4301
        private const val KEY_LAUNCHED = "launched"
    }
}

@AndroidEntryPoint
class MediaProjectionCaptureService : Service() {
    @Inject lateinit var source: MediaProjectionFrameSource

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> cleanup(updateStateToIdle = true, stopProjection = true, stopService = true)
            else -> stopSelf() // Sensitive service fails closed for null/unknown intents.
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (projection != null || stopping) return
        createChannel()
        startForeground(NOTIFICATION_ID, notification())

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            source.fail("屏幕捕获授权无效", false)
            cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(code, data)
        if (newProjection == null) {
            source.fail("无法创建屏幕捕获会话", true)
            cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (stopping) return
                source.fail("系统已终止屏幕捕获", true)
                cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            }
        }
        projection = newProjection
        projectionCallback = callback
        newProjection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val worker = HandlerThread("hzzs-capture", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = worker
        val workerHandler = Handler(worker.looper)
        val imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            3,
        )
        reader = imageReader
        imageReader.setOnImageAvailableListener({ availableReader ->
            availableReader.acquireLatestImage()?.use(source::accept)
        }, workerHandler)
        display = newProjection.createVirtualDisplay(
            "HZZS-Vision",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            workerHandler,
        )
        source.ready()
    }

    private fun cleanup(
        updateStateToIdle: Boolean,
        stopProjection: Boolean,
        stopService: Boolean,
    ) {
        if (stopping) return
        stopping = true

        val localProjection = projection
        val localCallback = projectionCallback
        projection = null
        projectionCallback = null

        display?.release()
        display = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null

        if (localProjection != null && localCallback != null) {
            runCatching { localProjection.unregisterCallback(localCallback) }
        }
        if (stopProjection) runCatching { localProjection?.stop() }
        if (updateStateToIdle) source.idle()

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) {
            source.fail("屏幕捕获服务已结束", true)
            cleanup(updateStateToIdle = false, stopProjection = true, stopService = false)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "屏幕视觉分析",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("火崽崽视觉分析")
        .setContentText("正在本机读取屏幕帧，不会上传截图")
        .setOngoing(true)
        .addAction(
            0,
            "全部停止",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MediaProjectionCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        const val ACTION_START = "top.azek431.hzzs.capture.START"
        const val ACTION_STOP = "top.azek431.hzzs.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "vision_capture"
        private const val NOTIFICATION_ID = 431
    }
}

@Singleton
class AccessibilityFrameSource @Inject constructor() : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val sequencer = FrameSequencer()

    override suspend fun start() {
        mutableState.value = if (Build.VERSION.SDK_INT < 30) {
            CaptureState.Failed("无障碍截图需要 Android 11 或更高版本", false)
        } else if (!HzzsAccessibilityService.isConnected()) {
            CaptureState.Failed("请先启用火崽崽无障碍服务", true)
        } else {
            CaptureState.Ready
        }
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? {
        if (state.value != CaptureState.Ready || Build.VERSION.SDK_INT < 30) return null
        val bitmap = HzzsAccessibilityService.captureBitmap() ?: return null
        return try {
            val size = safePixelCount(bitmap.width, bitmap.height) ?: return null
            val pixels = IntArray(size)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val (sequence, timestamp) = sequencer.next()
            if (sequence <= afterSequence) null else CapturedFrame(
                sequence,
                timestamp,
                bitmap.width,
                bitmap.height,
                pixels,
            )
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun stop() {
        mutableState.value = CaptureState.Idle
    }
}

@Singleton
class RootFrameSource @Inject constructor() : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val sequencer = FrameSequencer()

    override suspend fun start() {
        mutableState.value = if (
            runCommand(listOf("su", "-c", "id"), 128 * 1024, 1_500)?.isNotEmpty() == true
        ) {
            CaptureState.Ready
        } else {
            CaptureState.Failed("Root 不可用或未授权", true)
        }
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? {
        if (state.value != CaptureState.Ready) return null
        val bytes = runCommand(
            listOf("su", "-c", "screencap -p"),
            MAX_SCREENSHOT_BYTES,
            4_000,
        ) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val size = safePixelCount(bitmap.width, bitmap.height) ?: return null
            val pixels = IntArray(size)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val (sequence, timestamp) = sequencer.next()
            if (sequence <= afterSequence) null else CapturedFrame(
                sequence,
                timestamp,
                bitmap.width,
                bitmap.height,
                pixels,
            )
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun stop() {
        mutableState.value = CaptureState.Idle
    }

    private suspend fun runCommand(
        command: List<String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        require(timeoutMs > 0)
        val process = runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@withContext null

        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    runCatching { readLimited(process.inputStream, maxBytes) }
                        .onFailure { process.destroyForcibly() }
                        .getOrNull()
                }
                val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    process.waitFor(500, TimeUnit.MILLISECONDS)
                }
                val output = withTimeoutOrNull(1_000L) { reader.await() }
                if (!exited || process.exitValue() != 0) null else output
            }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 1024 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > maxBytes.toLong()) {
                    throw IllegalStateException("Root command output exceeds $maxBytes bytes")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    companion object {
        private const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
    }
}

private fun safePixelCount(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0) return null
    val count = width.toLong() * height.toLong()
    return if (count in 1..Int.MAX_VALUE.toLong()) count.toInt() else null
}
