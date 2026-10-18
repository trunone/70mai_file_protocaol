# Migration Notes for 70mai-protocol

This document describes how to migrate both upstream projects (*MaterialFiles* and *70mai_file_sync*) to consume the shared `70mai-protocol` library.

---

## 1. MaterialFiles Migration

### Files to Delete or Replace in `app/src/main/java/me/zhanghai/android/files/provider/mai70/`

- **Delete/Replace**:
  - `client/Client.kt`: Replace usages with `Mai70Client`.
  - `client/Protocol.kt`: Delete; replaced by `Mai70Protocol`.
  - `client/Authority.kt`: Replace with `me.zhanghai.android.files.provider.mai70.protocol.models.Authority`.

- **Retain / Adapt**:
  - `client/Authenticator.kt`: Can bridge `Authenticator` directly to `TokenStore`:
    ```kotlin
    class MaterialFilesTokenStore(private val authenticator: Authenticator) : TokenStore {
        override fun getToken(authority: Authority): String? {
            return authenticator.getToken(
                me.zhanghai.android.files.provider.mai70.client.Authority(
                    protocol = Protocol.MAI70,
                    host = authority.host,
                    port = authority.port,
                    token = authority.token
                )
            )
        }
        override fun saveToken(authority: Authority, token: String) {}
    }
    ```
  - `client/FileByteChannel.kt`: Update constructors/references to use `Mai70Client.getFileUrlCandidates()` or `HttpTransport`.

---

## 2. 70mai_file_sync Migration

### Files to Delete or Replace in `app/src/main/java/com/lz1bgb/camhttp/`

- **Delete**:
  - `HttpClient.kt`: Delete or refactor into a thin wrapper around `AndroidNetworkTransport` and `Mai70Client`.
  - `HttpAuthentication.kt`: Delete completely; pairing logic is fully implemented inside `Mai70Client.pair()` and `Mai70Auth`.

- **Updated Service Implementation**:
  In `FileTransferService.kt` (or background sync job):

  ```kotlin
  // Old code:
  // val httpClient = HttpClient(context)
  // val auth = HttpAuthentication(httpClient)
  // auth.performAuthenticationLogic()
  // val files = httpClient.getFileList(0)

  // New code with 70mai-protocol:
  val transport = AndroidNetworkTransport(context, defaultCameraIp = "192.168.0.1")
  val client = Mai70Client(transport = transport)

  // Pairing (if not already paired) or register:
  val token = client.pair()

  // Listing files:
  val files = client.listFiles(Category.NORMAL)
  for (file in files) {
      client.openFile(file).use { input ->
          // Save file stream to local destination
      }
  }
  ```

---

## Benefits of Migration

1. **Shared Maintenance**: Bug fixes to camera pagination, fallback query formats, and deletion candidates are maintained in a single core library.
2. **Robust Network Binding**: Background file sync services leverage Android socket factory routing to communicate over Wi-Fi while mobile cellular data remains active.
3. **Clean Architecture**: `core` module is pure Kotlin (free of Android SDK dependencies), allowing execution in desktop CLI tools, JVM servers, or Android apps.
