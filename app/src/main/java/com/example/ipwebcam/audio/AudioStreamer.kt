package com.example.ipwebcam.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamer {

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
        4096
    )

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    // Daftar stream client yang sedang mendengarkan audio
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRecording.get()) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread({
                val buffer = ByteArray(bufferSize)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        for (out in clientStreams) {
                            try {
                                out.write(buffer, 0, read)
                                out.flush()
                            } catch (e: Exception) {
                                // Client putus
                                clientStreams.remove(out)
                            }
                        }
                    }
                }
            }, "AudioStreamerThread").apply { start() }

        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    @Synchronized
    fun stop() {
        isRecording.set(false)
        try {
            recordThread?.interrupt()
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        clientStreams.clear()
    }

    fun addClient(outputStream: OutputStream) {
        // Tulis WAV header terlebih dahulu agar pemutar audio (browser / VLC) mengenalinya sebagai WAV
        val header = createWavHeader(sampleRate, 1, 16)
        try {
            outputStream.write(header)
            outputStream.flush()
            clientStreams.add(outputStream)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeClient(outputStream: OutputStream) {
        clientStreams.remove(outputStream)
    }

    fun isRunning(): Boolean = isRecording.get()

    /**
     * Membuat WAV header untuk streaming live (chunk size diatur besar/tidak terbatas).
     */
    private fun createWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = 0x7ffffff0 // Panjang data audio besar untuk streaming tak hingga
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1.toShort()) // AudioFormat 1 = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * (bitsPerSample / 8)).toShort()) // BlockAlign
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(totalAudioLen)
        return header.array()
    }
}
