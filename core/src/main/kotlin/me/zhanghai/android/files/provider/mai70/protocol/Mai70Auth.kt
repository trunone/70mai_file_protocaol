package me.zhanghai.android.files.provider.mai70.protocol

import kotlinx.coroutines.delay
import me.zhanghai.android.files.provider.mai70.protocol.http.HttpTransport
import me.zhanghai.android.files.provider.mai70.protocol.models.Authority
import kotlin.random.Random

/**
 * Handles 70mai pairing, token extraction, and registration workflows.
 */
class Mai70Auth(
    private val transport: HttpTransport,
    private val host: String = "192.168.0.1",
    private val port: Int = 80
) {

    /**
     * Executes the pairing handshake:
     * 1. Call `BindByBanya.cgi`
     * 2. Poll `UserconfirmByBanya.cgi` until button press confirmed
     * 3. Register client keep-alive
     *
     * @param onWaitingForButton Callback invoked before polling for camera button press.
     * @param maxAttempts Maximum polling retries (default 20).
     * @param delayMs Delay between retries in ms (default 5000ms).
     * @return Final session token string.
     * @throws Mai70PairingException on binding failure or timeout.
     */
    suspend fun pair(
        onWaitingForButton: suspend () -> Unit = {},
        maxAttempts: Int = 20,
        delayMs: Long = 5000
    ): String {
        // Step 1: BindByBanya
        val userId = Random.nextInt(100000, 999999).toString()
        val key1 = Mai70Protocol.calculateMD5(userId + Mai70Protocol.PAIRING_SALT)
        val bindUrl = "http://$host:$port/cgi-bin/BindByBanya.cgi?&-usr=$userId&-signkey=$key1"

        val bindResponse = transport.get(bindUrl)
            ?: throw Mai70PairingException("Empty response from BindByBanya")

        if (!Mai70Protocol.isSuccessResponse(bindResponse)) {
            throw Mai70PairingException("BindByBanya failed: $bindResponse")
        }

        val token = Mai70Protocol.extractJsonValue(bindResponse, "Token")
        val timestamp = Mai70Protocol.extractJsonValue(bindResponse, "timestamp")

        if (token.isEmpty() || timestamp.isEmpty()) {
            throw Mai70PairingException("Failed to extract token or timestamp from BindByBanya response")
        }

        // Step 2: UserconfirmByBanya
        val key2 = Mai70Protocol.calculateMD5(timestamp + Mai70Protocol.PAIRING_SALT)
        val confirmUrl = "http://$host:$port/cgi-bin/UserconfirmByBanya.cgi?&-timestamp=$timestamp&-signkey=$key2"

        onWaitingForButton()

        var confirmedToken: String? = null
        for (attempt in 1..maxAttempts) {
            val confirmResponse = transport.get(confirmUrl)
            if (confirmResponse != null && Mai70Protocol.isSuccessResponse(confirmResponse)) {
                val finalToken = Mai70Protocol.extractJsonValue(confirmResponse, "Token")
                confirmedToken = finalToken.ifEmpty { token }
                break
            }
            if (attempt < maxAttempts) {
                delay(delayMs)
            }
        }

        val activeToken = confirmedToken
            ?: throw Mai70PairingException("User button confirmation timed out after $maxAttempts attempts")

        // Step 3: Register client
        val authority = Authority(host = host, port = port, token = activeToken)
        register(authority)

        return activeToken
    }

    /**
     * Registers active phone client with camera (`client.cgi`).
     *
     * @param authority Target [Authority] containing host, port, and token.
     * @param clientIp Client IP address (default: "192.168.0.2").
     * @return `true` if registration confirmed.
     */
    suspend fun register(authority: Authority, clientIp: String = "192.168.0.2"): Boolean {
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "client.cgi?"
        val queryParams = "&-operation=register&-ip=$clientIp&-timestamp=$timestamp"

        val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
        val responseText = transport.get(url) ?: return false
        return Mai70Protocol.isSuccessResponse(responseText)
    }
}
