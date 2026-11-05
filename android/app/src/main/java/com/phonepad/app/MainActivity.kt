package com.phonepad.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonepad.app.hid.HidGamepadService
import com.phonepad.app.hid.HidStatus
import com.phonepad.app.ui.GamepadView

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var hostText: TextView
    private lateinit var hintText: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var gamepad: GamepadView
    private lateinit var pairingPanel: View

    private var service: HidGamepadService? = null
    private var bound = false
    private var pendingStart = false
    private var permissionAskedOnce = false
    private var shownStatus: HidStatus = HidStatus.Idle

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            maybeStartService()
        } else {
            permissionAskedOnce = true
            applyStatus(HidStatus.PermissionRequired)
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* user may cancel; status unchanged */ }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (bluetoothAdapter()?.isEnabled == true) maybeStartService()
        else applyStatus(HidStatus.BluetoothOff)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as HidGamepadService.LocalBinder).getService()
            service = svc
            bound = true
            svc.addStatusListener(statusListener)
            gamepad.listener = GamepadView.Listener { state -> svc.updateState(state) }
            // Start only if a start was requested before bind completed (or auto-start
            // when permissions + BT are already OK). Service de-duplicates concurrent starts.
            if (pendingStart || (hasBtPermissions() && bluetoothAdapter()?.isEnabled == true)) {
                pendingStart = false
                maybeStartService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeStatusListener(statusListener)
            service = null
            bound = false
        }
    }

    private val statusListener: (HidStatus) -> Unit = { status ->
        runOnUiThread { applyStatus(status) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        hostText = findViewById(R.id.hostText)
        hintText = findViewById(R.id.hintText)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        gamepad = findViewById(R.id.gamepad)
        pairingPanel = findViewById(R.id.pairingPanel)

        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.setOnClickListener { onSecondary() }

        applyStatus(HidStatus.Idle)
        ensurePermissionsThenStart()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, HidGamepadService::class.java).also { intent ->
            // Bind first so UI can talk to service even before FGS start
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        gamepad.resetToNeutral()
        service?.sendNeutral()
        if (bound) {
            service?.removeStatusListener(statusListener)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            gamepad.resetToNeutral()
            service?.sendNeutral()
        }
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            // CONNECT is needed for registerApp/connect/sendReport; ADVERTISE for discoverable.
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 9–11: BLUETOOTH/BLUETOOTH_ADMIN are install-time permissions.
            // Location is NOT needed to act as a HID device, so we no longer block on it.
            emptyList()
        }

    private fun ensurePermissionsThenStart() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            applyStatus(HidStatus.PermissionRequired)
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            maybeStartService()
        }
    }

    private fun maybeStartService() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            applyStatus(HidStatus.Unsupported)
            return
        }
        if (!hasBtPermissions()) {
            applyStatus(HidStatus.PermissionRequired)
            return
        }
        if (!adapter.isEnabled) {
            applyStatus(HidStatus.BluetoothOff)
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, HidGamepadService::class.java))
        } catch (t: Throwable) {
            applyStatus(HidStatus.Error("Could not start gamepad service: ${t.javaClass.simpleName}: ${t.message}"))
            return
        }
        val svc = service
        if (svc != null) {
            svc.startHid()
        } else {
            pendingStart = true
            applyStatus(HidStatus.Registering("Starting gamepad service…"))
        }
    }

    private fun onPrimary() {
        when (shownStatus) {
            is HidStatus.PermissionRequired -> {
                if (Build.VERSION.SDK_INT >= 31 && requiredPermissions().any { !shouldShowRequestPermissionRationale(it) } &&
                    !hasBtPermissions() && permissionAskedOnce
                ) {
                    // Permanently denied: send to app settings.
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)))
                } else {
                    ensurePermissionsThenStart()
                }
            }
            is HidStatus.BluetoothOff -> {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            is HidStatus.Idle, is HidStatus.Error -> maybeStartService()
            is HidStatus.Ready -> requestDiscoverable()
            is HidStatus.Connected -> service?.disconnectHost()
            is HidStatus.Registering -> { /* wait */ }
            is HidStatus.Unsupported -> {
                Toast.makeText(this, R.string.status_no_support, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onSecondary() {
        when (shownStatus) {
            is HidStatus.Ready, is HidStatus.Connected, is HidStatus.Registering -> {
                service?.stopHid()
                applyStatus(HidStatus.Idle)
            }
            else -> requestDiscoverable()
        }
    }

    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        try {
            discoverableLauncher.launch(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Cannot request discoverable: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyStatus(status: HidStatus) {
        shownStatus = status
        hostText.text = getString(R.string.host_none)
        pairingPanel.visibility = View.VISIBLE
        when (status) {
            is HidStatus.Unsupported -> {
                statusText.text = getString(R.string.status_no_support)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.err))
                btnPrimary.text = getString(R.string.btn_register)
                btnPrimary.isEnabled = false
                btnSecondary.isEnabled = false
            }
            is HidStatus.PermissionRequired -> {
                statusText.text = getString(R.string.status_need_permission)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warn))
                btnPrimary.text = getString(R.string.btn_grant)
                btnPrimary.isEnabled = true
                btnSecondary.isEnabled = false
            }
            is HidStatus.BluetoothOff -> {
                statusText.text = getString(R.string.status_bt_off)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warn))
                btnPrimary.text = "Enable Bluetooth"
                btnPrimary.isEnabled = true
                btnSecondary.isEnabled = false
            }
            is HidStatus.Idle -> {
                statusText.text = getString(R.string.status_idle)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                btnPrimary.text = getString(R.string.btn_register)
                btnPrimary.isEnabled = true
                btnSecondary.text = getString(R.string.btn_discoverable)
                btnSecondary.isEnabled = true
            }
            is HidStatus.Registering -> {
                statusText.text = status.step.ifBlank { getString(R.string.status_registering) }
                statusText.setTextColor(ContextCompat.getColor(this, R.color.warn))
                btnPrimary.isEnabled = false
                btnSecondary.isEnabled = false
            }
            is HidStatus.Ready -> {
                statusText.text = getString(R.string.status_ready)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.ok))
                btnPrimary.text = getString(R.string.btn_discoverable)
                btnPrimary.isEnabled = true
                btnSecondary.text = "Stop gamepad"
                btnSecondary.isEnabled = true
            }
            is HidStatus.Connected -> {
                statusText.text = getString(R.string.status_connected, status.hostName)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.ok))
                hostText.text = "${status.hostName}\n${status.hostAddress}"
                btnPrimary.text = getString(R.string.btn_disconnect)
                btnPrimary.isEnabled = true
                btnSecondary.text = "Stop gamepad"
                btnSecondary.isEnabled = true
            }
            is HidStatus.Error -> {
                statusText.text = "Error: ${status.message}"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.err))
                btnPrimary.text = getString(R.string.btn_register)
                btnPrimary.isEnabled = true
                btnSecondary.isEnabled = true
            }
        }
    }


    private fun hasBtPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter
}
