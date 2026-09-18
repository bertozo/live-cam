package com.xbertz.livecam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: StreamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    LiveCamScreen(viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopStreaming()
    }
}

@Composable
fun LiveCamScreen(viewModel: StreamViewModel) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val uiState by viewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreview(
                isStreaming = uiState.isStreaming,
                onFrame = viewModel::onFrame,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionRationale(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isStreaming) {
                StreamingInfoCard(uiState)
                Spacer(Modifier.height(16.dp))
            }
            uiState.error?.let { message ->
                Snackbar(modifier = Modifier.padding(bottom = 12.dp)) { Text(message) }
            }
            Button(
                onClick = { if (uiState.isStreaming) viewModel.stopStreaming() else viewModel.startStreaming() },
                enabled = hasCameraPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isStreaming) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                ),
            ) {
                Text(if (uiState.isStreaming) "Parar transmissao" else "Iniciar transmissao")
            }
        }
    }
}

@Composable
private fun StreamingInfoCard(uiState: StreamUiState) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Codigo de acesso", color = Color.Gray, fontSize = 12.sp)
            Text(
                uiState.accessCode.orEmpty(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text("Endereco na rede local", color = Color.Gray, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiState.serverUrl.orEmpty(), color = Color.White, fontSize = 16.sp)
                IconButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("LiveCam URL", uiState.serverUrl.orEmpty()))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar endereco", tint = Color.White)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${uiState.viewerCount} espectador(es) conectado(s)",
                color = Color.Gray,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PermissionRationale(onRequestPermission: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "A camera e necessaria para transmitir o video.",
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermission) {
                Text("Permitir camera")
            }
        }
    }
}
