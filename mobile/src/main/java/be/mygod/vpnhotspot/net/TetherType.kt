package be.mygod.vpnhotspot.net

import android.content.res.Resources
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.util.Event0
import be.mygod.vpnhotspot.util.findIdentifier
import timber.log.Timber
import java.util.regex.Pattern

enum class TetherType(@DrawableRes val icon: Int) {
    NONE(R.drawable.ic_device_wifi_tethering),
    WIFI_P2P(R.drawable.ic_action_settings_input_antenna),
    USB(R.drawable.ic_device_usb),
    WIFI(R.drawable.ic_device_network_wifi),
    BLUETOOTH(R.drawable.ic_device_bluetooth),
    // if you have an issue with these Ethernet icon namings, blame Google
    NCM(R.drawable.ic_action_settings_ethernet),
    ETHERNET(R.drawable.ic_content_inbox),
    WIGIG(R.drawable.ic_image_flash_on),
    VIRTUAL(R.drawable.ic_deployed_code),
    ;

    val isWifi get() = when (this) {
        WIFI_P2P, WIFI, WIGIG -> true
        else -> false
    }

    fun isA(other: TetherType) = this == other || other == USB && this == NCM

    companion object : TetheringManagerCompat.TetheringEventCallback {
        private lateinit var usbRegexs: List<Pattern>
        private lateinit var wifiRegexs: List<Pattern>
        private var wigigRegexs = emptyList<Pattern>()
        private var wifiP2pRegexs = emptyList<Pattern>()
        private lateinit var bluetoothRegexs: List<Pattern>
        private var ncmRegexs = emptyList<Pattern>()
        private val ethernetRegex: Pattern?
        private var requiresUpdate = false

        @RequiresApi(30)    // unused on lower APIs
        val listener = Event0()

        private fun Pair<String, Resources>.getRegexs(name: String, alternativePackage: String? = null): List<Pattern> {
            val id = second.findIdentifier(name, "array", first, alternativePackage)
            return if (id == 0) {
                if (name == "config_tether_wigig_regexs") Timber.i("$name is empty") else Timber.w(Exception(name))
                emptyList()
            } else try {
                second.getStringArray(id).filterNotNull().map { it.toPattern() }
            } catch (_: Resources.NotFoundException) {
                Timber.w(Exception("$name not found"))
                emptyList()
            }
        }

        @RequiresApi(30)
        private fun updateRegexs() = synchronized(this) {
            if (!requiresUpdate) return@synchronized
            requiresUpdate = false
            usbRegexs = emptyList()
            wifiRegexs = emptyList()
            bluetoothRegexs = emptyList()
            TetheringManagerCompat.registerTetheringEventCallback(this)
            val info = TetheringManagerCompat.resolvedService.serviceInfo
            val tethering = "com.android.networkstack.tethering" to
                    app.packageManager.getResourcesForApplication(info.applicationInfo)
            usbRegexs = tethering.getRegexs("config_tether_usb_regexs", info.packageName)
            wifiRegexs = tethering.getRegexs("config_tether_wifi_regexs", info.packageName)
            wigigRegexs = tethering.getRegexs("config_tether_wigig_regexs", info.packageName)
            wifiP2pRegexs = tethering.getRegexs("config_tether_wifi_p2p_regexs", info.packageName)
            bluetoothRegexs = tethering.getRegexs("config_tether_bluetooth_regexs", info.packageName)
            ncmRegexs = tethering.getRegexs("config_tether_ncm_regexs", info.packageName)
        }

        @RequiresApi(30)
        override fun onTetherableInterfaceRegexpsChanged(reg: Any?) = synchronized(this) {
            if (requiresUpdate) return@synchronized
            Timber.i("onTetherableInterfaceRegexpsChanged: $reg")
            TetheringManagerCompat.unregisterTetheringEventCallback(this)
            requiresUpdate = true
            listener()
        }

        /**
         * Source: https://android.googlesource.com/platform/frameworks/base/+/32e772f/packages/Tethering/src/com/android/networkstack/tethering/TetheringConfiguration.java#93
         */
        init {
            val system = "android" to Resources.getSystem()
            if (Build.VERSION.SDK_INT >= 30) requiresUpdate = true else {
                usbRegexs = system.getRegexs("config_tether_usb_regexs")
                wifiRegexs = system.getRegexs("config_tether_wifi_regexs")
                bluetoothRegexs = system.getRegexs("config_tether_bluetooth_regexs")
            }
            // available since Android 4.0: https://android.googlesource.com/platform/frameworks/base/+/c96a667162fab44a250503caccb770109a9cb69a
            ethernetRegex = try {
                system.second.getString(system.second.getIdentifier("config_ethernet_iface_regex", "string",
                        system.first)).run { if (isEmpty()) null else toPattern() }
            } catch (_: Resources.NotFoundException) {
                null
            }
        }

        /**
         * The result could change for the same interface since API 30+.
         * It will be triggered by [TetheringManagerCompat.TetheringEventCallback.onTetherableInterfaceRegexpsChanged].
         *
         * Based on: https://android.googlesource.com/platform/frameworks/base/+/5d36f01/packages/Tethering/src/com/android/networkstack/tethering/Tethering.java#479
         */
        fun ofInterface(iface: String?, p2pDev: String? = null) = when (iface) {
            null -> NONE
            p2pDev -> WIFI_P2P
            else -> try {
                synchronized(this) { ofInterfaceImpl(iface) }
            } catch (e: RuntimeException) {
                Timber.w(e)
                NONE
            }
        }
        private tailrec fun ofInterfaceImpl(iface: String): TetherType = when {
            requiresUpdate -> {
                if (Build.VERSION.SDK_INT >= 30) updateRegexs() else error("unexpected requiresUpdate")
                ofInterfaceImpl(iface)
            }
            wifiRegexs.any { it.matcher(iface).matches() } -> WIFI
            wigigRegexs.any { it.matcher(iface).matches() } -> WIGIG
            wifiP2pRegexs.any { it.matcher(iface).matches() } -> WIFI_P2P
            usbRegexs.any { it.matcher(iface).matches() } -> USB
            bluetoothRegexs.any { it.matcher(iface).matches() } -> BLUETOOTH
            ncmRegexs.any { it.matcher(iface).matches() } -> NCM
            ethernetRegex?.matcher(iface)?.matches() == true -> ETHERNET
            // https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Connectivity/Tethering/src/com/android/networkstack/tethering/Tethering.java;l=979;drc=b4d6320e2ae398b36f0aaafb2ecd83609d2d99af
            iface == "avf_tap_fixed" -> VIRTUAL
            else -> NONE
        }

        fun fromTetheringType(type: Int) = when (type) {
            TetheringManager.TETHERING_WIFI -> WIFI
            TetheringManagerCompat.TETHERING_USB -> USB
            TetheringManagerCompat.TETHERING_BLUETOOTH -> BLUETOOTH
            3 -> WIFI_P2P
            4 -> NCM
            TetheringManagerCompat.TETHERING_ETHERNET -> ETHERNET
            6 -> WIGIG
            7 -> VIRTUAL
            else -> NONE.also { Timber.w(Exception("Unhandled tethering type $type")) }
        }
    }
}
