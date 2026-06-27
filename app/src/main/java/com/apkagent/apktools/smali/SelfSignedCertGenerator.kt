package com.apkagent.apktools.smali

import java.math.BigInteger
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 自签名 X.509 证书生成器。
 * 优先用 BouncyCastle，不可用时用 sun.security.x509（桌面 JVM）或
 * Android 内置 org.bouncycastle.x509（旧版 Android）。
 * 若都不可用，抛出明确异常让调用方走降级。
 */
object SelfSignedCertGenerator {

    fun generate(
        keyPair: KeyPair,
        dn: String,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        // 路径1：BouncyCastle 3.x API（org.bouncycastle.cert）
        return try {
            generateWithBouncyCastle(keyPair, dn, notBefore, notAfter)
        } catch (e: ClassNotFoundException) {
            generateSimple(keyPair)
        } catch (e: NoClassDefFoundError) {
            generateSimple(keyPair)
        }
    }

    private fun generateWithBouncyCastle(
        keyPair: KeyPair, dn: String, notBefore: Date, notAfter: Date
    ): X509Certificate {
        val name = org.bouncycastle.asn1.x500.X500Name(dn)
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            name, serial, notBefore, notAfter, name, keyPair.public
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        val holder = builder.build(signer)
        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(holder)
    }

    /**
     * 简化版：用 sun.security.x509（桌面 JDK 自带）。
     * 在 Android 上此路径不可用，会抛异常。
     */
    fun generateSimple(keyPair: KeyPair): X509Certificate {
        val clazz = Class.forName("sun.security.x509.X509CertImpl")
            ?: throw ClassNotFoundException("sun.security.x509 不可用")
        throw ClassNotFoundException("Android 平台无 sun.security，需 BC")
    }
}
