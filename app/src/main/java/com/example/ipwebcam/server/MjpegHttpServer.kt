package com.example.ipwebcam.server

import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import com.example.ipwebcam.audio.AudioStreamer
import com.example.ipwebcam.camera.CameraStreamManager
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MjpegHttpServer(
    port: Int = 50050,
    private val cameraManager: CameraStreamManager,
    private val audioStreamer: AudioStreamer,
    private val onControlAction: ((action: String, value: String) -> Unit)? = null
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeStreamClients = AtomicInteger(0)

    var onClientCountChanged: ((Int) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parameters

        return when {
            // Web Dashboard
            uri == "/" || uri == "/index.html" -> {
                val html = getDashboardHtml(session.headers["host"] ?: "localhost:50050")
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
            }

            // MJPEG Stream (OpenCV cv2.VideoCapture / Browser / VLC)
            uri == "/video" || uri == "/mjpeg" -> {
                serveMjpegStream()
            }

            // Snapshot Single Frame
            uri == "/shot.jpg" || uri == "/snapshot" -> {
                serveSnapshot()
            }

            // Live Audio Stream (WAV)
            uri == "/audio" || uri == "/audio.wav" -> {
                serveAudioStream()
            }

            // API Control
            uri == "/api/control" -> {
                val action = params["action"]?.firstOrNull() ?: ""
                val value = params["value"]?.firstOrNull() ?: ""
                handleControl(action, value)
            }

            // API Status
            uri == "/api/status" -> {
                val json = getStatusJson()
                val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }
    }

    private fun serveMjpegStream(): Response {
        val stream = MjpegInputStream(cameraManager) {
            val count = activeStreamClients.decrementAndGet()
            notifyClientCount(count)
        }

        activeStreamClients.incrementAndGet()
        notifyClientCount(activeStreamClients.get())

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=--frame",
            stream
        )
        response.addHeader("Cache-Control", "no-cache, private")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun serveSnapshot(): Response {
        val frame = cameraManager.getLatestFrame()
        return if (frame != null) {
            val stream = ByteArrayInputStream(frame)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                stream,
                frame.size.toLong()
            )
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response
        } else {
            newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Kamera belum siap")
        }
    }

    private fun serveAudioStream(): Response {
        if (!audioStreamer.isRunning()) {
            audioStreamer.start()
        }

        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 8192)

        audioStreamer.addClient(pipedOut)

        val response = newChunkedResponse(Response.Status.OK, "audio/wav", object : InputStream() {
            override fun read(): Int = pipedIn.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = pipedIn.read(b, off, len)
            override fun close() {
                audioStreamer.removeClient(pipedOut)
                try { pipedIn.close() } catch (e: Exception) {}
                try { pipedOut.close() } catch (e: Exception) {}
            }
        })
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleControl(action: String, value: String): Response {
        mainHandler.post {
            when (action) {
                "torch" -> {
                    cameraManager.setTorch(value == "on" || value == "true")
                }
                "camera" -> {
                    onControlAction?.invoke("camera", value)
                }
                "quality" -> {
                    value.toIntOrNull()?.let { q ->
                        cameraManager.jpegQuality = q.coerceIn(20, 100)
                    }
                }
                "zoom" -> {
                    value.toFloatOrNull()?.let { z ->
                        cameraManager.setZoom(z)
                    }
                }
            }
            onControlAction?.invoke(action, value)
        }

        val response = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun getStatusJson(): String {
        val isBack = cameraManager.lensFacing == CameraSelector.LENS_FACING_BACK
        return """
            {
                "status": "running",
                "fps": ${cameraManager.currentFps},
                "camera": "${if (isBack) "back" else "front"}",
                "torch": ${cameraManager.isTorchOn},
                "quality": ${cameraManager.jpegQuality},
                "zoom": ${cameraManager.zoomRatio},
                "clients": ${activeStreamClients.get()}
            }
        """.trimIndent()
    }

    private fun notifyClientCount(count: Int) {
        mainHandler.post {
            onClientCountChanged?.invoke(maxOf(0, count))
        }
    }

    /**
     * MjpegInputStream mengalirkan frame JPEG secara terus menerus ke client HTTP multipart.
     */
    private class MjpegInputStream(
        private val cameraManager: CameraStreamManager,
        private val onClose: () -> Unit
    ) : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>(2)
        private val listenerId = UUID.randomUUID().toString()
        private var currentBuffer = ByteArray(0)
        private var currentPos = 0
        private var isClosed = false

        init {
            cameraManager.registerFrameListener(listenerId) { jpeg ->
                if (!isClosed) {
                    val header = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n").toByteArray()
                    val total = ByteArray(header.size + jpeg.size + 2)
                    System.arraycopy(header, 0, total, 0, header.size)
                    System.arraycopy(jpeg, 0, total, header.size, jpeg.size)
                    total[total.size - 2] = '\r'.code.toByte()
                    total[total.size - 1] = '\n'.code.toByte()
                    // Buang frame lama jika queue penuh untuk mencegah lag/delay
                    queue.poll()
                    queue.offer(total)
                }
            }
        }

        override fun read(): Int {
            if (!ensureData()) return -1
            return currentBuffer[currentPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!ensureData()) return -1
            val available = currentBuffer.size - currentPos
            val toRead = minOf(len, available)
            System.arraycopy(currentBuffer, currentPos, b, off, toRead)
            currentPos += toRead
            return toRead
        }

        private fun ensureData(): Boolean {
            if (isClosed) return false
            while (currentPos >= currentBuffer.size) {
                if (isClosed) return false
                val nextChunk = queue.poll(500, TimeUnit.MILLISECONDS)
                if (nextChunk != null) {
                    currentBuffer = nextChunk
                    currentPos = 0
                    return true
                }
            }
            return true
        }

        override fun close() {
            if (!isClosed) {
                isClosed = true
                cameraManager.unregisterFrameListener(listenerId)
                queue.clear()
                onClose()
            }
            super.close()
        }
    }

    /**
     * Dashboard HTML5 Web Client yang elegan & responsif
     */
    private fun getDashboardHtml(host: String): String {
        return """
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android IP Webcam Pro - Dashboard</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;600;700&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-color: #0d1117;
            --surface-color: #161b22;
            --card-color: #1f242c;
            --primary-color: #00d26a;
            --primary-glow: rgba(0, 210, 106, 0.25);
            --secondary-color: #38bdf8;
            --text-main: #f0f6fc;
            --text-muted: #8b949e;
            --border-color: #30363d;
            --danger: #f85149;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            font-family: 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif;
            background-color: var(--bg-color);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }

        header {
            background-color: var(--surface-color);
            border-bottom: 1px solid var(--border-color);
            padding: 14px 24px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .brand {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .brand-icon {
            width: 36px;
            height: 36px;
            background: linear-gradient(135deg, var(--primary-color), var(--secondary-color));
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: bold;
            color: #000;
        }

        .brand h1 {
            font-size: 1.25rem;
            font-weight: 700;
            letter-spacing: -0.5px;
        }

        .badges {
            display: flex;
            gap: 10px;
        }

        .badge {
            background: var(--card-color);
            border: 1px solid var(--border-color);
            padding: 6px 14px;
            border-radius: 20px;
            font-size: 0.85rem;
            font-family: 'JetBrains Mono', monospace;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .badge-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background-color: var(--primary-color);
            box-shadow: 0 0 8px var(--primary-color);
        }

        main {
            flex: 1;
            max-width: 1200px;
            width: 100%;
            margin: 0 auto;
            padding: 24px 16px;
            display: grid;
            grid-template-columns: 1fr 340px;
            gap: 24px;
        }

        @media (max-width: 900px) {
            main {
                grid-template-columns: 1fr;
            }
        }

        .video-container {
            background-color: #000;
            border-radius: 16px;
            border: 1px solid var(--border-color);
            overflow: hidden;
            display: flex;
            align-items: center;
            justify-content: center;
            position: relative;
            aspect-ratio: 16 / 9;
            box-shadow: 0 12px 30px rgba(0,0,0,0.5);
        }

        .video-container img {
            width: 100%;
            height: 100%;
            object-fit: contain;
            display: block;
        }

        .video-overlay {
            position: absolute;
            top: 12px;
            left: 12px;
            background: rgba(13, 17, 23, 0.75);
            backdrop-filter: blur(8px);
            padding: 6px 12px;
            border-radius: 8px;
            font-size: 0.8rem;
            font-family: 'JetBrains Mono', monospace;
            border: 1px solid rgba(255,255,255,0.1);
        }

        .sidebar {
            display: flex;
            flex-direction: column;
            gap: 16px;
        }

        .panel {
            background-color: var(--surface-color);
            border: 1px solid var(--border-color);
            border-radius: 16px;
            padding: 18px;
        }

        .panel h3 {
            font-size: 1rem;
            font-weight: 600;
            margin-bottom: 14px;
            display: flex;
            align-items: center;
            gap: 8px;
            color: var(--text-main);
        }

        .control-group {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }

        .btn-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 10px;
        }

        .btn {
            background-color: var(--card-color);
            border: 1px solid var(--border-color);
            color: var(--text-main);
            padding: 10px 14px;
            border-radius: 10px;
            font-weight: 600;
            font-size: 0.88rem;
            cursor: pointer;
            transition: all 0.2s ease;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
        }

        .btn:hover {
            background-color: #282f3a;
            border-color: var(--secondary-color);
            transform: translateY(-1px);
        }

        .btn-primary {
            background-color: var(--primary-color);
            color: #000;
            border: none;
        }

        .btn-primary:hover {
            background-color: #05e376;
            box-shadow: 0 0 15px var(--primary-glow);
        }

        .slider-control {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }

        .slider-label {
            display: flex;
            justify-content: space-between;
            font-size: 0.85rem;
            color: var(--text-muted);
        }

        .slider-label span:last-child {
            color: var(--primary-color);
            font-family: 'JetBrains Mono', monospace;
            font-weight: bold;
        }

        input[type="range"] {
            width: 100%;
            accent-color: var(--primary-color);
        }

        .url-box {
            background: var(--card-color);
            border: 1px solid var(--border-color);
            padding: 10px 12px;
            border-radius: 10px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.8rem;
            word-break: break-all;
            color: var(--secondary-color);
            cursor: pointer;
            position: relative;
            transition: border-color 0.2s;
        }

        .url-box:hover {
            border-color: var(--secondary-color);
        }

        .url-label {
            font-size: 0.75rem;
            color: var(--text-muted);
            margin-bottom: 4px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        audio {
            width: 100%;
            height: 36px;
            border-radius: 8px;
            outline: none;
        }
    </style>
</head>
<body>
    <header>
        <div class="brand">
            <div class="brand-icon">📹</div>
            <h1>IP Webcam Pro</h1>
        </div>
        <div class="badges">
            <div class="badge">
                <span class="badge-dot"></span>
                <span>LIVE</span>
            </div>
            <div class="badge" id="clientBadge">1 Klien</div>
        </div>
    </header>

    <main>
        <div>
            <div class="video-container">
                <div class="video-overlay" id="fpsDisplay">FPS: 30</div>
                <img src="/video" id="liveStream" alt="Live Camera Stream">
            </div>

            <!-- Audio Player -->
            <div class="panel" style="margin-top: 16px;">
                <h3>🎙️ Live Audio Streaming</h3>
                <audio controls src="/audio" preload="none"></audio>
            </div>
        </div>

        <div class="sidebar">
            <!-- Camera Controls -->
            <div class="panel">
                <h3>🎮 Kontrol Kamera</h3>
                <div class="control-group">
                    <div class="btn-grid">
                        <button class="btn" onclick="sendControl('camera', 'switch')">🔄 Ganti Lensa</button>
                        <button class="btn" id="btnTorch" onclick="toggleTorch()">💡 Flash: OFF</button>
                    </div>

                    <div class="slider-control" style="margin-top: 8px;">
                        <div class="slider-label">
                            <span>Digital Zoom</span>
                            <span id="zoomVal">1.0x</span>
                        </div>
                        <input type="range" min="1.0" max="5.0" step="0.1" value="1.0" oninput="updateZoom(this.value)">
                    </div>

                    <div class="slider-control">
                        <div class="slider-label">
                            <span>Kualitas JPEG</span>
                            <span id="qualVal">80%</span>
                        </div>
                        <input type="range" min="30" max="100" step="5" value="80" onchange="sendControl('quality', this.value); document.getElementById('qualVal').innerText = this.value + '%'">
                    </div>

                    <a href="/shot.jpg" target="_blank" download="snapshot.jpg" class="btn btn-primary" style="text-decoration: none; margin-top: 6px;">
                        📸 Ambil Foto Snapshot
                    </a>
                </div>
            </div>

            <!-- Quick URLs for Developers & OpenCV -->
            <div class="panel">
                <h3>🔗 URL Integrasi</h3>
                <div class="control-group">
                    <div>
                        <div class="url-label">OpenCV / Python Stream:</div>
                        <div class="url-box" onclick="copyText('http://${host}/video')">
                            http://${host}/video
                        </div>
                    </div>
                    <div>
                        <div class="url-label">Single Snapshot (Foto):</div>
                        <div class="url-box" onclick="copyText('http://${host}/shot.jpg')">
                            http://${host}/shot.jpg
                        </div>
                    </div>
                    <div>
                        <div class="url-label">WAV Audio:</div>
                        <div class="url-box" onclick="copyText('http://${host}/audio')">
                            http://${host}/audio
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </main>

    <script>
        let torchOn = false;

        function sendControl(action, value) {
            fetch('/api/control?action=' + action + '&value=' + encodeURIComponent(value))
                .catch(err => console.error(err));
        }

        function toggleTorch() {
            torchOn = !torchOn;
            sendControl('torch', torchOn ? 'on' : 'off');
            document.getElementById('btnTorch').innerText = torchOn ? '💡 Flash: ON' : '💡 Flash: OFF';
            document.getElementById('btnTorch').style.borderColor = torchOn ? '#00d26a' : '';
        }

        function updateZoom(val) {
            document.getElementById('zoomVal').innerText = parseFloat(val).toFixed(1) + 'x';
            sendControl('zoom', val);
        }

        function copyText(text) {
            navigator.clipboard.writeText(text).then(() => {
                alert('Tersalin ke clipboard: ' + text);
            });
        }

        // Status Polling
        setInterval(() => {
            fetch('/api/status')
                .then(r => r.json())
                .then(data => {
                    if (data.fps !== undefined) {
                        document.getElementById('fpsDisplay').innerText = 'FPS: ' + data.fps;
                    }
                    if (data.clients !== undefined) {
                        document.getElementById('clientBadge').innerText = data.clients + ' Klien';
                    }
                })
                .catch(() => {});
        }, 1500);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
