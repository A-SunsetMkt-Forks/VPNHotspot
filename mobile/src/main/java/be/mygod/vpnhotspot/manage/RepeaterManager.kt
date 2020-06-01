package be.mygod.vpnhotspot.manage

import android.Manifest
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiConfiguration
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.EditText
import androidx.annotation.MainThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import be.mygod.vpnhotspot.*
import be.mygod.vpnhotspot.databinding.ListitemRepeaterBinding
import be.mygod.vpnhotspot.net.wifi.configuration.*
import be.mygod.vpnhotspot.util.ServiceForegroundConnector
import be.mygod.vpnhotspot.util.formatAddresses
import be.mygod.vpnhotspot.util.showAllowingStateLoss
import be.mygod.vpnhotspot.widget.SmartSnackbar
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.NetworkInterface
import java.net.SocketException

class RepeaterManager(private val parent: TetheringFragment) : Manager(), ServiceConnection {
    class ViewHolder(val binding: ListitemRepeaterBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.addresses.movementMethod = LinkMovementMethod.getInstance()
        }
    }
    inner class Data : BaseObservable() {
        val switchEnabled: Boolean
            @Bindable get() = when (binder?.service?.status) {
                RepeaterService.Status.IDLE, RepeaterService.Status.ACTIVE -> true
                else -> false
            }
        val serviceStarted: Boolean
            @Bindable get() = when (binder?.service?.status) {
                RepeaterService.Status.STARTING, RepeaterService.Status.ACTIVE -> true
                else -> false
            }

        val title: CharSequence @Bindable get() {
            if (Build.VERSION.SDK_INT >= 29) binder?.group?.frequency?.let {
                if (it != 0) return parent.getString(R.string.repeater_channel, it, frequencyToChannel(it))
            }
            return parent.getString(R.string.title_repeater)
        }
        val addresses: CharSequence @Bindable get() {
            return try {
                NetworkInterface.getByName(p2pInterface ?: return "")?.formatAddresses() ?: ""
            } catch (_: SocketException) {
                ""
            }
        }

        fun onStatusChanged() {
            notifyPropertyChanged(BR.switchEnabled)
            notifyPropertyChanged(BR.serviceStarted)
            notifyPropertyChanged(BR.addresses)
        }
        fun onGroupChanged(group: WifiP2pGroup? = null) {
            p2pInterface = group?.`interface`
            if (Build.VERSION.SDK_INT >= 29) notifyPropertyChanged(BR.title)
            notifyPropertyChanged(BR.addresses)
        }

        fun toggle() {
            val binder = binder
            when (binder?.service?.status) {
                RepeaterService.Status.IDLE -> if (Build.VERSION.SDK_INT < 29) parent.requireContext().let { context ->
                    ContextCompat.startForegroundService(context, Intent(context, RepeaterService::class.java))
                } else parent.startRepeater.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                RepeaterService.Status.ACTIVE -> binder.shutdown()
                else -> { }
            }
        }

        fun wps() {
            if (binder?.active == true) WpsDialogFragment().apply {
                key()
            }.showAllowingStateLoss(parent.parentFragmentManager)
        }
    }

    @Parcelize
    data class WpsRet(val pin: String?) : Parcelable
    class WpsDialogFragment : AlertDialogFragment<Empty, WpsRet>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.repeater_wps_dialog_title)
            setView(R.layout.dialog_wps)
            setPositiveButton(android.R.string.ok, listener)
            setNegativeButton(android.R.string.cancel, null)
            setNeutralButton(R.string.repeater_wps_dialog_pbc, listener)
        }

        override val ret get() = WpsRet(dialog!!.findViewById<EditText>(android.R.id.edit)?.text?.toString())

        override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).apply {
            window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    class ConfigHolder : ViewModel() {
        var config: P2pSupplicantConfiguration? = null
    }

    init {
        ServiceForegroundConnector(parent, this, RepeaterService::class)
        AlertDialogFragment.setResultListener<WifiApDialogFragment.Arg>(parent, javaClass.name) { which, ret ->
            if (which == DialogInterface.BUTTON_POSITIVE) GlobalScope.launch(Dispatchers.Main.immediate) {
                updateConfiguration(ret!!.configuration)
            }
        }
        AlertDialogFragment.setResultListener<WpsDialogFragment, WpsRet>(parent) { which, ret ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> binder!!.startWps(ret!!.pin)
                DialogInterface.BUTTON_NEUTRAL -> binder!!.startWps(null)
            }
        }
    }

    fun configure() = parent.viewLifecycleOwner.lifecycleScope.launchWhenCreated {
        getConfiguration()?.let { config ->
            WifiApDialogFragment().apply {
                arg(WifiApDialogFragment.Arg(config, p2pMode = true))
                key(this@RepeaterManager.javaClass.name)
            }.showAllowingStateLoss(parent.parentFragmentManager)
        }
    }

    override val type get() = VIEW_TYPE_REPEATER
    private val data = Data()
    internal var binder: RepeaterService.Binder? = null
    private var p2pInterface: String? = null
    private val holder by parent.viewModels<ConfigHolder>()

    override fun bindTo(viewHolder: RecyclerView.ViewHolder) {
        (viewHolder as ViewHolder).binding.data = data
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = service as RepeaterService.Binder
        service.statusChanged[this] = data::onStatusChanged
        service.groupChanged[this] = data::onGroupChanged
        data.notifyChange()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        val binder = binder ?: return
        this.binder = null
        binder.statusChanged -= this
        binder.groupChanged -= this
        data.onStatusChanged()
    }

    @MainThread
    private suspend fun getConfiguration(): WifiConfiguration? {
        if (RepeaterService.safeMode) {
            val networkName = RepeaterService.networkName
            val passphrase = RepeaterService.passphrase
            if (networkName != null && passphrase != null) {
                return newWifiApConfiguration(networkName, passphrase).apply {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK) // is not actually used
                    apBand = when (RepeaterService.operatingBand) {
                        WifiP2pConfig.GROUP_OWNER_BAND_AUTO -> AP_BAND_ANY
                        WifiP2pConfig.GROUP_OWNER_BAND_2GHZ -> AP_BAND_2GHZ
                        WifiP2pConfig.GROUP_OWNER_BAND_5GHZ -> AP_BAND_5GHZ
                        else -> throw IllegalArgumentException("Unknown operatingBand")
                    }
                    apChannel = RepeaterService.operatingChannel
                }
            }
        } else {
            val group = binder?.group
            if (group != null) try {
                val config = withContext(Dispatchers.Default) {
                    P2pSupplicantConfiguration(group, binder?.thisDevice?.deviceAddress)
                }
                holder.config = config
                return newWifiApConfiguration(group.networkName, config.psk).apply {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK) // is not actually used
                    if (Build.VERSION.SDK_INT >= 23) {
                        apBand = AP_BAND_ANY
                        apChannel = RepeaterService.operatingChannel
                    }
                }
            } catch (e: RuntimeException) {
                Timber.w(e)
            }
        }
        SmartSnackbar.make(R.string.repeater_configure_failure).show()
        return null
    }
    private suspend fun updateConfiguration(config: WifiConfiguration) {
        if (RepeaterService.safeMode) {
            RepeaterService.networkName = config.SSID
            RepeaterService.passphrase = config.preSharedKey
            RepeaterService.operatingBand = when (config.apBand) {
                AP_BAND_ANY -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                AP_BAND_2GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                AP_BAND_5GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                else -> throw IllegalArgumentException("Unknown apBand")
            }
        } else holder.config?.let { master ->
            val binder = binder
            if (binder?.group?.networkName != config.SSID || master.psk != config.preSharedKey) try {
                withContext(Dispatchers.Default) { master.update(config.SSID, config.preSharedKey) }
                (this.binder ?: binder)?.group = null
            } catch (e: Exception) {
                Timber.w(e)
                SmartSnackbar.make(e).show()
            }
            holder.config = null
        }
        if (Build.VERSION.SDK_INT >= 23) RepeaterService.operatingChannel = config.apChannel
    }
}
