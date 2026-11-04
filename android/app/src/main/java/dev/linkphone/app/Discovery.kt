package dev.linkphone.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

object Proto {
    const val VERSION = 1
    const val SERVICE_TYPE = "_linkphone._tcp."
    const val KIND_CONTROL = 0x10
    const val KIND_FILE = 0x20
    const val CHUNK = 64 * 1024
}

/** mDNS browse for the desktop's _linkphone._tcp advertisement. */
class Discovery(private val context: Context) {

    data class Target(
        val name: String,
        val host: String,
        val port: Int,
        val serverId: String = ""
    )

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var onFound: ((Target) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun start(onFound: (Target) -> Unit, onError: (String) -> Unit) {
        stop()
        this.onFound = onFound
        this.onError = onError

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            onError("No network service discovery on this device")
            return
        }
        nsd = manager

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("LinkPhone", "discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != Proto.SERVICE_TYPE) return
                if (resolving) return
                resolving = true
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w("LinkPhone", "resolve failed $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val address = serviceInfo.host?.hostAddress ?: return
                        val serverId = serviceInfo.attributes?.get("id")
                            ?.toString(Charsets.UTF_8)?.trim().orEmpty()
                        onFound?.invoke(
                            Target(serviceInfo.serviceName, address, serviceInfo.port, serverId)
                        )
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError?.invoke("Discovery failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        listener = discoveryListener
        runCatching {
            manager.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            onError(it.message ?: "Discovery failed")
        }
    }

    fun stop() {
        val active = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(active) }
    }
}
