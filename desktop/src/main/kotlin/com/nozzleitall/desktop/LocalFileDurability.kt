package com.nozzleitall.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Flush a published directory entry where the local file provider permits directory handles. */
object LocalFileDurability {
    fun syncDirectory(folder: File) {
        // The Windows JDK cannot open a directory as a FileChannel. Atomic rename is still used
        // on that platform; power-loss durability needs Windows package/runtime qualification.
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
