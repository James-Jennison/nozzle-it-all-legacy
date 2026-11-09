package com.nozzleitall.desktop

import com.nozzleitall.desktop.projects.safePreviewDimensions
import com.nozzleitall.desktop.projects.projectPreview
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ProjectPreviewTest {
    @Test fun rejectTruncatedOrOversizedPngBeforeDecode() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 1, 0, 0, 0, 1, 0)
        assertTrue(safePreviewDimensions(header))
        assertFalse(safePreviewDimensions(header.copyOfRange(0, 23)))
        val oversized = header.copyOf().apply { this[18] = 0x20; this[19] = 0x00 }
        assertFalse(safePreviewDimensions(oversized))
    }

    @Test fun invalidSmallPreviewFallsBackToTheNextValidEmbeddedImage() {
        val image = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val file = Files.createTempFile("project-preview", ".3mf").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Metadata/plate_1_small.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Metadata/plate_1.png")); zip.write(image); zip.closeEntry()
        }
        assertNotNull(projectPreview(file))
    }
}
