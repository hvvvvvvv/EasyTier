package com.plugin.vpnservice

import android.content.Intent
import android.net.VpnService
import android.net.IpPrefix
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import java.net.InetAddress
import java.util.Arrays

import app.tauri.plugin.JSObject

fun stringToIpPrefix(ipPrefixString: String): IpPrefix {
    val parts = ipPrefixString.split("/")
    if (parts.size != 2) throw IllegalArgumentException("Invalid IP prefix string")
    
    val address = InetAddress.getByName(parts[0])
    val prefixLength = parts[1].toInt()
    
    return IpPrefix(address, prefixLength)
}

class TauriVpnService : VpnService() {
    companion object {
        @JvmField var triggerCallback: (String, JSObject) -> Unit = { _, _ -> }
        @JvmField var self: TauriVpnService? = null

        const val IPV4_ADDR = "IPV4_ADDR"
        const val ROUTES = "ROUTES"
        const val DNS = "DNS"
        const val DISALLOWED_APPLICATIONS = "DISALLOWED_APPLICATIONS"
        const val MTU = "MTU"
    }

    private lateinit var vpnInterface: ParcelFileDescriptor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("vpn on start command ${intent?.getExtras()} $intent")
        var args = intent?.getExtras()

        vpnInterface = createVpnInterface(args)
        println("vpn created ${vpnInterface.fd}")

        var event_data = JSObject()
        event_data.put("fd", vpnInterface.fd)
        triggerCallback("vpn_service_start", event_data)

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        self = this
        println("vpn on create")
    }

    override fun onDestroy() {
        println("vpn on destroy")
        self = null
        super.onDestroy()
        disconnect()
    }

    override fun onRevoke() {
        println("vpn on revoke")
        self = null
        super.onRevoke()
        disconnect()
    }

    private fun disconnect() {
        triggerCallback("vpn_service_stop", JSObject())
        vpnInterface.close()
    }

    private fun createVpnInterface(args: Bundle?): ParcelFileDescriptor {
        var builder = Builder()
                .setSession("TauriVpnService")
                .setBlocking(false)
        
        var mtu = args?.getInt(MTU) ?: 1500
        var ipv4Addr = args?.getString(IPV4_ADDR) ?: "10.126.126.1/24"
        var dns = args?.getString(DNS) ?: "114.114.114.114"
        var routes = args?.getStringArray(ROUTES) ?: emptyArray()
        var disallowedApplications = args?.getStringArray(DISALLOWED_APPLICATIONS) ?: emptyArray()

        println("vpn create vpn interface. mtu: $mtu, ipv4Addr: $ipv4Addr, dns:" +
            "$dns, routes: ${java.util.Arrays.toString(routes)}," +
            "disallowedApplications:  ${java.util.Arrays.toString(disallowedApplications)}")

        val ipParts = ipv4Addr.split("/")
        if (ipParts.size != 2) throw IllegalArgumentException("Invalid IP addr string")
        builder.addAddress(ipParts[0], ipParts[1].toInt())

        builder.setMtu(mtu)
        builder.addDnsServer(dns)

        for (route in routes) {
            builder.addRoute(stringToIpPrefix(route))
        }
        
        for (app in disallowedApplications) {
            builder.addDisallowedApplication(app)
        }

        return builder.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.setMetered(false)
            }
        }
        .establish()
        ?: throw IllegalStateException("Failed to init VpnService")
    }
}