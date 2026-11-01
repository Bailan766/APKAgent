package com.apkagent.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectIntelligenceTest {

    @Test
    fun detect_packer_identifies_known_signatures() {
        val tmp = kotlin.io.path.createTempFile("test-packer", ".apk").toFile()
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libjiagu.so"))
            zos.write("fake-360-so".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(500)) // small dex
            zos.closeEntry()
        }

        val result = ProjectIntelligence.detectPacker(tmp)
        assertTrue(result.isPacked)
        assertTrue(result.detectedPackers.contains("360加固"))
        assertTrue(result.protectionScore >= 60)
        tmp.delete()
    }

    @Test
    fun detect_tech_stack_identifies_flutter_and_sdk() {
        val tmp = kotlin.io.path.createTempFile("test-tech", ".apk").toFile()
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libflutter.so"))
            zos.write("flutter".toByteArray())
            zos.closeEntry()
        }

        val classes = listOf(
            "io.flutter.embedding.android.FlutterActivity",
            "com.tencent.mm.opensdk.openapi.WXAPIFactory",
            "okhttp3.OkHttpClient"
        )

        val result = ProjectIntelligence.detectTechStack(tmp, classes)
        assertTrue(result.frameworks.contains("Flutter"))
        assertTrue(result.sdks.contains("微信 OpenSDK"))
        assertTrue(result.sdks.contains("OkHttp"))
        tmp.delete()
    }

    @Test
    fun analyze_native_libs_groups_by_abi() {
        val tmp = kotlin.io.path.createTempFile("test-native", ".apk").toFile()
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so"))
            zos.write("native-arm64".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/armeabi-v7a/libnative.so"))
            zos.write("native-armv7".toByteArray())
            zos.closeEntry()
        }

        val summary = ProjectIntelligence.analyzeNativeLibs(tmp)
        assertEquals(2, summary.abis.size)
        assertTrue(summary.abis.containsKey("arm64-v8a"))
        assertTrue(summary.abis.containsKey("armeabi-v7a"))
        tmp.delete()
    }
}
