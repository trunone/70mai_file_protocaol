package me.zhanghai.android.files.provider.mai70.protocol

import me.zhanghai.android.files.provider.mai70.protocol.models.Category
import me.zhanghai.android.files.provider.mai70.protocol.models.FileEntry
import java.math.BigInteger
import java.security.MessageDigest
import me.zhanghai.android.files.provider.mai70.protocol.models.Authority

/**
 * Low-level protocol constants, signature generators, CGI URL builders, and JSON response parsers.
 */
object Mai70Protocol {

    /** Default salt string used in pairing and authentication algorithms */
    const val PAIRING_SALT: String = "73VpsAfdety8FDd0"

    /**
     * Calculates MD5 hash of an input string.
     */
    fun calculateMD5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    /**
     * Generates request sign key: `MD5(urlQuery + token)`.
     */
    fun generateSignKey(urlQuery: String, token: String): String =
        calculateMD5(urlQuery + token)

    /**
     * Sanitizes camera JSON responses (removes trailing commas before closing square brackets).
     */
    fun sanitizeJsonResponse(text: String): String {
        var result = text
        if (result.contains(",\n]}")) {
            result = result.replace(",\n]}", "]} ")
        } else if (result.contains(",]}")) {
            result = result.replace(",]}", "]} ")
        }
        return result
    }

    /**
     * Constructs base CGI request URL for camera endpoint.
     */
    fun buildCgiUrl(
        authority: Authority,
        cgiPath: String,
        queryParams: String,
        token: String = authority.token
    ): String {
        val queryPath = if (cgiPath.endsWith("?")) cgiPath else "$cgiPath?"
        val sign = generateSignKey(queryPath + queryParams, token)
        val connector = if (queryParams.endsWith("&") || queryParams.isEmpty()) "" else "&"
        return "http://${authority.host}:${authority.port}/cgi-bin/$queryPath$queryParams${connector}-signkey=$sign"
    }

    /**
     * Extracts simple string value from JSON without external heavy dependencies.
     */
    fun extractJsonValue(json: String, key: String): String {
        return try {
            val searchKey = "\"$key\""
            if (!json.contains(searchKey)) return ""

            val afterKey = json.substringAfter(searchKey).trim().removePrefix(":")
            val trimmed = afterKey.trim()
            if (trimmed.startsWith("\"")) {
                trimmed.substringAfter("\"").substringBefore("\"")
            } else {
                trimmed.split(Regex("[,}]"))[0].trim().removeSurrounding("\"")
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Checks if response string indicates success (`"success"`, `"ResultCode":0`, `"ResultCode":"0"`, or `"Result":0`).
     */
    fun isSuccessResponse(responseText: String?): Boolean {
        if (responseText.isNullOrEmpty()) return false
        if (responseText.contains("success", ignoreCase = true)) return true
        val resultCode = extractJsonValue(responseText, "ResultCode")
        if (resultCode == "0") return true
        val resultVal = extractJsonValue(responseText, "Result")
        if (resultVal == "0") return true
        val statusVal = extractJsonValue(responseText, "status")
        return statusVal == "0"
    }

    /**
     * Parses `getfilecount.cgi` response into category to count map.
     */
    fun parseFileCountsResponse(responseText: String?): Map<Category, Int> {
        if (responseText.isNullOrEmpty()) return emptyMap()
        val sanitized = sanitizeJsonResponse(responseText)
        if (!isSuccessResponse(sanitized)) return emptyMap()

        val counts = mutableMapOf<Category, Int>()
        try {
            if (sanitized.contains("\"Result\":") && sanitized.contains("[")) {
                val arrayPart = sanitized.substringAfter("\"Result\":").substringAfter("[").substringBefore("]")
                val objects = arrayPart.split("}")
                for (obj in objects) {
                    if (!obj.contains("{")) continue
                    val typeStr = extractJsonValue(obj, "type")
                    val countStr = extractJsonValue(obj, "count")
                    val type = typeStr.toIntOrNull()
                    val count = countStr.toIntOrNull()
                    if (type != null && count != null) {
                        counts[Category.fromType(type)] = count
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return counts
    }

    /**
     * Parses `getfilelist.cgi` response into a list of [FileEntry].
     */
    fun parseFileListResponse(responseText: String?, category: Category = Category.UNKNOWN): List<FileEntry>? {
        if (responseText.isNullOrEmpty()) return null
        val sanitized = sanitizeJsonResponse(responseText)
        if (!isSuccessResponse(sanitized)) return null

        val entries = mutableListOf<FileEntry>()
        try {
            if (sanitized.contains("\"Result\":") && sanitized.contains("[")) {
                val arrayPart = sanitized.substringAfter("\"Result\":").substringAfter("[").substringBeforeLast("]")
                val objects = arrayPart.split("},")
                for (rawObj in objects) {
                    val obj = if (rawObj.startsWith("{")) rawObj else "{$rawObj"
                    if (!obj.contains("path") && !obj.contains("name")) continue
                    val path = extractJsonValue(obj, "path")
                    val name = extractJsonValue(obj, "name")
                    val sizeStr = extractJsonValue(obj, "size")
                    var size = sizeStr.toLongOrNull() ?: 0L
                    if (size < 0) {
                        size += (4L * 1024 * 1024 * 1024)
                    }
                    if (name.isNotEmpty()) {
                        entries.add(FileEntry(path = path, name = name, size = size, category = category))
                    }
                }
                return entries
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}
