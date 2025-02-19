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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.myapplication.permissions.RequestCameraAndAudioPermissions
import com.example.myapplication.video.CameraManager
import com.example.myapplication.state.ConnectionState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape

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
    
    // 修改状态文本为列表
    var statusLines by remember { mutableStateOf(mutableListOf<String>()) }
    var aiResponseBuffer by remember { mutableStateOf("") }
    
    // 添加音频文件计数器
    var audioFileIndex by remember { mutableStateOf(0) }
    
    // 添加辅助函数来更新状态行
    fun addStatusLine(line: String) {
        statusLines = (statusLines + line).takeLast(5).toMutableList() // 保留最后5行
    }
    
    // 添加辅助函数来更新AI响应行
    fun updateAIResponseLine(text: String) {
        val newLines = if (statusLines.any { it.startsWith("AI响应:") }) {
            // 如果已经有AI响应行，更新它
            statusLines.map { line ->
                if (line.startsWith("AI响应:")) "AI响应: $text" else line
            }
        } else {
            // 如果没有AI响应行，添加新行
            statusLines + "AI响应: $text"
        }.takeLast(5).toMutableList()
        
        statusLines = newLines
    }
    
    val scope = rememberCoroutineScope()
    
    DisposableEffect(Unit) {
        onDispose {
            audioManager.release()
            cameraManager.stopCamera()
            webSocketManager?.disconnect()
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
                    aiResponseBuffer += text
                    updateAIResponseLine(aiResponseBuffer)
                },
                onResponseAudio = { audio ->
                    when (audio) {
                        AIWebSocketManager.AUDIO_START -> {
                            // 开始收集MP3数据
                            audioManager.startMp3Collection()
                        }
                        AIWebSocketManager.AUDIO_END -> {
                            // 完成MP3收集并播放
                            audioManager.finishAndPlayMp3()
                        }
                        else -> {
                            // 添加MP3数据块
                            audioManager.appendMp3Data(audio)
                        }
                    }
                },
                onTurnStart = {
                    isAISpeaking = true
                },
                onTurnEnd = {
                    isAISpeaking = false
                    aiResponseBuffer = ""
                },
                onSpeechStarted = {
                    userSpeaking = true
                    addStatusLine("正在说话...")
                },
                onSpeechStopped = {
                    userSpeaking = false
                    addStatusLine("说话结束")
                },
                onTranscriptionCompleted = { text ->
                    transcription = text
                    addStatusLine("语音识别完成: $text")
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
            statusLines = mutableListOf()
            aiResponseBuffer = ""
        }
    }

    LaunchedEffect(audioManager) {
        audioManager.setOnPlaybackCompleteListener {
            // 在音频播放完成后重置状态行
            statusLines = mutableListOf()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                if (isChatting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        // 从raw资源读取
                                        val cacheFile = File(context.cacheDir, "test.wav")
                                        // 使用当前索引获取音频文件
                                        val resourceId = context.resources.getIdentifier(
                                            "test$audioFileIndex",
                                            "raw",
                                            context.packageName
                                        )
                                        
                                        context.resources.openRawResource(resourceId).use { input ->
                                            FileOutputStream(cacheFile).use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                        
                                        // 从缓存文件读取并发送
                                        val bytes = cacheFile.readBytes()
                                        webSocketManager?.cancelResponse()
                                        webSocketManager?.sendAudioData(bytes)
                                        webSocketManager?.commitAudioBuffer()

                                        var voice = "这是什么东西"
                                        if(audioFileIndex == 0){
                                            voice = "这是什么东西"
                                        }else if(audioFileIndex == 1){
                                            voice = "现在是白天还是晚上"
                                        }else if(audioFileIndex == 2){
                                            voice = "插排是什么颜色的"
                                        }else if(audioFileIndex == 3){
                                            voice = "你看到茶壶了吗"
                                        }else if(audioFileIndex == 4){
                                            voice = "手机是什么型号的"
                                        }else if(audioFileIndex == 5){
                                            voice = "这里有哪些文字"
                                        }else if(audioFileIndex == 6){  
                                            voice = "一共有多少个充电器"
                                        }else if(audioFileIndex == 7){
                                            voice = "你看到了哪些东西"
                                        }
                                        println("发送音频文件 test$audioFileIndex.wav $voice")
                                        // 更新索引，循环使用0-10
                                        audioFileIndex = (audioFileIndex + 1) % 8
                                        
                                        // 删除缓存文件
                                        cacheFile.delete()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "测试音频"
                            )
                        }
                        
                        FloatingActionButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        webSocketManager?.cancelResponse()
                                        webSocketManager?.commitAudioBuffer()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        ) {
                            Text("提交")
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
        
        // 状态指示区域 - 放在底部居中
        if (statusLines.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp) // 距离底部的距离
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    statusLines.forEach { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                text.startsWith("正在说话") -> Color.Green
                                text.startsWith("语音识别完成") -> Color.Yellow
                                text.startsWith("AI响应") -> Color.Cyan
                                else -> Color.White
                            }
                        )
                    }
                }
            }
        }
    }
}