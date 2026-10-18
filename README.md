# 70mai-protocol

`70mai-protocol` is a lightweight, pure-Kotlin cross-platform shared library that extracts and standardizes the reverse-engineered Wi-Fi/HTTP CGI protocol for 70mai dashcams. It allows Kotlin/JVM and Android applications to easily pair, browse, stream, download, and delete files on 70mai camera devices.

---

## Architecture Overview

The library is organized into two primary modules:

```
70mai-protocol/
├── core/                          # Pure Kotlin, zero Android dependencies
│   ├── Mai70Client.kt             # Main façade client
│   ├── Mai70Auth.kt               # Pairing and registration handshake
│   ├── Mai70Protocol.kt           # MD5 signing, URL builders, JSON parsers
│   ├── TokenStore.kt              # Persistence abstraction for session tokens
│   ├── Mai70Exception.kt          # Domain exception hierarchy
│   ├── models/                    # Domain data models
│   │   ├── Authority.kt
│   │   ├── Category.kt            # Normal, Event, Parking, Photo, Unknown, TimeLapse
│   │   ├── FileEntry.kt
│   │   └── FileInfo.kt
│   └── http/
│       └── HttpTransport.kt       # Minimal transport abstraction
│
└── android/                       # Optional Android artifact
    └── AndroidNetworkTransport.kt # OkHttp implementation with Wi-Fi/SocketFactory binding
```

---

## License

This project is released under the **GNU General Public License v3.0 (GPLv3)** to maintain full compatibility with both upstream projects (*MaterialFiles* and *70mai_file_sync*).

---

## Pairing Sequence & Protocol Details

1. **Initiate Binding**:
   - Endpoint: `/cgi-bin/BindByBanya.cgi?&-usr={userId}&-signkey={MD5(userId + "73VpsAfdety8FDd0")}`
   - Returns temporary token and camera timestamp.

2. **User Confirmation (Physical Button Press)**:
   - Endpoint: `/cgi-bin/UserconfirmByBanya.cgi?&-timestamp={timestamp}&-signkey={MD5(timestamp + "73VpsAfdety8FDd0")}`
   - Polled until the user presses the power button on the dashcam. Returns final session token.

3. **Client Registration (Keep-Alive)**:
   - Endpoint: `/cgi-bin/client.cgi?&-operation=register&-ip={clientIp}&-timestamp={timestamp}&-signkey={sign}`
   - Required before performing remote operations.

4. **Album Access Mode**:
   - Endpoint: `/cgi-bin/setaccessalbum.cgi?&-enable=0&-timestamp={timestamp}&-signkey={sign}`
   - Pauses camera recording and enables file listing and streaming on supported models.

5. **Signature Algorithm**:
   - All authenticated requests append `&-signkey={MD5(queryPath + queryParams + sessionToken)}`.

---

## Usage Examples

### 1. 70mai_file_sync Style (Full Pairing + Auto-Sync)

```kotlin
import me.zhanghai.android.files.provider.mai70.protocol.Mai70Client
import me.zhanghai.android.files.provider.mai70.protocol.android.AndroidNetworkTransport
import me.zhanghai.android.files.provider.mai70.protocol.models.Category

suspend fun syncDashcamFiles(context: Context) {
    // 1. Create Android HTTP transport (handles mobile data / camera Wi-Fi routing automatically)
    val transport = AndroidNetworkTransport(context, defaultCameraIp = "192.168.0.1")

    // 2. Instantiate client
    val client = Mai70Client(transport = transport)

    // 3. Perform pairing (prompts user to press camera button)
    val token = client.pair(
        onWaitingForButton = {
            println("Please press the physical power button on your 70mai dashcam to approve pairing...")
        }
    )

    // 4. Fetch list of normal video recordings
    val normalVideos = client.listFiles(Category.NORMAL)
    for (video in normalVideos) {
        println("Found video: ${video.name} (${video.size} bytes)")

        // Open file stream to download/save locally
        client.openFile(video).use { inputStream ->
            // Save or stream video contents
        }
    }
}
```

### 2. MaterialFiles Style (Custom TokenStore / Authenticator)

```kotlin
import me.zhanghai.android.files.provider.mai70.protocol.Mai70Client
import me.zhanghai.android.files.provider.mai70.protocol.TokenStore
import me.zhanghai.android.files.provider.mai70.protocol.models.Authority
import me.zhanghai.android.files.provider.mai70.protocol.models.Category

class PersistentTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override fun getToken(authority: Authority): String? =
        prefs.getString("token_${authority.host}_${authority.port}", null)

    override fun saveToken(authority: Authority, token: String) {
        prefs.edit().putString("token_${authority.host}_${authority.port}", token).apply()
    }
}

suspend fun browseFileSystem(transport: HttpTransport, tokenStore: TokenStore) {
    val client = Mai70Client(
        transport = transport,
        tokenStore = tokenStore,
        host = "192.168.0.1",
        port = 80
    )

    // Query file counts per category
    val counts = client.getFileCounts()
    println("Normal videos count: ${counts[Category.NORMAL] ?: 0}")

    // List and delete remote file
    val eventFiles = client.listFiles(Category.EVENT)
    if (eventFiles.isNotEmpty()) {
        client.delete(eventFiles.first())
    }
}
```
