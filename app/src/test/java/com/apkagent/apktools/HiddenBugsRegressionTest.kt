package com.apkagent.apktools

import com.apkagent.apktools.advanced.ZipAlignTool
import com.apkagent.apktools.smali.SignatureCheckerScanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class HiddenBugsRegressionTest {

    /**
     * 构造符合 AOSP 标准格式的最小二进制 AXML：
     * - 0x0001 (RES_STRING_POOL_TYPE, headerSize=0x001C)
     * - 0x0180 (RES_XML_RESOURCE_MAP_TYPE)
     * - 0x0100 (RES_XML_START_NAMESPACE_TYPE)
     * - 0x0102 (RES_XML_START_ELEMENT_TYPE: <application android:debuggable="false">)
     * - 0x0103 (RES_XML_END_ELEMENT_TYPE: </application>)
     */
    private fun buildSampleBinaryAxml(stripAttrNameString: Boolean = false): ByteArray {
        val strings = listOf(
            if (stripAttrNameString) "" else "debuggable", // idx 0 (maps to 0x0101000f)
            AxmlParser.ANDROID_NS_URI,                     // idx 1
            "android",                                     // idx 2
            "application"                                  // idx 3
        )

        // 1. String Pool Chunk (0x0001)
        val encodedStrings = strings.map { s ->
            val chars = s.toByteArray(Charsets.UTF_16LE)
            val b = ByteBuffer.allocate(2 + chars.size + 2).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(s.length.toShort())
            b.put(chars)
            b.putShort(0)
            b.array()
        }
        val offsets = IntArray(strings.size)
        var curOff = 0
        for (i in encodedStrings.indices) {
            offsets[i] = curOff
            curOff += encodedStrings[i].size
        }
        val poolDataSize = (curOff + 3) and -4
        val stringsStart = 28 + strings.size * 4
        val stringPoolChunkSize = stringsStart + poolDataSize
        val spBuf = ByteBuffer.allocate(stringPoolChunkSize).order(ByteOrder.LITTLE_ENDIAN)
        spBuf.putShort(0x0001) // RES_STRING_POOL_TYPE
        spBuf.putShort(0x001C) // headerSize = 28
        spBuf.putInt(stringPoolChunkSize)
        spBuf.putInt(strings.size) // stringCount
        spBuf.putInt(0)            // styleCount
        spBuf.putInt(0)            // flags (UTF-16)
        spBuf.putInt(stringsStart) // stringsStart
        spBuf.putInt(0)            // stylesStart
        offsets.forEach { spBuf.putInt(it) }
        encodedStrings.forEach { spBuf.put(it) }
        while (spBuf.position() < stringPoolChunkSize) spBuf.put(0)

        // 2. Resource Map Chunk (0x0180)
        val resMapBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        resMapBuf.putShort(0x0180)
        resMapBuf.putShort(8)
        resMapBuf.putInt(12)
        resMapBuf.putInt(AxmlParser.ATTR_DEBUGGABLE) // idx 0 -> 0x0101000f

        // 3. Start Namespace Chunk (0x0100)
        val nsBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        nsBuf.putShort(0x0100)
        nsBuf.putShort(16)
        nsBuf.putInt(24)
        nsBuf.putInt(1)  // lineNumber
        nsBuf.putInt(-1) // comment
        nsBuf.putInt(2)  // prefixIdx = "android"
        nsBuf.putInt(1)  // uriIdx = ANDROID_NS_URI

        // 4. Start Element Chunk (0x0102): <application android:debuggable="false">
        val startTagBuf = ByteBuffer.allocate(36 + 20).order(ByteOrder.LITTLE_ENDIAN)
        startTagBuf.putShort(0x0102)
        startTagBuf.putShort(16)
        startTagBuf.putInt(56)
        startTagBuf.putInt(2)  // line
        startTagBuf.putInt(-1) // comment
        startTagBuf.putInt(-1) // nsIdx
        startTagBuf.putInt(3)  // nameIdx = "application"
        startTagBuf.putShort(20) // attrStart
        startTagBuf.putShort(20) // attrSize
        startTagBuf.putShort(1)  // attrCount
        startTagBuf.putShort(0)
        startTagBuf.putShort(0)
        startTagBuf.putShort(0)
        // Attribute 0: android:debuggable="false"
        startTagBuf.putInt(1)  // aNsIdx = ANDROID_NS_URI
        startTagBuf.putInt(0)  // aNameIdx = 0 ("debuggable" / 0x0101000f)
        startTagBuf.putInt(-1) // aRawVal = -1
        startTagBuf.putShort(8)
        startTagBuf.put(0)
        startTagBuf.put(0x12)  // TYPE_INT_BOOLEAN
        startTagBuf.putInt(0)  // false

        // 5. End Element Chunk (0x0103): </application>
        val endTagBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        endTagBuf.putShort(0x0103)
        endTagBuf.putShort(16)
        endTagBuf.putInt(24)
        endTagBuf.putInt(3)
        endTagBuf.putInt(-1)
        endTagBuf.putInt(-1)
        endTagBuf.putInt(3)

        val bodyBytes = ByteArrayOutputStream().apply {
            write(spBuf.array())
            write(resMapBuf.array())
            write(nsBuf.array())
            write(startTagBuf.array())
            write(endTagBuf.array())
        }.toByteArray()

        val totalSize = 8 + bodyBytes.size
        val fileBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        fileBuf.putShort(0x0003)
        fileBuf.putShort(0x0008)
        fileBuf.putInt(totalSize)
        fileBuf.put(bodyBytes)
        return fileBuf.array()
    }

    @Test
    fun `AxmlParser parses 0x0001 string pool, 0x0100 namespace, and 0x0180 resource map`() {
        val rawAxml = buildSampleBinaryAxml(stripAttrNameString = false)
        assertTrue(AxmlParser.isBinaryAxml(rawAxml))

        val xml = AxmlParser(rawAxml).parse()
        assertTrue("Expected <application in XML, got: $xml", xml.contains("<application"))
        assertTrue("Expected android:debuggable=\"false\" in XML, got: $xml", xml.contains("""android:debuggable="false""""))

        // 测试属性名被资源混淆剥离为空串时，通过 0x0180 资源 ID (0x0101000f) 自动还原
        val strippedAxml = buildSampleBinaryAxml(stripAttrNameString = true)
        val strippedXml = AxmlParser(strippedAxml).parse()
        assertTrue("Expected android:debuggable=\"false\" from resource map, got: $strippedXml", strippedXml.contains("""android:debuggable="false""""))
    }

    @Test
    fun `AxmlParser patchBinaryManifest modifies boolean attributes in-place without corrupting AXML`() {
        val rawAxml = buildSampleBinaryAxml(stripAttrNameString = false)
        val (patchedAxml, changes) = AxmlParser.patchBinaryManifest(rawAxml, debuggable = true)
        assertTrue(changes.isNotEmpty())
        assertTrue(AxmlParser.isBinaryAxml(patchedAxml))

        val patchedXml = AxmlParser(patchedAxml).parse()
        assertTrue("Expected android:debuggable=\"true\" after binary patch, got: $patchedXml", patchedXml.contains("""android:debuggable="true""""))
    }

    @Test
    fun `SignatureCheckerScanner patchMethodReturnTrue does not duplicate rest of smali file`() {
        val smali = """
            .class public Lcom/example/SigCheck;
            .super Ljava/lang/Object;

            .method public static verify(Landroid/content/Context;)Z
                .locals 0
                const/4 p0, 0x0
                return p0
            .end method

            .method public static helper()V
                .locals 1
                return-void
            .end method
        """.trimIndent()

        val patched = SignatureCheckerScanner.patchMethodReturnTrue(smali, "verify")
        // 确保 .locals 0 被安全提升为 .locals 1
        assertTrue(patched.contains(".locals 1"))
        assertTrue(patched.contains("const/4 v0, 0x1\n    return v0"))
        // 确保后续方法 helper()V 只出现一次，绝不重复拼接文件尾部
        val helperOccurrences = Regex("""\.method public static helper\(\)V""").findAll(patched).count()
        assertEquals("Subsequent methods must not be duplicated!", 1, helperOccurrences)
    }

    @Test
    fun `ZipAlignTool aligns stored resources_arsc without prepending padding bytes into file content`() {
        val tmpDir = Files.createTempDirectory("zipalign_test").toFile()
        try {
            val unalignedApk = File(tmpDir, "unaligned.apk")
            val alignedApk = File(tmpDir, "aligned.apk")
            val expectedArsc = ByteArray(128) { (it + 1).toByte() }

            ZipOutputStream(unalignedApk.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write("dex\n035\u0000dummy_dex_payload".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("resources.arsc"))
                zos.write(expectedArsc)
                zos.closeEntry()
            }

            val res = ZipAlignTool.alignApk(unalignedApk, alignedApk, 4)
            assertTrue(res.message, res.success)
            assertTrue(alignedApk.exists())

            ZipFile(alignedApk).use { zip ->
                val arscEntry = zip.getEntry("resources.arsc")
                assertEquals(ZipEntry.STORED, arscEntry.method)
                val actualArsc = zip.getInputStream(arscEntry).readBytes()
                assertArrayEquals("resources.arsc content must not be corrupted by padding bytes!", expectedArsc, actualArsc)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `EmbeddedPythonEngine executes print, arithmetic, crypto, comprehensions and androguard out of the box`() {
        val tmpDir = Files.createTempDirectory("py_engine_test").toFile()
        try {
            // 1. Screenshot case 1: print('hello')
            val r1 = EmbeddedPythonEngine.execute("print('hello')", tmpDir)
            assertTrue(r1.combined, r1.success)
            assertEquals("hello", r1.output)

            // 2. Screenshot case 2: print(1+1)
            val r2 = EmbeddedPythonEngine.execute("print(1+1)", tmpDir)
            assertTrue(r2.combined, r2.success)
            assertEquals("2", r2.output)

            // 3. Reverse engineering script: XOR + base64 + hashlib + functions + comprehensions
            val script = """
                import base64, hashlib, re
                def xor_dec(data, key):
                    return bytes([b ^ key for b in data]).decode('utf-8')
                enc = [b ^ 0x42 for b in b'APKAgent_Secret_42']
                plain = xor_dec(enc, 0x42)
                b64 = base64.b64encode(plain.encode()).decode()
                md5 = hashlib.md5(plain.encode()).hexdigest()
                nums = re.findall(r'\d+', plain)
                print(f"{plain}|{b64}|{len(md5)}|{nums[0]}")
            """.trimIndent()
            val r3 = EmbeddedPythonEngine.execute(script, tmpDir)
            assertTrue(r3.combined, r3.success)
            assertEquals("APKAgent_Secret_42|QVBLQWdlbnRfU2VjcmV0XzQy|32|42", r3.output)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
