package com.mai70.protocol.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mai70.protocol.http.HttpTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [HttpTransport] backed by [OkHttpClient].
 *
 * Supports automatic camera Wi-Fi network detection and socket factory binding so the mobile phone
 * can communicate with the dashcam over Wi-Fi even when cellular mobile data is active.
 *
 * @param context Android Application Context.
 * @param defaultCameraIp Configured camera IP address (default: "192.168.0.1").
 * @param baseClient Optional customized base OkHttpClient.
 */
class AndroidNetworkTransport(
    context: Context,
    private val defaultCameraIp: String = "192.168.0.1",
    baseClient: OkHttpClient = defaultOkHttpClient
) : HttpTransport {

    private val appContext: Context = context.applicationContext

    private var client: OkHttpClient = baseClient
    private var cameraClient: OkHttpClient? = null
    private var cameraNetwork: Network? = null

    /** Current IPv4 address assigned to camera interface */
    var currentIpAddress: String? = null
        private set

    /** DHCP Server (Gateway) address of detected camera Wi-Fi network */
    var dhcpServerIpAddress: String? = null
        private set

    companion object {
        /** Default reusable [OkHttpClient] instance with standard timeouts */
        val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Scans active Android network interfaces and binds socket factory to camera Wi-Fi network if found.
     */
    fun updateNetworkInfo() {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        @Suppress("DEPRECATION")
        val networks = connectivityManager.allNetworks
        var foundCameraNetwork: Network? = null

        for (network in networks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val linkProperties = connectivityManager.getLinkProperties(network)

            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val dhcpAddr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    linkProperties?.dhcpServerAddress?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    val gateway = wifiManager?.dhcpInfo?.gateway ?: 0
                    if (gateway != 0) {
                        String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            gateway and 0xff,
                            gateway shr 8 and 0xff,
                            gateway shr 16 and 0xff,
                            gateway shr 24 and 0xff
                        )
                    } else null
                }

                if (dhcpAddr == defaultCameraIp) {
                    foundCameraNetwork = network
                    this.dhcpServerIpAddress = dhcpAddr

                    val ipv4Address = linkProperties?.linkAddresses?.find {
                        it.address is Inet4Address
                    }?.address?.hostAddress
                    this.currentIpAddress = ipv4Address
                    break
                }
            }
        }

        if (foundCameraNetwork != null) {
            if (this.cameraNetwork != foundCameraNetwork) {
                this.cameraNetwork = foundCameraNetwork
                this.cameraClient = client.newBuilder()
                    .socketFactory(foundCameraNetwork.socketFactory)
                    .build()
            }
        } else {
            this.cameraNetwork = null
            this.cameraClient = null

            val activeNetwork = connectivityManager.activeNetwork
            val activeLP = connectivityManager.getLinkProperties(activeNetwork)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this.dhcpServerIpAddress = activeLP?.dhcpServerAddress?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                val gateway = wifiManager?.dhcpInfo?.gateway ?: 0
                if (gateway != 0) {
                    this.dhcpServerIpAddress = String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        gateway and 0xff,
                        gateway shr 8 and 0xff,
                        gateway shr 16 and 0xff,
                        gateway shr 24 and 0xff
                    )
                }
            }
            this.currentIpAddress = activeLP?.linkAddresses?.find { it.address is Inet4Address }?.address?.hostAddress
        }
    }

    /**
     * Resolves the active [OkHttpClient] instance based on target URL.
     */
    fun getOkHttpClientForUrl(url: String): OkHttpClient {
        return if (url.contains(defaultCameraIp) && cameraClient != null) {
            cameraClient!!
        } else {
            client
        }
    }

    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            updateNetworkInfo()
            val request = Request.Builder().url(url).build()
            val activeClient = getOkHttpClientForUrl(url)
            activeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun openStream(url: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            updateNetworkInfo()
            val request = Request.Builder().url(url).build()
            val activeClient = getOkHttpClientForUrl(url)
            val response = activeClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }
            val body = response.body ?: run {
                response.close()
                return@withContext null
            }
            body.byteStream()
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun probe(url: String): Pair<Long, String?>? = withContext(Dispatchers.IO) {
        try {
            updateNetworkInfo()
            val activeClient = getOkHttpClientForUrl(url)

            // Try HEAD request first
            try {
                val headRequest = Request.Builder().url(url).head().build()
                activeClient.newCall(headRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        val lastModified = response.header("Last-Modified")
                        return@withContext Pair(length, lastModified)
                    }
                }
            } catch (_: Exception) {
                // Fall back to Range request
            }

            // Range request fallback
            val rangeRequest = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            activeClient.newCall(rangeRequest).execute().use { response ->
                if (response.isSuccessful || response.code == 416) {
                    val contentRange = response.header("Content-Range")
                    val size = if (contentRange != null && contentRange.contains("/")) {
                        contentRange.substringAfter("/").toLongOrNull() ?: 0L
                    } else {
                        response.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                    val lastModified = response.header("Last-Modified")
                    return@withContext Pair(size, lastModified)
                }
            }
            null
        } catch (_: IOException) {
            null
        }
    }
}
