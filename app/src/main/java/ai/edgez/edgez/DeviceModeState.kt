package ai.edgez.edgez

object DeviceModeState {
    @Volatile
    private var enabledValue: Boolean = false

    @Volatile
    var known: Boolean = false
        private set

    var enabled: Boolean
        get() = enabledValue
        set(value) {
            enabledValue = value
            // Direct true assignments enter provisioning/device mode. A false
            // assignment usually means the transport disconnected, so the
            // next device mode is unknown until DEVICE_SETTINGS_REPORT arrives.
            known = value
        }

    val userModeConfirmed: Boolean
        get() = known && !enabledValue

    fun updateFromDeviceType(deviceType: EdgeZDeviceType) {
        enabledValue = deviceType.isDeviceProfile
        known = true
    }
}
