package com.advancedvideoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.advancedvideoplayer.data.TempFiles
import com.advancedvideoplayer.data.UriInfo
import com.advancedvideoplayer.iso.DiscScan
import com.advancedvideoplayer.iso.DiscScanner
import com.advancedvideoplayer.iso.IsoPlayback
import com.advancedvideoplayer.iso.IsoSource
import com.advancedvideoplayer.ui.IsoPickerDialog
import com.advancedvideoplayer.ui.FileAccess
import com.advancedvideoplayer.ui.PlayerScreen
import com.advancedvideoplayer.ui.theme.AdvancedVideoPlayerTheme

class MainActivity : ComponentActivity() {

    private var currentUri by mutableStateOf<Uri?>(null)
    private var hasFullAccess by mutableStateOf(false)

    // Disc images (.iso): the image stays open while one of its titles plays.
    private var isoSource: IsoSource? = null
    private var pendingIso: IsoSource? = null
    private var isoChoices by mutableStateOf<DiscScan?>(null)

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Persisting the grant is a convenience; some providers refuse it and that must not crash.
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            } catch (_: UnsupportedOperationException) {
            }
            openMedia(it)
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Leftovers of earlier runs (crash / process death). Screen-on is handled by PlayerView
        // only while actually playing, instead of a permanent FLAG_KEEP_SCREEN_ON.
        TempFiles.sweep(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            // Hide the status + navigation bars while a video is open (swipe from the edge to peek).
            LaunchedEffect(currentUri) { updateSystemBars() }
            AdvancedVideoPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PlayerScreen(
                        videoUri = currentUri,
                        onOpenFile = { openFilePicker() },
                        onOpenUrl = { openUrl(it) },
                        hasFullAccess = hasFullAccess,
                        onGrantAccess = { requestFullAccess() },
                        onOpenUri = { openMedia(it) }
                    )
                    isoChoices?.let { scan ->
                        IsoPickerDialog(
                            scan = scan,
                            onPick = { title ->
                                val src = pendingIso
                                if (src != null) {
                                    closeIso()
                                    isoSource = src
                                    pendingIso = null
                                    currentUri = IsoPlayback.register(src, title.extents, title.fileName)
                                }
                                isoChoices = null
                            },
                            onDismiss = { dismissIsoChoices() }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Bars come back after dialogs, the app switcher or the notification shade: hide them again.
        if (hasFocus) updateSystemBars()
    }

    private fun updateSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (currentUri != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just flipped the switch in Settings.
        hasFullAccess = FileAccess.hasFullAccess(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) {
            TempFiles.sweep(applicationContext)
            closeIso()
            dismissIsoChoices()
        }
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openMedia(it) }
        }
    }

    private fun openFilePicker() {
        try {
            pickMedia.launch(arrayOf("*/*")) // MKV/TS/DTS files are often mis-typed; never grey them out
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(raw: String) {
        val text = raw.trim()
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = Uri.parse(withScheme)
        when (uri.scheme?.lowercase()) {
            "http", "https", "rtsp", "rtsps" -> if (!uri.host.isNullOrBlank()) openMedia(uri)
            else -> Toast.makeText(this, "Enter an http(s):// or rtsp:// link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android 11+: opens the "All files access" switch for this app; below: runtime read permission. */
    private fun requestFullAccess() {
        val intent = FileAccess.settingsIntent(this)
        if (intent != null) {
            try {
                startActivity(intent)
                Toast.makeText(this, "Turn on “Allow access to manage all files”, then come back", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings › Apps › Special access › All files access", Toast.LENGTH_LONG).show()
            }
        } else {
            requestStoragePermission()
        }
    }

    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermission.launch(permissions)
    }

    fun hasStoragePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Opens a file / stream; disc images (.iso) go through a title picker first. */
    private fun openMedia(uri: Uri) {
        val name = if (UriInfo.isNetwork(uri)) "" else UriInfo.displayName(this, uri).lowercase()
        if (name.endsWith(".iso") || name.endsWith(".img")) {
            scanDisc(uri)
        } else {
            closeIso()
            currentUri = uri
        }
    }

    private fun scanDisc(uri: Uri) {
        Toast.makeText(this, "Reading disc image…", Toast.LENGTH_SHORT).show()
        Thread {
            var io: IsoSource? = null
            val scan = try {
                io = IsoSource.open(applicationContext, uri)
                DiscScanner.scan(io)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (scan != null && io != null && !isFinishing) {
                    dismissIsoChoices()
                    pendingIso = io
                    isoChoices = scan
                } else {
                    try { io?.close() } catch (_: Throwable) { }
                    Toast.makeText(
                        this,
                        "Can't read this disc image (only unencrypted Blu-ray / DVD-Video ISOs work)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun dismissIsoChoices() {
        isoChoices = null
        pendingIso?.close()
        pendingIso = null
    }

    private fun closeIso() {
        isoSource?.let {
            IsoPlayback.release(it)
            it.close()
        }
        isoSource = null
    }
}
