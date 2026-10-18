# 70mai-protocol

`70mai-protocol` is a generic, lightweight, pure-Kotlin shared library that implements the reverse-engineered Wi-Fi/HTTP CGI protocol for 70mai dashcams. It enables JVM and Android applications to pair, browse, stream, download, and delete media files on 70mai devices.

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

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**.

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

### Android Integration (Automatic Wi-Fi Network Binding)

```kotlin
import com.mai70.protocol.Mai70Client
import com.mai70.protocol.android.AndroidNetworkTransport
import com.mai70.protocol.models.Category

suspend fun syncDashcamFiles(context: Context) {
    // 1. Create Android HTTP transport (routes requests through camera Wi-Fi even with cellular data enabled)
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

### Pure Kotlin / JVM Integration

```kotlin
import com.mai70.protocol.Mai70Client
import com.mai70.protocol.TokenStore
import com.mai70.protocol.http.HttpTransport
import com.mai70.protocol.models.Authority
import com.mai70.protocol.models.Category

class FileTokenStore : TokenStore {
    private var token: String? = null
    override fun getToken(authority: Authority): String? = token
    override fun saveToken(authority: Authority, token: String) {
        this.token = token
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

---

## Testing with Real Hardware

To test all library functionalities on physical 70mai camera hardware:

### Setup
1. **Power on the 70mai dashcam** and ensure Wi-Fi hotspot mode is enabled on the camera.
2. **Connect your computer or Android test phone** to the dashcam's Wi-Fi hotspot (default IP `192.168.0.1`).
3. **Verify IP Connectivity**:
   ```bash
   ping 192.168.0.1
   ```

### Functionality Verification Checklist

1. **Pairing Handshake (`pair()`)**: Execute `client.pair()`, press physical power button on dashcam when prompted, verify returns token.
2. **Client Registration (`register()`)**: Call `client.register("192.168.0.2")`, verify returns `true`.
3. **Album Access Mode (`setAlbumMode()`)**: Call `client.setAlbumMode(true)`, verify camera pauses recording.
4. **Category File Counts (`getFileCounts()`)**: Call `client.getFileCounts()`, verify returns category counts map.
5. **Category File Listing (`listFiles()`)**: Call `client.listFiles(Category.NORMAL)`, verify list of `FileEntry` items returned.
6. **File Downloading (`openFile()`)**: Call `client.openFile(entry)`, stream bytes from open `InputStream`.
7. **Metadata Probing (`getFileInfo()`)**: Call `client.getFileInfo("Normal", "filename.MP4")`, verify metadata returned.
8. **File Deletion (`delete()`)**: Call `client.delete(entry)`, verify file removed from camera storage.
