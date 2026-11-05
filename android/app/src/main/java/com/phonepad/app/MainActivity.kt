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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            maybeStartService()
        } else {
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
            // Auto-register once bound if BT is on and we already hold permissions
            if (hasBtPermissions() && bluetoothAdapter()?.isEnabled == true) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, HidGamepadService::class.java),
                )
                svc.startHid()
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

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(it)
                }
            }
        } else {
            // Pre-31: location sometimes required for BT discovery on older stacks
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
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
        if (!adapter.isEnabled) {
            applyStatus(HidStatus.BluetoothOff)
            return
        }
        // Start FGS then ask it to register
        val intent = Intent(this, HidGamepadService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // Service may already be bound
        service?.startHid() ?: run {
            // briefly wait for bind — startHid called again from onServiceConnected via Idle→user
            // Auto-start once connected:
            // Handled by posting after a short delay if service not yet bound
            statusText.postDelayed({
                service?.startHid()
            }, 300)
        }
    }

    private fun onPrimary() {
        when (service?.currentStatus() ?: HidStatus.Idle) {
            is HidStatus.PermissionRequired -> ensurePermissionsThenStart()
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
        when (service?.currentStatus()) {
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
                statusText.text = getString(R.string.status_registering)
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
                statusText.text = status.message
                statusText.setTextColor(ContextCompat.getColor(this, R.color.err))
                btnPrimary.text = getString(R.string.btn_register)
                btnPrimary.isEnabled = true
                btnSecondary.isEnabled = true
            }
        }
    }


    private fun hasBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
            ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        } else true
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter
}
