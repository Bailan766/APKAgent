package com.apkagent.apktools

import org.junit.Assert.*
import org.junit.Test

class DexParserTest {

    /** 构造一个最小的合法 DEX 文件头 */
    private fun buildMinimalDex(): ByteArray {
        val data = ByteArray(256)
        // magic: "dex\n039\0"
        val magic = "dex\n039\u0000".toByteArray(Charsets.US_ASCII)
        magic.copyInto(data, 0)
        // file_size @ 0x20
        data[0x20] = 0.toByte()
        data[0x21] = 0x01.toByte() // 256
        data[0x22] = 0.toByte()
        data[0x23] = 0.toByte()
        return data
    }

    @Test(expected = IllegalStateException::class)
    fun `parse throws on too small file`() {
        DexParser(ByteArray(10)).parse()
    }

    @Test(expected = IllegalStateException::class)
    fun `parse throws on invalid magic`() {
        val data = ByteArray(256)
        "notdex\n".toByteArray().copyInto(data, 0)
        DexParser(data).parse()
    }

    @Test
    fun `descriptorToName converts class descriptor`() {
        val parser = DexParser(ByteArray(0))
        assertEquals("com.foo.Bar", parser.descriptorToName("Lcom/foo/Bar;"))
    }

    @Test
    fun `descriptorToName handles arrays`() {
        val parser = DexParser(ByteArray(0))
        assertEquals("int[][]", parser.descriptorToName("[[I"))
        assertEquals("java.lang.String[]", parser.descriptorToName("[Ljava/lang/String;"))
    }

    @Test
    fun `descriptorToName handles primitives`() {
        val parser = DexParser(ByteArray(0))
        assertEquals("int", parser.descriptorToName("I"))
        assertEquals("boolean", parser.descriptorToName("Z"))
        assertEquals("void", parser.descriptorToName("V"))
    }
}
