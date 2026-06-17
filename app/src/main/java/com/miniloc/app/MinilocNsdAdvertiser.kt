package com.miniloc.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class MinilocNsdAdvertiser(private val context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registrationKey: String? = null

    fun start(mode: SharingMode, port: Int) {
        val key = "${mode.wireName}:$port"
        if (registrationKey == key && registrationListener != null) return
        stop()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = PairingStore.serviceName(context)
            serviceType = MINILOC_NSD_SERVICE_TYPE
            this.port = port
            setAttribute("app", "miniloc")
            setAttribute("version", APP_VERSION)
            setAttribute("instance_id", PairingStore.instanceId(context))
            setAttribute("mode", mode.wireName)
            setAttribute("auth_required", (mode != SharingMode.OPEN_LAN).toString())
            setAttribute("read_auth_required", (mode != SharingMode.OPEN_LAN).toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
                registrationKey = key
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
                registrationListener = null
                registrationKey = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registrationListener = null
                registrationKey = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }

        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            Log.w(TAG, "NSD registration error", error)
            registrationListener = null
            registrationKey = null
        }
    }

    fun stop() {
        val listener = registrationListener ?: return
        runCatching {
            nsdManager.unregisterService(listener)
        }.onFailure { error ->
            Log.w(TAG, "NSD unregistration error", error)
        }
        registrationListener = null
        registrationKey = null
    }

    companion object {
        private const val TAG = "MinilocNsd"
    }
}
