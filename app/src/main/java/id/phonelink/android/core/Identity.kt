package id.phonelink.android.core

import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * This device's self-signed certificate. On a phone the private key lives in Android Keystore, where the app can use it
 * but not read it. JVM tests have no Keystore, so there it stays in a PKCS12 file in the app's private folder.
 */
class LocalIdentity private constructor(
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
    /** True when the key is in Android Keystore. */
    val isHardwareBacked: Boolean,
) {
    val deviceId: String = sha256Hex(chain[0].encoded)

    fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(privateKey, chain)), arrayOf(AcceptAllTrustManager), SecureRandom())
        }

    /** Always offers this device's one key, which also works for a key that can't leave Android Keystore. */
    private class SingleKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
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

        private const val ANDROID_STORE = "AndroidKeyStore"
        /** Its own alias, so a key from an older install can't clash. */
        private const val KEYSTORE_ALIAS = "dysklink-identity"

        fun loadOrCreate(directory: File, useKeystore: Boolean = keystoreAvailable()): LocalIdentity {
            val storeFile = File(directory, "identity.p12")
            val passwordFile = File(directory, "identity.key")
            if (useKeystore) {
                runCatching { fromKeystore() }.getOrNull()?.let { identity ->
                    // A key file left over from a move that stopped halfway is removed now.
                    if (storeFile.exists() && fileIdentity(storeFile, passwordFile)?.deviceId == identity.deviceId) {
                        storeFile.delete(); passwordFile.delete()
                    }
                    return identity
                }
            }
            val existing = fileIdentity(storeFile, passwordFile) ?: create(storeFile, passwordFile)
            if (!useKeystore) return existing
            // The same key and certificate move into Keystore, so the device ID and pairings stay.
            return runCatching { moveToKeystore(existing) }
                .onSuccess { storeFile.delete(); passwordFile.delete() }
                .getOrElse { existing }
        }

        private fun keystoreAvailable(): Boolean =
            runCatching { KeyStore.getInstance(ANDROID_STORE).load(null); true }.getOrDefault(false)

        private fun fromKeystore(): LocalIdentity? {
            val store = KeyStore.getInstance(ANDROID_STORE).apply { load(null) }
            val key = store.getKey(KEYSTORE_ALIAS, null) as? PrivateKey ?: return null
            val chain = store.getCertificateChain(KEYSTORE_ALIAS)?.map { it as X509Certificate }?.toTypedArray() ?: return null
            if (chain.isEmpty()) return null
            return LocalIdentity(key, chain, isHardwareBacked = true)
        }

        private fun moveToKeystore(identity: LocalIdentity): LocalIdentity {
            val store = KeyStore.getInstance(ANDROID_STORE).apply { load(null) }
            val protection = android.security.keystore.KeyProtection.Builder(android.security.keystore.KeyProperties.PURPOSE_SIGN)
                .setDigests(
                    android.security.keystore.KeyProperties.DIGEST_NONE,
                    android.security.keystore.KeyProperties.DIGEST_SHA256,
                    android.security.keystore.KeyProperties.DIGEST_SHA384,
                    android.security.keystore.KeyProperties.DIGEST_SHA512,
                )
                .build()
            store.setEntry(KEYSTORE_ALIAS, KeyStore.PrivateKeyEntry(identity.privateKey, identity.chain), protection)
            val moved = fromKeystore() ?: error("The key didn't reach Keystore")
            check(moved.deviceId == identity.deviceId) { "Keystore gave back a different certificate" }
            // A TLS handshake signs with the key, so a key Keystore can't sign with is caught here, before the file goes.
            java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(moved.privateKey); update(byteArrayOf(1)); sign() }
            return moved
        }

        private fun fileIdentity(storeFile: File, passwordFile: File): LocalIdentity? {
            if (!storeFile.exists() || !passwordFile.exists()) return null
            val password = passwordFile.readText().toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            storeFile.inputStream().use { store.load(it, password) }
            val key = store.getKey(ALIAS, password) as PrivateKey
            val chain = store.getCertificateChain(ALIAS).map { it as X509Certificate }.toTypedArray()
            return LocalIdentity(key, chain, isHardwareBacked = false)
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
            return LocalIdentity(keyPair.private, arrayOf(certificate), isHardwareBacked = false)
        }
    }
}
