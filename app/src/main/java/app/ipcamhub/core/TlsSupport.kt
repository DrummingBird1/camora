package app.ipcamhub.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * HTTPS for the stream server. A self-signed certificate is created once and its private key
 * stays inside the Android Keystore. Browsers will warn about the certificate; the connection is
 * still encrypted.
 */
object TlsSupport {
    // v2: the key must also allow DIGEST_NONE, which the TLS stack uses to sign pre-hashed data.
    private const val ALIAS = "ipcamhub_tls_v2"

    fun serverSocketFactory(): SSLServerSocketFactory {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias("ipcamhub_tls_v1")) ks.deleteEntry("ipcamhub_tls_v1") // unusable older key
        if (!ks.containsAlias(ALIAS)) {
            val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                    )
                    .setCertificateSubject(X500Principal("CN=Camora"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    .build()
            )
            generator.generateKeyPair()
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        return context.serverSocketFactory
    }
}
