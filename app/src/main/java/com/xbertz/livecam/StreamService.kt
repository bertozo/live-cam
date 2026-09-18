package com.xbertz.livecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.xbertz.livecam.server.MjpegServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.util.concurrent.Executors

private const val SERVER_PORT = 8080
private const val NOTIFICATION_ID = 1
private const val CHANNEL_ID = "livecam_stream"
private const val TARGET_FRAME_INTERVAL_MS = 50L // ~20 fps cap
const val ACTION_STOP_STREAMING = "com.xbertz.livecam.action.STOP_STREAMING"

/**
 * Owns the camera and the embedded HTTP server as a foreground service, so both keep running
 * when the screen turns off or MainActivity goes to the background - a plain Activity-bound
 * CameraX session (and the process itself) gets torn down as soon as the screen locks.
 */
class StreamService : LifecycleService() {

    companion object {
        @Volatile var runningInstance: StreamService? = null
    }

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    private val binder = LocalBinder()

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null
    private var server: MjpegServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val random = SecureRandom()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        bindCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_STREAMING) {
            stopStreaming()
        }
        return START_STICKY
    }

    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        pendingSurfaceProvider = provider
        previewUseCase?.setSurfaceProvider(provider)
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                if (!_uiState.value.isStreaming || now - lastFrameTime < TARGET_FRAME_INTERVAL_MS) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                lastFrameTime = now
                try {
                    server?.broadcastFrame(imageProxy.toUprightJpeg())
                } finally {
                    imageProxy.close()
                }
            }

            previewUseCase = preview
            preview.setSurfaceProvider(pendingSurfaceProvider)

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                _uiState.update { s -> s.copy(error = "Nao foi possivel iniciar a camera: ${it.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun startStreaming() {
        if (_uiState.value.isStreaming) return

        val ip = getLocalIpAddress()
        if (ip == null) {
            _uiState.update { it.copy(error = "Conecte o celular a uma rede Wi-Fi para transmitir.") }
            return
        }

        val code = String.format("%06d", random.nextInt(1_000_000))
        val newServer = MjpegServer(
            port = SERVER_PORT,
            accessCode = code,
            onViewerCountChanged = { count -> _uiState.update { it.copy(viewerCount = count) } },
        )

        val started = runCatching { newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onFailure { _uiState.update { s -> s.copy(error = "Nao foi possivel iniciar o servidor: ${it.message}") } }
            .isSuccess
        if (!started) return

        server = newServer
        acquireWakeLock()
        _uiState.update {
            it.copy(
                isStreaming = true,
                accessCode = code,
                serverUrl = "http://$ip:$SERVER_PORT",
                viewerCount = 0,
                error = null,
            )
        }
        updateNotification()
    }

    fun stopStreaming() {
        server?.stopServer()
        server = null
        releaseWakeLock()
        _uiState.update { it.copy(isStreaming = false, accessCode = null, serverUrl = null, viewerCount = 0) }
        updateNotification()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveCam:StreamWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Transmissao LiveCam", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val state = _uiState.value
        val text = if (state.isStreaming) "Transmitindo - codigo ${state.accessCode}" else "Camera pronta"

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveCam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(contentIntent)

        if (state.isStreaming) {
            val stopIntent = Intent(this, StreamService::class.java).setAction(ACTION_STOP_STREAMING)
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Parar transmissao", stopPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        server?.stopServer()
        releaseWakeLock()
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
        runningInstance = null
        super.onDestroy()
    }
}
