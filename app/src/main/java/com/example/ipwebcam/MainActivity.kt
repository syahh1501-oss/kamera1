package com.example.ipwebcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

        // Jaga layar tetap menyala selama aplikasi dibuka
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraManager = CameraStreamManager(this, this)
        audioStreamer = AudioStreamer()

        setupListeners()
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
                binding.tvStandby.visibility = android.view.View.GONE
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
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

            // Start Foreground Service
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
