package com.xbertz.livecam

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class StreamUiState(
    val isStreaming: Boolean = false,
    val accessCode: String? = null,
    val serverUrl: String? = null,
    val viewerCount: Int = 0,
    val error: String? = null,
)

/**
 * Thin controller in front of [StreamService]: the camera and the HTTP server live in the
 * service (so they survive the screen turning off), this class just starts/binds to it and
 * mirrors its state for the UI.
 */
class StreamViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState

    private var service: StreamService? = null
    private var stateCollectJob: Job? = null
    private var bound = false
    private var pendingPreviewProvider: Preview.SurfaceProvider? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = (binder as StreamService.LocalBinder).service()
            service = connectedService
            connectedService.setPreviewSurfaceProvider(pendingPreviewProvider)
            stateCollectJob = viewModelScope.launch {
                connectedService.uiState.collect { _uiState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            stateCollectJob?.cancel()
        }
    }

    /** Starts (if needed) and binds to the camera/server service; call from onStart. */
    fun bindService() {
        if (bound) return
        val context = getApplication<Application>()
        val intent = Intent(context, StreamService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    /** Unbinds the UI from the service without stopping it, so streaming keeps running. */
    fun unbindService() {
        if (bound) {
            attachPreviewSurface(null)
            getApplication<Application>().unbindService(connection)
            bound = false
        }
        service = null
        stateCollectJob?.cancel()
    }

    fun attachPreviewSurface(provider: Preview.SurfaceProvider?) {
        pendingPreviewProvider = provider
        service?.setPreviewSurfaceProvider(provider)
    }

    fun startStreaming() {
        service?.startStreaming()
    }

    fun stopStreaming() {
        service?.stopStreaming()
    }

    fun dismissError() {
        service?.dismissError()
    }

    override fun onCleared() {
        unbindService()
    }
}
