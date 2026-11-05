package com.phonepad.app.hid

sealed class HidStatus {
    data object Unsupported : HidStatus()
    data object PermissionRequired : HidStatus()
    data object BluetoothOff : HidStatus()
    data object Idle : HidStatus()
    data object Registering : HidStatus()
    data object Ready : HidStatus()
    data class Connected(val hostName: String, val hostAddress: String) : HidStatus()
    data class Error(val message: String) : HidStatus()
}
