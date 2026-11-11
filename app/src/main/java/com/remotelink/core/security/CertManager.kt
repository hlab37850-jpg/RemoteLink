package com.remotelink.core.security

import android.content.Context
import com.remotelink.core.storage.Store
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * يولّد هوية الجهاز (شهادة TLS موقّعة ذاتيًا) مرة واحدة ويحفظها داخل مساحة التطبيق الخاصة.
 * الثقة تتم بتثبيت البصمة (certificate pinning) التي تصل عبر رمز QR وقت الاقتران.
 */
class CertManager(private val context: Context, private val store: Store) {
    private val file get() = File(context.filesDir, "identity.p12")
    @Volatile private var cached: KeyStore? = null
    @Volatile private var cachedFp: String? = null

    @Synchronized
    private fun identity(): KeyStore {
        cached?.let { return it }
        val pw = store.keystorePassword().toCharArray()
        if (file.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                file.inputStream().use { ks.load(it, pw) }
                if (ks.containsAlias(ALIAS)) {
                    cached = ks
                    return ks
                }
            } catch (_: Exception) {
                // ملف تالف: نعيد التوليد
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=RemoteLink-${store.deviceId().take(8)}")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()).add(BigInteger.ONE),
            Date(now - 86_400_000L),
            Date(now + 3650L * 86_400_000L),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, pw, arrayOf(cert))
        file.outputStream().use { ks.store(it, pw) }
        cached = ks
        return ks
    }

    /** بصمة SHA-256 للشهادة (hex صغير). */
    fun fingerprint(): String {
        cachedFp?.let { return it }
        val cert = identity().getCertificate(ALIAS) as X509Certificate
        return Crypto.sha256Hex(cert.encoded).also { cachedFp = it }
    }

    /** سياق TLS للخادم (الجوال المتحكَّم به). */
    fun serverSslContext(): SSLContext {
        val ks = identity()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, store.keystorePassword().toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** سياق TLS للعميل: لا يقبل إلا شهادة بنفس البصمة. */
    fun pinnedClientContext(expectedFp: String): SSLContext {
        val expected = expectedFp.lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                throw CertificateException("client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
                if (!Crypto.constantTimeEquals(Crypto.sha256Hex(leaf.encoded), expected)) {
                    throw CertificateException("fingerprint mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
    }

    private companion object {
        const val ALIAS = "remotelink"
    }
}
