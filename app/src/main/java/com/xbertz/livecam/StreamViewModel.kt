package com.xbertz.livecam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xbertz.livecam.server.MjpegServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

private const val SERVER_PORT = 8080

data class StreamUiState(
    val isStreaming: Boolean = false,
    val accessCode: String? = null,
    val serverUrl: String? = null,
    val viewerCount: Int = 0,
    val error: String? = null,
)

class StreamViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState

    private val random = SecureRandom()
    private var server: MjpegServer? = null

    fun startStreaming() {
        if (_uiState.value.isStreaming) return

        val ip = getLocalIpAddress()
        if (ip == null) {
            _uiState.update { it.copy(error = "Conecte o celular a uma rede Wi-Fi para transmitir.") }
            return
        }

        val code = generateAccessCode()
        val newServer = MjpegServer(
            port = SERVER_PORT,
            accessCode = code,
            onViewerCountChanged = { count -> _uiState.update { it.copy(viewerCount = count) } },
        )

        runCatching { newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onFailure {
                _uiState.update { state -> state.copy(error = "Nao foi possivel iniciar o servidor: ${it.message}") }
                return
            }

        server = newServer
        _uiState.update {
            it.copy(
                isStreaming = true,
                accessCode = code,
                serverUrl = "http://$ip:$SERVER_PORT",
                viewerCount = 0,
                error = null,
            )
        }
    }

    fun stopStreaming() {
        server?.stopServer()
        server = null
        _uiState.update {
            it.copy(isStreaming = false, accessCode = null, serverUrl = null, viewerCount = 0)
        }
    }

    fun onFrame(jpeg: ByteArray) {
        server?.broadcastFrame(jpeg)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun generateAccessCode(): String = String.format("%06d", random.nextInt(1_000_000))

    override fun onCleared() {
        server?.stopServer()
        server = null
    }
}
