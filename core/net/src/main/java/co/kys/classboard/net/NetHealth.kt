package co.kys.classboard.net


import java.net.NetworkInterface
import java.net.Inet4Address

object NetUtils {
    fun localIpv4OrNull(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        return ifaces.toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}
