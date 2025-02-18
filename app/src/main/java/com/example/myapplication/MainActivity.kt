package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.audio.AudioManager
import com.example.myapplication.network.AIWebSocketManager
import androidx.compose.foundation.layout.Arrangement
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.myapplication.permissions.RequestCameraAndAudioPermissions
import com.example.myapplication.video.CameraManager
import com.example.myapplication.state.ConnectionState
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ChatScreen()
            }
        }
    }
}

@Composable
fun ChatScreen() {
    var isPermissionGranted by remember { mutableStateOf(false) }
    var isChatting by remember { mutableStateOf(false) }
    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val audioManager = remember { AudioManager(context) }
    val cameraManager = remember { CameraManager(context, lifecycleOwner) }
    
    var webSocketManager by remember { mutableStateOf<AIWebSocketManager?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var aiResponse by remember { mutableStateOf<String?>(null) }
    var isAISpeaking by remember { mutableStateOf(false) }
    var userSpeaking by remember { mutableStateOf(false) }
    var transcription by remember { mutableStateOf<String?>(null) }
    
    DisposableEffect(Unit) {
        onDispose {
            audioManager.release()
            cameraManager.stopCamera()
        }
    }
    
    LaunchedEffect(previewView, isChatting) {
        if (isChatting && previewView != null) {
            cameraManager.startCamera(
                previewView = previewView!!,
                onVideoFrame = { videoFrame ->
                    // 发送视频帧到WebSocket
                    webSocketManager?.sendVideoFrame(videoFrame)
                }
            )
        }
    }
    
    LaunchedEffect(isChatting) {
        if (isChatting) {
            webSocketManager = AIWebSocketManager(
                onMessageReceived = { audioData ->
                    // 处理接收到的音频数据
                },
                onConnectionEstablished = {
                    connectionState = ConnectionState.Connected
                    // 开始通话时立即开始录音
                    audioManager.startRecording { audioData ->
                        webSocketManager?.sendAudioData(audioData)
                    }
                },
                onConnectionError = { error ->
                    connectionState = ConnectionState.Error(error)
                },
                onResponseText = { text ->
                    aiResponse = text
                },
                onResponseAudio = { audio ->
                    when {
                        audio.isEmpty() -> {
                            // 开始收集MP3数据
                            audioManager.startMp3Collection()
                        }
                        audio.size == -1 -> {
                            // 完成MP3收集并播放
                            audioManager.finishAndPlayMp3()
                        }
                        else -> {
                            // 添加MP3数据块
                            audioManager.appendMp3Data(audio)
                        }
                    }
                },
                onResponseVideo = { videoFrame ->
                    // TODO: 实现视频帧显示逻辑
                },
                onTurnStart = {
                    isAISpeaking = true
                },
                onTurnEnd = {
                    isAISpeaking = false
                },
                onSpeechStarted = {
                    userSpeaking = true
                },
                onSpeechStopped = {
                    userSpeaking = false
                },
                onTranscriptionCompleted = { text ->
                    transcription = text
                },
                onResponseCreated = { responseId ->
                    // 可以保存 responseId 用于后续操作
                }
            )
            connectionState = ConnectionState.Connecting
            webSocketManager?.connect()
        } else {
            // 主动结束通话时释放所有资源
            audioManager.release()
            cameraManager.stopCamera()
            webSocketManager?.commitAudioBuffer()
            webSocketManager?.disconnect()
            webSocketManager = null
            connectionState = ConnectionState.Disconnected
            previewView = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isPermissionGranted) {
                RequestCameraAndAudioPermissions(
                    onAllPermissionsGranted = { isPermissionGranted = true },
                    onPermissionsDenied = { /* 处理权限被拒绝的情况 */ }
                )
            } else {
                if (isChatting) {
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                previewView = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f/16f)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { 
                        isChatting = !isChatting
                        if (!isChatting) {
                            cameraManager.stopCamera()
                            previewView = null
                        }
                    }
                ) {
                    Text(if (isChatting) "结束通话" else "开始通话")
                }
            }
            
            // 显示连接状态
            when (connectionState) {
                is ConnectionState.Error -> {
                    Text(
                        text = (connectionState as ConnectionState.Error).message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                ConnectionState.Connecting -> {
                    CircularProgressIndicator()
                }
                else -> { /* 其他状态不显示 */ }
            }
            
            // 显示AI响应文本
            aiResponse?.let { response ->
                Text(
                    text = response,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            // 显示AI说话状态
            if (isAISpeaking) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI正在说话...")
                }
            }
            
            // 显示语音识别状态
            if (userSpeaking) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在说话...")
                }
            }
            
            // 显示语音识别结果
            transcription?.let { text ->
                Text(
                    text = "识别结果: $text",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}