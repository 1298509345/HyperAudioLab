package lab.hyperaudio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress

object LanRouting {
    /** Prefer the actual local Wi-Fi route even if cellular is the default Internet network. */
    @Suppress("DEPRECATION")
    fun networkFor(context: Context, host: String): Network? {
        val address = InetAddress.getByName(host)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) &&
                cm.getLinkProperties(network)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true
        }
    }
}
