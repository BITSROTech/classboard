package co.kys.classboard.lobby

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
class NsdBrowser(private val context: Context) {
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolves = ConcurrentHashMap<String, NsdManager.ResolveListener>()

    fun start(
        serviceType: String = "_classboard._tcp.",
        onResolved: (NsdServiceInfo) -> Unit,
        onLost: (String) -> Unit = {}
    ) {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { stop() }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) { stop() }
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceType != serviceType) return
                val r = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) { onResolved(info) }
                }
                resolves[s.serviceName] = r
                nsd.resolveService(s, r)
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                onLost(s.serviceName)
                resolves.remove(s.serviceName)
            }
        }
        discovery = listener
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        resolves.clear()
    }
}
