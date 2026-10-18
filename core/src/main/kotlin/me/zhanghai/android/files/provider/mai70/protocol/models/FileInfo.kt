package me.zhanghai.android.files.provider.mai70.protocol.models

import java.time.Instant

/**
 * File metadata information (directory status, size, last modified time).
 *
 * @property isDirectory Indicates whether the resource is a directory.
 * @property size File size in bytes.
 * @property lastModifiedTime Last modified timestamp if available, or [Instant.EPOCH].
 */
data class FileInfo(
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedTime: Instant = Instant.EPOCH
)
