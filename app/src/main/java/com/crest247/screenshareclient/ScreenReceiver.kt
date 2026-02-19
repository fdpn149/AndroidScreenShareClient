package com.crest247.screenshareclient

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenReceiver(
    private val onAspectRatioChanged: (Float) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit = {} // New callback for status messages
) {

    private var socket: Socket? = null
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private val TAG = "ScreenReceiver"
    private var receiverThread: Thread? = null

    // Track if we ever successfully connected to determine retry behavior
    private var hasConnectedOnce = false

    fun start(surface: Surface, host: String, port: Int) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "ScreenReceiver already running")
            return
        }

        hasConnectedOnce = false

        receiverThread = Thread {
            Log.d(TAG, "Receiver Thread Started: $host:$port")
            
            while (isRunning.get()) {
                try {
                    connectAndStream(surface, host, port)
                } catch (e: Exception) {
                    Log.e(TAG, "Connection Loop Error: ${e.message}")
                    
                    // Cleanup resources immediately on error
                    cleanupSocket()
                    cleanupDecoder()

                    if (isRunning.get()) {
                        if (hasConnectedOnce) {
                            // If we were previously connected, try to reconnect automatically
                            onMessage("Connection lost. Reconnecting...")
                            try { Thread.sleep(2000) } catch (ignore: InterruptedException) { break }
                        } else {
                            // If we never connected, fail immediately to let user check IP
                            onMessage("Connection failed: ${e.message}")
                            isRunning.set(false)
                            onConnectionStateChanged(false)
                            break
                        }
                    }
                }
            }
            // Final cleanup
            cleanupSocket()
            cleanupDecoder()
            Log.d(TAG, "Receiver Thread Exited")
        }
        receiverThread?.start()
    }

    private fun connectAndStream(surface: Surface, host: String, port: Int) {
        Log.d(TAG, "Connecting to $host:$port...")
        socket = Socket()
        socket?.connect(InetSocketAddress(host, port), 5000)
        
        // Optimize for latency
        socket?.tcpNoDelay = true
        socket?.receiveBufferSize = 64 * 1024 // 64KB receive buffer

        
        val inputStream = socket?.getInputStream() ?: throw Exception("Stream is null")
        Log.d(TAG, "Socket Connected")
        onConnectionStateChanged(true)
        hasConnectedOnce = true // Mark as valid session

        // Handshake: Width (4) + Height (4)
        val header = ByteArray(8)
        readFully(inputStream, header)
        val buffer = ByteBuffer.wrap(header)
        val width = buffer.getInt()
        val height = buffer.getInt()

        Log.d(TAG, "Stream Info: ${width}x${height}")
        if (width <= 0 || height <= 0) throw Exception("Invalid dimensions: ${width}x${height}")

        onAspectRatioChanged(width.toFloat() / height.toFloat())

        // Config Decoder
        try {
            cleanupDecoder() // Ensure any previous codec is released
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            Log.d(TAG, "Decoder Started")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder Init Failed", e)
            throw e
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val lengthBytes = ByteArray(4)

        // Decode Loop
        while (isRunning.get()) {
            // Read Frame Length
            readFully(inputStream, lengthBytes)
            val length = ByteBuffer.wrap(lengthBytes).getInt()

            if (length > 20_000_000 || length < 0) { // arbitrary sanity check (20MB frame)
                 throw Exception("Invalid frame length: $length")
            }

            // Read Data
            val data = ByteArray(length)
            readFully(inputStream, data)

            // Feed to Decoder
            try {
                // Wait indefinitely (or reasonably long) for input buffer
                val inputBufferIndex = mediaCodec?.dequeueInputBuffer(100000) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(data)
                    mediaCodec?.queueInputBuffer(inputBufferIndex, 0, length, 0, 0)
                } else {
                    Log.w(TAG, "Decoder Input Buffer Timeout - Dropping Frame")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoder Input Error", e)
                throw e
            }

            // Render Output
            try {
                var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                while (outputBufferIndex >= 0) {
                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, true)
                    outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Decoder Output Error", e)
                 // Consider if this is fatal
            }
        }
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inputStream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw Exception("End of stream")
            offset += read
        }
    }

    private fun cleanupSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            socket = null
        }
    }
    
    private fun cleanupDecoder() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            mediaCodec = null
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping Receiver...")
        isRunning.set(false)
        receiverThread?.interrupt()
        cleanupSocket()
        cleanupDecoder()
        onConnectionStateChanged(false)
    }
}
