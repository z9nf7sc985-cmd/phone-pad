package com.phonepad.app.hid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phonepad.app.MainActivity
import com.phonepad.app.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns BluetoothHidDevice profile proxy, SDP registration, and report send loop.
 * Keeps a neutral report cached and rate-caps sends (~100 Hz).
 */
class HidGamepadService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): HidGamepadService = this@HidGamepadService
    }

    private val binder = LocalBinder()
    private val statusListeners = mutableSetOf<(HidStatus) -> Unit>()
    private val statusRef = AtomicReference<HidStatus>(HidStatus.Idle)
    private val stateRef = AtomicReference(GamepadState.NEUTRAL)
    private val lastSent = AtomicReference<ByteArray?>(null)

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var registered = false
    private var proxyReady = false

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private var sendThread: HandlerThread? = null
    private var sendHandler: Handler? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            hidDevice = proxy
            proxyReady = true
            Log.i(TAG, "HID_DEVICE proxy connected")
            registerAppInternal()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            Log.w(TAG, "HID_DEVICE proxy disconnected")
            hidDevice = null
            proxyReady = false
            registered = false
            connectedDevice = null
            publish(HidStatus.Error("HID service disconnected"))
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            Log.i(TAG, "onAppStatusChanged registered=$isRegistered plugged=${pluggedDevice?.address}")
            registered = isRegistered
            if (isRegistered) {
                val host = connectedDevice
                if (host != null) {
                    publish(HidStatus.Connected(hostName(host), host.address))
                } else {
                    publish(HidStatus.Ready)
                }
            } else {
                connectedDevice = null
                publish(HidStatus.Idle)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val name = device?.let { hostName(it) } ?: "?"
            Log.i(TAG, "onConnectionStateChanged $name state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    if (device != null) {
                        publish(HidStatus.Connected(hostName(device), device.address))
                        // Push neutral then current state so host wakes inputs
                        flushReport(force = true)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device?.address) {
                        connectedDevice = null
                        lastSent.set(null)
                        if (registered) publish(HidStatus.Ready) else publish(HidStatus.Idle)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val bm = getSystemService(BluetoothManager::class.java)
        adapter = bm?.adapter
        sendThread = HandlerThread("phonepad-hid-send", Thread.MAX_PRIORITY).also {
            it.start()
            sendHandler = Handler(it.looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        sendThread?.quitSafely()
        sendThread = null
        sendHandler = null
        callbackExecutor.shutdownNow()
        super.onDestroy()
    }

    fun currentStatus(): HidStatus = statusRef.get()

    fun addStatusListener(listener: (HidStatus) -> Unit) {
        synchronized(statusListeners) {
            statusListeners.add(listener)
            listener(statusRef.get())
        }
    }

    fun removeStatusListener(listener: (HidStatus) -> Unit) {
        synchronized(statusListeners) { statusListeners.remove(listener) }
    }

    fun updateState(state: GamepadState) {
        stateRef.set(state)
        scheduleSend()
    }

    fun sendNeutral() {
        stateRef.set(GamepadState.NEUTRAL)
        flushReport(force = true)
    }

    fun startHid() {
        val a = adapter
        if (a == null) {
            publish(HidStatus.Unsupported)
            return
        }
        if (!a.isEnabled) {
            publish(HidStatus.BluetoothOff)
            return
        }
        // Probe profile availability
        try {
            // getProfileProxy returns false if profile unsupported
            publish(HidStatus.Registering)
            val ok = a.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
            if (!ok) {
                publish(HidStatus.Unsupported)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getProfileProxy failed", t)
            publish(HidStatus.Unsupported)
        }
    }

    fun stopHid() {
        unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        proxyReady = false
        connectedDevice = null
        publish(HidStatus.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun disconnectHost() {
        val hid = hidDevice
        val dev = connectedDevice
        if (hid != null && dev != null) {
            try {
                hid.disconnect(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect failed", t)
            }
        }
        connectedDevice = null
        if (registered) publish(HidStatus.Ready) else publish(HidStatus.Idle)
    }

    fun connectedHostName(): String? = connectedDevice?.let { hostName(it) }

    private fun registerAppInternal() {
        val hid = hidDevice ?: return
        if (registered) {
            publish(HidStatus.Ready)
            return
        }
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                GenericHidProfile.SDP_NAME,
                GenericHidProfile.SDP_DESCRIPTION,
                GenericHidProfile.SDP_PROVIDER,
                GenericHidProfile.SUBCLASS,
                GenericHidProfile.descriptor,
            )
            val ok = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
            Log.i(TAG, "registerApp sent=$ok")
            if (!ok) publish(HidStatus.Error("registerApp returned false"))
            // Actual Ready comes from onAppStatusChanged
        } catch (sec: SecurityException) {
            Log.e(TAG, "registerApp permission", sec)
            publish(HidStatus.PermissionRequired)
        } catch (t: Throwable) {
            Log.e(TAG, "registerApp failed", t)
            publish(HidStatus.Error(t.message ?: "registerApp failed"))
        }
    }

    private fun unregisterApp() {
        val hid = hidDevice ?: return
        try {
            if (registered) hid.unregisterApp()
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterApp", t)
        }
        registered = false
    }

    private fun scheduleSend() {
        val h = sendHandler ?: return
        h.removeCallbacks(sendRunnable)
        h.post(sendRunnable)
    }

    private val sendRunnable = Runnable { flushReport(force = false) }

    @Volatile private var lastSendElapsed = 0L

    private fun flushReport(force: Boolean) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        if (!registered) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSendElapsed < MIN_SEND_GAP_MS) {
            sendHandler?.removeCallbacks(deferredSend)
            sendHandler?.postDelayed(deferredSend, MIN_SEND_GAP_MS - (now - lastSendElapsed))
            return
        }
        val report = GenericHidProfile.encode(stateRef.get())
        val prev = lastSent.get()
        if (!force && prev != null && prev.contentEquals(report)) return
        try {
            val ok = hid.sendReport(device, GenericHidProfile.REPORT_ID, report)
            if (ok) {
                lastSent.set(report)
                lastSendElapsed = SystemClock.elapsedRealtime()
            } else {
                Log.w(TAG, "sendReport returned false")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport failed", t)
        }
    }

    private val deferredSend = Runnable { flushReport(force = false) }

    private fun publish(status: HidStatus) {
        statusRef.set(status)
        synchronized(statusListeners) {
            statusListeners.forEach { it(status) }
        }
    }

    private fun hostName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
    }

    private fun startAsForeground() {
        val channelId = "phonepad_hid"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PhonePadHID"
        private const val NOTIF_ID = 42
        private const val MIN_SEND_GAP_MS = 10L
    }
}

