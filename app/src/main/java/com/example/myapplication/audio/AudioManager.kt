package com.example.myapplication.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AudioManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // WAV 格式参数
    private val sampleRate = 16000 // 采样率
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // 单声道
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16位PCM
    private val bytesPerSample = 2 // 16位 = 2字节
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    
    private var onAudioData: ((ByteArray) -> Unit)? = null
    
    // 添加MP3播放相关的变量
    private var mediaPlayer: MediaPlayer? = null
    private val mp3Buffer = ByteArrayOutputStream()
    private var isCollectingMp3 = false
    
    init {
        initAudioTrack()
    }
    
    // 添加WAV头部
    private fun createWavHeader(pcmDataSize: Int): ByteArray {
        val headerSize = 44
        val totalDataSize = headerSize + pcmDataSize
        
        val header = ByteBuffer.allocate(headerSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF chunk
            put("RIFF".toByteArray()) // ChunkID
            putInt(totalDataSize - 8) // ChunkSize
            put("WAVE".toByteArray()) // Format
            
            // fmt chunk
            put("fmt ".toByteArray()) // Subchunk1ID
            putInt(16) // Subchunk1Size (16 for PCM)
            putShort(1) // AudioFormat (1 for PCM)
            putShort(1) // NumChannels (1 for mono)
            putInt(sampleRate) // SampleRate
            putInt(sampleRate * bytesPerSample) // ByteRate
            putShort((1 * bytesPerSample).toShort()) // BlockAlign
            putShort(16) // BitsPerSample
            
            // data chunk
            put("data".toByteArray()) // Subchunk2ID
            putInt(pcmDataSize) // Subchunk2Size
        }
        
        return header.array()
    }
    
    // 将PCM数据转换为WAV格式
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val wavHeader = createWavHeader(pcmData.size)
        return wavHeader + pcmData
    }
    
    private fun initAudioTrack() {
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }
    
    @SuppressLint("MissingPermission")
    fun startRecording(onData: (ByteArray) -> Unit) {
        onAudioData = onData
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        
        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val pcmStream = ByteArrayOutputStream()
            audioRecord?.startRecording()
            
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    // 累积PCM数据
                    pcmStream.write(buffer, 0, readSize)
                    
                    // 转换为WAV格式并发送
                    val wavData = pcmToWav(pcmStream.toByteArray())
                    onAudioData?.invoke(wavData)
                    
                    // 清空PCM缓冲区，为下一次录制准备
                    pcmStream.reset()
                }
            }
            
            pcmStream.close()
        }
    }
    
    fun stopRecording() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        onAudioData = null
    }
    
    fun playAudio(audioData: ByteArray) {
        audioTrack?.let { track ->
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(audioData, 0, audioData.size)
        }
    }
    
    // 开始收集MP3数据块
    fun startMp3Collection() {
        mp3Buffer.reset()
        isCollectingMp3 = true
    }
    
    // 添加MP3数据块
    fun appendMp3Data(data: ByteArray) {
        if (isCollectingMp3) {
            mp3Buffer.write(data)
        }
    }
    
    // 完成MP3收集并播放
    fun finishAndPlayMp3() {
        if (!isCollectingMp3) return
        
        isCollectingMp3 = false
        val mp3Data = mp3Buffer.toByteArray()
        mp3Buffer.reset()
        
        // 创建临时文件
        val tempFile = File(context.cacheDir, "temp_${UUID.randomUUID()}.mp3")
        FileOutputStream(tempFile).use { it.write(mp3Data) }
        
        // 释放之前的MediaPlayer
        mediaPlayer?.release()
        
        // 创建新的MediaPlayer并播放
        mediaPlayer = MediaPlayer().apply {
            setDataSource(tempFile.path)
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            prepare()
            start()
            
            // 播放完成后删除临时文件
            setOnCompletionListener {
                tempFile.delete()
                release()
                mediaPlayer = null
            }
        }
    }
    
     fun release() {
        stopRecording()
        audioTrack?.release()
        audioTrack = null
        mediaPlayer?.release()
        mediaPlayer = null
        mp3Buffer.close()
    }
} 