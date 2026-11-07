package id.phonelink.android.core

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** This device's self-signed certificate, stored in the app's private folder. */
class LocalIdentity private constructor(
    private val keyStore: KeyStore,
    private val password: CharArray,
) {
    val deviceId: String =
        sha256Hex(keyStore.getCertificate(ALIAS).encoded)

    fun sslContext(): SSLContext {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(AcceptAllTrustManager), SecureRandom())
        }
    }

    /** Trust comes from the fingerprint at pairing, not the certificate chain. */
    private object AcceptAllTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "phonelink"
        private const val VALID_MS = 20L * 365 * 24 * 60 * 60 * 1000

        fun loadOrCreate(directory: File): LocalIdentity {
            val storeFile = File(directory, "identity.p12")
            val passwordFile = File(directory, "identity.key")
            if (storeFile.exists() && passwordFile.exists()) {
                val password = passwordFile.readText().toCharArray()
                val store = KeyStore.getInstance("PKCS12")
                storeFile.inputStream().use { store.load(it, password) }
                return LocalIdentity(store, password)
            }
            return create(storeFile, passwordFile)
        }

        private fun create(storeFile: File, passwordFile: File): LocalIdentity {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = generator.generateKeyPair()

            val bouncyCastle = BouncyCastleProvider()
            val subject = X500Name("CN=PhoneLink")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(128, SecureRandom()),
                Date(now - 24 * 60 * 60 * 1000),
                Date(now + VALID_MS),
                subject,
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").setProvider(bouncyCastle).build(keyPair.private))
            val certificate = JcaX509CertificateConverter().setProvider(bouncyCastle).getCertificate(holder)

            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val password = Base64.getEncoder().encodeToString(secret).toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password)
            store.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))

            storeFile.outputStream().use { store.store(it, password) }
            passwordFile.writeText(String(password))
            return LocalIdentity(store, password)
        }
    }
}
