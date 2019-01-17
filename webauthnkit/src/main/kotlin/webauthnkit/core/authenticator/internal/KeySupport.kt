package webauthnkit.core.authenticator.internal

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import webauthnkit.core.util.WKLogger
import webauthnkit.core.authenticator.*
import webauthnkit.core.util.ByteArrayUtil
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.security.auth.x500.X500Principal
import android.security.keystore.KeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKey

object KeyStoreType {
    const val Android = "AndroidKeyStore"
}

object CurveType {
    const val SECP256r1 = "secp256r1"
}

object SignAlgorithmType {
    const val SHA256WithRSA   = "SHA256withRSA"
    const val SHA256WithECDSA = "SHA256withECDSA"
}

object AttestationFormatType {
    const val AndroidKey = "android-key"
    const val Packed     = "packed"
}

@ExperimentalUnsignedTypes
class KeySupportChooser(val context: Context) {

    companion object {
        val TAG = this::class.simpleName
    }

    fun choose(algorithms: List<Int>): KeySupport? {
        WKLogger.d(TAG, "choose support module")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            chooseInternal(algorithms)
        } else {
            WKLogger.d(TAG, "this android version is below M, use legacy version")
            chooseLegacyInternal(algorithms)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun chooseInternal(algorithms: List<Int>): KeySupport? {
        for (alg in algorithms) {
            when (alg) {
                COSEAlgorithmIdentifier.es256 -> {
                    return ECDSAKeySupport(context, alg)
                }
                else -> {
                    WKLogger.d(TAG, "key support for this algorithm not found")
                }
            }
        }
        WKLogger.w(TAG, "no proper support module found")
        return null
    }

    private fun chooseLegacyInternal(algs: List<Int>): KeySupport? {
        for (alg in algs) {
            when (alg) {
                COSEAlgorithmIdentifier.rs256 -> {
                    return LegacyRSAKeySupport(context, alg)
                }
                else -> {
                    WKLogger.d(TAG, "key support for this algorithm not found")
                }
            }
        }
        WKLogger.w(TAG, "no proper support module found")
        return null
    }
}

@ExperimentalUnsignedTypes
interface KeySupport {
    val alg: Int
    fun createKeyPair(alias: String, clientDataHash: ByteArray): COSEKey?
    fun sign(alias: String, data: ByteArray): ByteArray?
    fun buildAttestationObject(
        alias:             String,
        clientDataHash:    UByteArray,
        authenticatorData: AuthenticatorData
    ): AttestationObject?
}

@TargetApi(Build.VERSION_CODES.M)
@ExperimentalUnsignedTypes
class ECDSAKeySupport(
    val context: Context,
    override val alg: Int
): KeySupport {

    companion object {
        val TAG = this::class.simpleName
    }

    override fun createKeyPair(alias: String, clientDataHash: ByteArray): COSEKey? {

        try {
            val generator =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,
                    KeyStoreType.Android)

            generator.initialize(createGenParameterSpec(alias, clientDataHash))

            val pubKey = generator.generateKeyPair().public
            val point = (pubKey as ECPublicKey).w
            point.affineX.toByteArray()
            point.affineY.toByteArray()

            return COSEKeyEC2(
                alg = alg,
                crv = COSEKeyCurveType.p256,
                x   = point.affineX.toByteArray().toUByteArray(),
                y   = point.affineY.toByteArray().toUByteArray()

            )
        } catch (e: Exception) {
            WKLogger.w(TAG, "failed to create key pair: " + e.localizedMessage)
            return null
        }

    }

    private fun createGenParameterSpec(alias: String, clientDataHash: ByteArray): KeyGenParameterSpec {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createGenParameterSpecN(alias, clientDataHash)
        } else {
            createGenParameterSpecM(alias)
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun createGenParameterSpecN(alias: String, clientDataHash: ByteArray): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CurveType.SECP256r1))
            .setDigests(
                KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .setUserAuthenticationValidityDurationSeconds(5 * 60)
            .setAttestationChallenge(clientDataHash)
            .build()
    }

    private fun createGenParameterSpecM(alias: String): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CurveType.SECP256r1))
            .setDigests(
                KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            //.setUserAuthenticationValidityDurationSeconds(5 * 60)
            .build()
    }

    override fun sign(alias: String, data: ByteArray): ByteArray? {
        val keyStore = KeyStore.getInstance(KeyStoreType.Android)
        keyStore.load(null)
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val signer = Signature.getInstance(SignAlgorithmType.SHA256WithECDSA)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    private fun useSecureHardware(alias: String): Boolean {
        val keyStore = KeyStore.getInstance(KeyStoreType.Android)
        val key = keyStore.getKey(alias, null) as SecretKey
        val factory = SecretKeyFactory.getInstance(key.algorithm, KeyStoreType.Android)
        val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return keyInfo.isInsideSecureHardware
        // && keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware
    }

    override fun buildAttestationObject(
        alias:             String,
        clientDataHash:    UByteArray,
        authenticatorData: AuthenticatorData
    ): AttestationObject? {

        val authenticatorDataBytes = authenticatorData.toBytes()
        if (authenticatorDataBytes == null) {
            WKLogger.d(TAG, "failed to build authenticator data")
            return null
        }

        val bytesToBeSigned =
            ByteArrayUtil.merge(authenticatorDataBytes, clientDataHash)

        val sig = this.sign(alias, bytesToBeSigned.toByteArray())
        if (sig == null) {
            WKLogger.d(TAG, "failed to sign authenticator data")
            return null
        }

        val attStmt = HashMap<String, Any>()
        attStmt["alg"] = alg
        attStmt["sig"] = sig

        if (useSecureHardware(alias)) {

            WKLogger.d(TAG,
                "this android device supports secure-hardware, "
                    + "so, use 'attestation-key' format")

            val keyStore = KeyStore.getInstance(KeyStoreType.Android)
            keyStore.load(null)

            val certs = keyStore.getCertificateChain(alias)

            val x5c = ArrayList<ByteArray>()
            for (cert in certs) {
                x5c.add((cert as X509Certificate).encoded)
            }

            attStmt["x5c"] = x5c

            return AttestationObject(
                fmt      = AttestationFormatType.AndroidKey,
                authData = authenticatorData,
                attStmt  = attStmt
            )

        } else {

            WKLogger.d(TAG,
                "this android device doesn't support secure-hardware, "
                    + "so, build self attestation")

            return AttestationObject(
                fmt      = AttestationFormatType.Packed,
                authData = authenticatorData,
                attStmt  = attStmt
            )
        }

    }
}

@ExperimentalUnsignedTypes
class LegacyRSAKeySupport(
    val context: Context,
    override val alg: Int
): KeySupport {

    companion object {
        val TAG = this::class.simpleName
    }

    override fun createKeyPair(alias: String, clientDataHash: ByteArray): COSEKey? {
        try {
            val generator =
                KeyPairGenerator.getInstance("RSA",
                    KeyStoreType.Android)
            generator.initialize(this.createKeyPairSpec(alias))
            val pubKey = generator.generateKeyPair().public
            val n = (pubKey as RSAPublicKey).modulus.toByteArray().toUByteArray()
            val e = pubKey.publicExponent.toByteArray().toUByteArray()

            return COSEKeyRSA(
                alg = alg,
                n   = n,
                e   = e
            )
        } catch (e: Exception) {
            WKLogger.w(TAG, "failed to create key pair" + e.localizedMessage)
            return null
        }
    }

    private fun createKeyPairSpec(alias: String): KeyPairGeneratorSpec {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        end.add(Calendar.YEAR, 100)
        return KeyPairGeneratorSpec.Builder(context)
            .setAlias(alias)
            .setSubject(X500Principal("CN=$alias"))
            .setSerialNumber(BigInteger.valueOf(1000000))
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
    }

    override fun sign(alias: String, data: ByteArray): ByteArray? {
        val keyStore = KeyStore.getInstance(KeyStoreType.Android)
        keyStore.load(null)
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry == null) {
            WKLogger.d(TAG, "failed to obtain private key from KeyStore")
            return null
        }
        val signer = Signature.getInstance(SignAlgorithmType.SHA256WithRSA)
        signer.initSign(entry.privateKey)
        signer.update(data)
        return signer.sign()
    }

    override fun buildAttestationObject(
        alias:             String,
        clientDataHash:    UByteArray,
        authenticatorData: AuthenticatorData
    ): AttestationObject? {

        val authenticatorDataBytes = authenticatorData.toBytes()
        if (authenticatorDataBytes == null) {
            WKLogger.d(TAG, "failed to build authenticator data")
            return null
        }

        val bytesToBeSigned =
            ByteArrayUtil.merge(authenticatorDataBytes, clientDataHash)

        val sig = this.sign(alias, bytesToBeSigned.toByteArray())
        if (sig == null) {
            WKLogger.d(TAG, "failed to sign authenticator data")
            return null
        }

        val attStmt = HashMap<String, Any>()
        attStmt["alg"] = alg
        attStmt["sig"] = sig

        return AttestationObject(
            fmt      = AttestationFormatType.Packed,
            authData = authenticatorData,
            attStmt  = attStmt
        )
    }
}

