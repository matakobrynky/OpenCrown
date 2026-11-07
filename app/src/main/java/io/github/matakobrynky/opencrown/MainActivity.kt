package io.github.matakobrynky.opencrown

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.matakobrynky.opencrown.data.Repo
import io.github.matakobrynky.opencrown.mk.MkHub
import io.github.matakobrynky.opencrown.ui.BtStatus
import io.github.matakobrynky.opencrown.ui.ControlScreen
import io.github.matakobrynky.opencrown.ui.HomeScreen
import io.github.matakobrynky.opencrown.ui.InspectorScreen
import io.github.matakobrynky.opencrown.ui.MkTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.load(this)
        MkHub.init(this)
        io.github.matakobrynky.opencrown.ui.Ui.load(this)
        setContent {
            MkTheme {
                // status/navigation bar icons follow the app's theme, not just the phone's
                val dark = io.github.matakobrynky.opencrown.ui.Ui.scheme.dark
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
                        navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
                    )
                }
                androidx.compose.material3.Surface(color = io.github.matakobrynky.opencrown.ui.Bg) { AppRoot() }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        MkHub.stopAll() // never leave motors running in the background
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) MkHub.shutdown()
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var btOn by remember { mutableStateOf(adapter?.isEnabled == true) }

    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(perms)
        while (true) { btOn = adapter?.isEnabled == true; delay(1000) }
    }

    val status = BtStatus(
        supported = adapter != null,
        permissionsGranted = granted,
        enabled = btOn,
        advertisingSupported = true,
        fix = {
            if (!granted) permLauncher.launch(perms)
            else if (!btOn) try { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: SecurityException) {}
        },
    )

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var startEditing by rememberSaveable { mutableStateOf(false) }
    val profile = Repo.profiles.firstOrNull { it.id == openId }

    var inspector by rememberSaveable { mutableStateOf(false) }

    if (inspector) {
        BackHandler { inspector = false }
        InspectorScreen { inspector = false }
    } else if (profile != null) {
        BackHandler { openId = null }
        ControlScreen(profile.id, startEditing, status) { openId = null; startEditing = false }
    } else {
        HomeScreen(status, onInspector = { inspector = true }) { id, edit -> openId = id; startEditing = edit }
    }
}
