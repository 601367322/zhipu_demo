package com.example.myapplication.network

import com.example.myapplication.config.ApiConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.time.Instant
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

class AIWebSocketManager(
    private val onMessageReceived: (ByteArray) -> Unit,
    private val onConnectionEstablished: () -> Unit,
    private val onConnectionError: (String) -> Unit,
    private val onResponseText: (String) -> Unit = {},
    private val onResponseAudio: (ByteArray) -> Unit = {},
    private val onTurnStart: () -> Unit = {},
    private val onTurnEnd: () -> Unit = {},
    private val onSpeechStarted: () -> Unit = {},
    private val onSpeechStopped: () -> Unit = {},
    private val onTranscriptionCompleted: (String) -> Unit = {},
    private val onResponseCreated: (String) -> Unit = {}
) {
    private var webSocket: WebSocketClient? = null
    private var isActiveDisconnect = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var lastVideoFrameTime = 0L  // 添加这一行来跟踪上一帧的发送时间
    
    companion object {
        val AUDIO_START = ByteArray(0)  // 空数组表示开始
        val AUDIO_END = ByteArray(1) { 0 }  // 长度为1的数组表示结束
    }
    
    fun connect() {
        isActiveDisconnect = false
        val uri = URI(ApiConfig.WS_URL)
        
        val headers = HashMap<String, String>().apply {
            put("Authorization", "Bearer ${ApiConfig.API_KEY}")
        }
        
        webSocket = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                onConnectionEstablished()
                sendInitMessage()
            }
            
            override fun onMessage(message: String?) {
                message?.let { handleServerMessage(it) }
            }
            
            override fun onMessage(bytes: ByteBuffer?) {
                bytes?.let {
                    onMessageReceived(it.array())
                }
            }
            
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!isActiveDisconnect && code != 1000) {
                    println("WebSocket closed unexpectedly: $code $reason, attempting to reconnect...")
                    reconnect()
                }
                onConnectionError("连接关闭: $code $reason")
            }
            
            override fun onError(ex: Exception?) {
                if (!isActiveDisconnect) {
                    println("WebSocket error: ${ex?.message}, attempting to reconnect...")
                    reconnect()
                }
                onConnectionError("连接错误: ${ex?.message}")
            }
        }
        
        webSocket?.connect()
    }
    
    private fun safeSend(message: String) {
        try {
            webSocket?.let { ws ->
                if (ws.isOpen) {
                    if(!message.startsWith("{\"type\":\"input_audio_buffer.append")) {
                        println(message)
                    }else{
//                        println("input_audio_buffer.append")
                    }
                    ws.send(message)
                } else {
                    println("WebSocket is not open, message not sent")
                }
            }
        } catch (e: Exception) {
            println("Error sending message: ${e.message}")
            onConnectionError("发送消息失败: ${e.message}")
        }
    }
    
    private fun sendInitMessage() {
        val initMessage = """
            {
                "event_id": "",
                "type": "session.update",
                "session": {
                    "input_audio_format": "wav",
                    "output_audio_format": "mp3",
                    "instructions": "",
                    "turn_detection": {
                        "type": "server_vad"
                    },
                    "beta_fields": {
                        "chat_mode": "video_passive",
                        "tts_source": "e2e",
                        "auto_search": true
                    },
                    "tools": [
                        {
                            "type": "function",
                            "name": "search_engine",
                            "description": "基于给定的查询执行通用搜索",
                            "parameters": {
                                "type": "object",
                                "properties": {
                                    "q": {
                                        "type": "string",
                                        "description": "搜索查询"
                                    }
                                },
                                "required": [
                                    "q"
                                ]
                            }
                        }
                    ]
                }
            }
        """.trimIndent()
        safeSend(initMessage)
    }

    fun sendAudioData(audioData: ByteArray) {
        val audioMessage = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.getEncoder().encodeToString(audioData))
            put("client_timestamp", System.currentTimeMillis())
        }.toString()
        
        safeSend(audioMessage)
    }
    
    fun sendVideoFrame(videoFrame: ByteArray) {
        val currentTime = System.currentTimeMillis()
        // 检查是否距离上一帧发送已经过去了至少500毫秒（1秒2帧）
        if (currentTime - lastVideoFrameTime < 500) {
            return
        }
        
        val videoMessage = JSONObject().apply {
            put("type", "input_audio_buffer.append_video_frame")
            put("video_frame", Base64.getEncoder().encodeToString(videoFrame))
            put("client_timestamp", currentTime)
        }.toString()
        
        lastVideoFrameTime = currentTime
        safeSend(videoMessage)
    }

    fun commitAudioBuffer() {
        val commitMessage = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
            put("client_timestamp", System.currentTimeMillis())
        }.toString()
        
        safeSend(commitMessage)
    }

    fun createConversationItem(functionOutput: String, eventId: String = "evt_${System.currentTimeMillis()}") {
        val itemMessage = JSONObject().apply {
            put("event_id", eventId)
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "function_call_output")
                put("output", functionOutput)
            })
        }.toString()

        safeSend(itemMessage)
    }
    
    fun cancelResponse() {
        val cancelMessage = JSONObject().apply {
            put("type", "response.cancel")
            put("client_timestamp", System.currentTimeMillis())
        }.toString()
        
        safeSend(cancelMessage)
    }
    
    fun disconnect() {
        isActiveDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close()
        webSocket = null
        scope.cancel()
    }
    
    private fun reconnect() {
        reconnectJob?.cancel()
        
        reconnectJob = scope.launch {
            delay(1000)
            withContext(Dispatchers.Main) {
                webSocket = null
                connect()
            }
        }
    }
    
    private fun handleServerMessage(message: String) {
        try {
            val jsonMessage = JSONObject(message)
            println(jsonMessage)
            
            when (jsonMessage.optString("type")) {
                "input_audio_buffer.speech_started" -> {
                    onSpeechStarted()
                }
                
                "input_audio_buffer.speech_stopped" -> {
                    onSpeechStopped()
                }
                
                "input_audio_buffer.committed" -> {
                    // 音频缓冲区已提交，可以开始新的录制
                }
                
                "conversation.created" -> {
                    // 新会话已创建
                    val conversationId = jsonMessage
                        .optJSONObject("conversation")
                        ?.optString("id")
                }
                
                "conversation.item.input_audio_transcription.completed" -> {
                    // 语音识别完成
                    val transcript = jsonMessage.optString("transcript", "")
                    onTranscriptionCompleted(transcript)
                }
                
                "conversation.item.created" -> {
                    // 会话项已创建
                }
                
                "response.created" -> {
                    onResponseCreated(jsonMessage.optJSONObject("response")?.optString("id") ?: "")
                    // 通知开始收集MP3数据
                    onResponseAudio(AUDIO_START)
                }
                
                "response.audio_transcript.delta" -> {
                    // AI文本响应片段
                    val delta = jsonMessage.optString("delta", "")
                    onResponseText(delta)
                }
                
                "response.audio_transcript.done" -> {
                    // AI文本响应完成
                    val transcript = jsonMessage.optString("transcript", "")
                    onResponseText("\n") // 添加换行符表示完成
                }
                
                "response.audio.delta" -> {
                    val audioData = Base64.getDecoder()
                        .decode(jsonMessage.optString("delta", ""))
                    onResponseAudio(audioData)
                }
                
                "response.audio.done" -> {
                    // 通知MP3数据接收完成
                    onResponseAudio(AUDIO_END)
                }
                
                "response.done" -> {
                    // 整个响应完成
                    onTurnEnd()
                }
                
                "error" -> {
                    val errorMessage = jsonMessage.optString("error", "Unknown error")
                    onConnectionError(errorMessage)
                }
            }
        } catch (e: Exception) {
            println("Error parsing message: ${e.message}")
        }
    }

} 