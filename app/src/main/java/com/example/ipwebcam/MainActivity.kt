package com.example.ipwebcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ipwebcam.audio.AudioStreamer
import com.example.ipwebcam.camera.CameraStreamManager
import com.example.ipwebcam.databinding.ActivityMainBinding
import com.example.ipwebcam.server.MjpegHttpServer
import com.example.ipwebcam.service.WebcamService
import com.example.ipwebcam.utils.NetworkUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraManager: CameraStreamManager
    private lateinit var audioStreamer: AudioStreamer
    private var httpServer: MjpegHttpServer? = null

    private var isServerRunning = false
    private var isAudioEnabled = true
    private val port = 8080

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted) {
            initCamera()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Jaga layar tetap menyala selama aplikasi dibuka agar stream tidak terhenti
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraManager = CameraStreamManager(this, this)
        audioStreamer = AudioStreamer()

        setupListeners()
        setupBlackScreenMode()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            initCamera()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun initCamera() {
        cameraManager.startCamera(binding.previewView) {
            runOnUiThread {
                binding.tvStandby.visibility = View.GONE
            }
        }

        cameraManager.onFpsUpdated = { fps ->
            runOnUiThread {
                binding.tvFps.text = "$fps FPS"
            }
        }
    }

    private fun setupListeners() {
        // Toggle Server Button
        binding.btnToggleServer.setOnClickListener {
            if (isServerRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        // Switch Camera (Front/Back)
        binding.btnFlipCamera.setOnClickListener {
            cameraManager.switchCamera(binding.previewView)
            updateTorchButtonState()
        }

        // Flash / Torch Toggle
        binding.btnToggleTorch.setOnClickListener {
            val newState = !cameraManager.isTorchOn
            cameraManager.setTorch(newState) { success ->
                if (success) {
                    updateTorchButtonState()
                } else {
                    Toast.makeText(this, "Torch tidak tersedia di kamera depan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Audio Stream Toggle
        binding.btnToggleAudio.setOnClickListener {
            isAudioEnabled = !isAudioEnabled
            if (isAudioEnabled) {
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic)
                binding.btnToggleAudio.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                Toast.makeText(this, "Audio Streaming Aktif", Toast.LENGTH_SHORT).show()
            } else {
                audioStreamer.stop()
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic_off)
                binding.btnToggleAudio.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
                Toast.makeText(this, "Audio Streaming Dimatikan", Toast.LENGTH_SHORT).show()
            }
        }

        // Quality Slider
        binding.sliderQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val q = value.toInt()
                cameraManager.jpegQuality = q
                binding.tvQualityValue.text = "$q%"
            }
        }

        // Copy URL Button
        binding.btnCopyUrl.setOnClickListener {
            val url = binding.tvStreamUrl.text.toString()
            copyToClipboard(url)
        }

        // Tap URL untuk melihat daftar IP jaringan yang tersedia
        binding.urlContainer.setOnClickListener {
            showIpListDialog()
        }
    }

    /**
     * Mode Layar Hitam (Screen Saver):
     * Menggelapkan layar hingga kecerahan minimum (0.01) untuk menghemat baterai & mencegah panas,
     * tetapi Android tetap menjalankan kamera & server 100% tanpa di-kill oleh OS.
     */
    private fun setupBlackScreenMode() {
        binding.btnBlackScreen.setOnClickListener {
            binding.blackScreenOverlay.visibility = View.VISIBLE
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            Toast.makeText(this, "Mode layar gelap aktif. Ketuk layar untuk kembali.", Toast.LENGTH_SHORT).show()
        }

        binding.blackScreenOverlay.setOnClickListener {
            binding.blackScreenOverlay.visibility = View.GONE
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    private fun showIpListDialog() {
        val ips = NetworkUtils.getAllIpAddresses()
        if (ips.isEmpty()) {
            Toast.makeText(this, "Tidak ada koneksi jaringan lokal terdeteksi", Toast.LENGTH_SHORT).show()
            return
        }

        val items = ips.map { "${it.type}: http://${it.ip}:$port" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pilih / Salin Alamat IP")
            .setItems(items) { _, which ->
                val selected = "http://${ips[which].ip}:$port"
                binding.tvStreamUrl.text = selected
                binding.tvOpencvHint.text = "OpenCV: cv2.VideoCapture('$selected/video')"
                copyToClipboard(selected)
            }
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Tips Koneksi") { _, _ ->
                showTroubleshootDialog()
            }
            .show()
    }

    private fun showTroubleshootDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tidak Bisa Diakses dari Laptop?")
            .setMessage(
                "Penyebab paling umum:\n\n" +
                "1. AP Isolation pada Router Wi-Fi:\n" +
                "Banyak router Wi-Fi kantor/kos/IndiHome memblokir komunikasi antar perangkat.\n\n" +
                "SOLUSI TERBAIK:\n" +
                "Nyalakan Hotspot Pribadi di HP ini, lalu sambungkan Laptop ke Hotspot HP tersebut.\n\n" +
                "2. Windows Firewall di Laptop:\n" +
                "Pastikan port 8080 tidak diblokir di Windows Defender Firewall pada Laptop."
            )
            .setPositiveButton("Mengerti", null)
            .show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun startServer() {
        val ip = NetworkUtils.getLocalIpAddress(this)
        val baseUrl = "http://$ip:$port"

        try {
            httpServer = MjpegHttpServer(
                port = port,
                cameraManager = cameraManager,
                audioStreamer = audioStreamer,
                onControlAction = { action, value ->
                    runOnUiThread {
                        when (action) {
                            "camera" -> {
                                cameraManager.switchCamera(binding.previewView)
                                updateTorchButtonState()
                            }
                            "torch" -> updateTorchButtonState()
                            "quality" -> {
                                value.toIntOrNull()?.let { q ->
                                    binding.sliderQuality.value = q.toFloat()
                                    binding.tvQualityValue.text = "$q%"
                                }
                            }
                        }
                    }
                }
            ).apply {
                onClientCountChanged = { count ->
                    runOnUiThread {
                        binding.tvClients.text = "$count Clients"
                    }
                }
                start()
            }

            isServerRunning = true

            // Update UI
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.success))
            binding.tvStatus.text = getString(R.string.server_running)
            binding.tvStreamUrl.text = baseUrl
            binding.tvOpencvHint.text = "OpenCV: cv2.VideoCapture('$baseUrl/video')"

            binding.btnToggleServer.text = getString(R.string.stop_server)
            binding.btnToggleServer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
            binding.btnToggleServer.setIconResource(R.drawable.ic_videocam_off)

            // Start Foreground Service (dengan WakeLock & WifiLock)
            WebcamService.startService(this, baseUrl)

            Toast.makeText(this, "Server aktif di $baseUrl", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal memulai server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        try {
            httpServer?.stop()
            httpServer = null
            audioStreamer.stop()
            WebcamService.stopService(this)

            isServerRunning = false

            // Update UI
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
            binding.tvStatus.text = getString(R.string.server_stopped)
            binding.tvClients.text = "0 Clients"

            binding.btnToggleServer.text = getString(R.string.start_server)
            binding.btnToggleServer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            binding.btnToggleServer.setIconResource(R.drawable.ic_videocam)

            Toast.makeText(this, "Server dihentikan", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTorchButtonState() {
        val isTorch = cameraManager.isTorchOn
        binding.btnToggleTorch.setImageResource(if (isTorch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        binding.btnToggleTorch.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isTorch) R.color.warning else R.color.text_primary)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        cameraManager.stop()
    }
}
