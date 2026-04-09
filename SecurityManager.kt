package com.arus.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

import org.bouncycastle.pqc.crypto.crystals.kyber.*

object SecurityManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128


    private lateinit var prefs: SharedPreferences

    private var quantumKeyPair: KyberKeyPair? = null
    private var cachedMasterAesKey: SecretKey? = null

    data class KyberKeyPair(val public: KyberPublicKeyParameters, val private: KyberPrivateKeyParameters)

    fun init(context: Context, isRecovery: Boolean = false) {
        prefs = context.getSharedPreferences("arus_quantum_vault", Context.MODE_PRIVATE)

        if (!isRecovery) {
            getOrGenerateMasterAesKey()
        } else {
            lockVault() 
        }
    }

    @Synchronized
    fun lockVault() {
        cachedMasterAesKey = null
        quantumKeyPair = null
    }

    fun hasMasterKey(): Boolean {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.containsAlias("ARUS_HARDWARE_VAULT") &&
                    ks.getKey("ARUS_HARDWARE_VAULT", null) != null &&
                    prefs.contains("kyber_priv")
        } catch (e: Exception) {
            false
        }
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Synchronized
    private fun getOrCreateHardwareKey(): SecretKey {
        val alias = "ARUS_HARDWARE_VAULT"
        val existingKey = keyStore.getKey(alias, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return keyGen.generateKey()
    }

    private fun protectData(data: ByteArray): String {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateHardwareKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val enc = Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        return "$iv::$enc"
    }

    private fun unprotectData(encrypted: String): ByteArray {
        val parts = encrypted.split("::")
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareKey(), GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
    }


    private fun deriveKeyFromMasterPassword(password: String, email: String): SecretKey {
        val salt = email.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, 15000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(secretBytes, "AES")
    }

    fun exportVaultForCloud(masterPassword: String, userEmail: String): Pair<String, String> {
        val keys = getOrGenerateQuantumKeys()
        getOrGenerateMasterAesKey()

        val pubBase64 = Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP)
        val privBase64 = Base64.encodeToString(keys.private.encoded, Base64.NO_WRAP)

        val aesEnc = prefs.getString("encrypted_master_aes", "") ?: ""
        val aesCapsule = prefs.getString("master_aes_capsule", "") ?: ""

        val packedPub = "$pubBase64||$aesEnc"
        val packedPrivRaw = "$privBase64||$aesCapsule"

        val zkpKey = deriveKeyFromMasterPassword(masterPassword, userEmail)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, zkpKey)

        val cipherText = cipher.doFinal(packedPrivRaw.toByteArray(Charsets.UTF_8))
        val ivStr = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val cipherStr = Base64.encodeToString(cipherText, Base64.NO_WRAP)

        val securedPrivVault = "$ivStr::$cipherStr"

        return Pair(packedPub, securedPrivVault)
    }

    fun restoreVaultFromCloud(pubBase64: String, securedPrivVault: String, masterPassword: String, userEmail: String): Boolean {
        try {
            val zkpKey = deriveKeyFromMasterPassword(masterPassword, userEmail)
            val privPartsEnc = securedPrivVault.split("::")
            if (privPartsEnc.size != 2) {
                return false
            }

            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, zkpKey, GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(privPartsEnc[0], Base64.NO_WRAP)))

            val decryptedPrivRaw = String(cipher.doFinal(Base64.decode(privPartsEnc[1], Base64.NO_WRAP)), Charsets.UTF_8)

            val pubParts = pubBase64.split("||")
            val privParts = decryptedPrivRaw.split("||")

            if (pubParts.isEmpty() || privParts.isEmpty()) {
                return false
            }

            val pubBytes = Base64.decode(pubParts[0], Base64.NO_WRAP)
            val privBytes = Base64.decode(privParts[0], Base64.NO_WRAP)

            val editor = prefs.edit()
                .putString("kyber_pub", protectData(pubBytes))
                .putString("kyber_priv", protectData(privBytes))

            if (pubParts.size > 1 && privParts.size > 1) {
                editor.putString("encrypted_master_aes", pubParts[1])
                    .putString("master_aes_capsule", privParts[1])
            }

            editor.apply()

            lockVault()

            return true

        } catch (e: javax.crypto.AEADBadTagException) {
            return false
        } catch (e: Exception) {
            return false
        }
    }

    @Synchronized
    private fun getOrGenerateQuantumKeys(): KyberKeyPair {
        quantumKeyPair?.let { return it }

        val savedPub = prefs.getString("kyber_pub", null)
        val savedPriv = prefs.getString("kyber_priv", null)

        if (savedPub != null && savedPriv != null) {
            try {
                quantumKeyPair = KyberKeyPair(
                    KyberPublicKeyParameters(KyberParameters.kyber768, unprotectData(savedPub)),
                    KyberPrivateKeyParameters(KyberParameters.kyber768, unprotectData(savedPriv))
                )
                return quantumKeyPair!!
            } catch (e: Exception) {
                
            }
        }

        val keyPairGen = KyberKeyPairGenerator()
        keyPairGen.init(KyberKeyGenerationParameters(SecureRandom(), KyberParameters.kyber768))
        val kp = keyPairGen.generateKeyPair()

        val pubParams = kp.public as KyberPublicKeyParameters
        val privParams = kp.private as KyberPrivateKeyParameters

        prefs.edit()
            .putString("kyber_pub", protectData(pubParams.encoded))
            .putString("kyber_priv", protectData(privParams.encoded))
            .apply()

        quantumKeyPair = KyberKeyPair(pubParams, privParams)
        return quantumKeyPair!!
    }

    @Synchronized
    private fun getOrGenerateMasterAesKey(): SecretKey {
        cachedMasterAesKey?.let { return it }

        val encAesBase64 = prefs.getString("encrypted_master_aes", null)
        val capsuleBase64 = prefs.getString("master_aes_capsule", null)

        if (encAesBase64 != null && capsuleBase64 != null) {
            try {
                val keys = getOrGenerateQuantumKeys()
                val extractor = KyberKEMExtractor(keys.private)
                val capsule = Base64.decode(capsuleBase64, Base64.NO_WRAP)
                val secretKeyBytes = extractor.extractSecret(capsule)

                val cipher = Cipher.getInstance(AES_MODE)
                val encParts = encAesBase64.split("::")
                val iv = Base64.decode(encParts[0], Base64.NO_WRAP)
                val cipherText = Base64.decode(encParts[1], Base64.NO_WRAP)

                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKeyBytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val aesKeyBytes = cipher.doFinal(cipherText)

                cachedMasterAesKey = SecretKeySpec(aesKeyBytes, "AES")
                return cachedMasterAesKey!!
            } catch (e: Exception) {
                
            }
        }

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        val newAesKey = keyGen.generateKey()

        val keys = getOrGenerateQuantumKeys()
        val generator = KyberKEMGenerator(SecureRandom())
        val encapsulation = generator.generateEncapsulated(keys.public)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encapsulation.secret, "AES"))
        val cipherText = cipher.doFinal(newAesKey.encoded)

        val ivStr = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val capsuleStr = Base64.encodeToString(encapsulation.encapsulation, Base64.NO_WRAP)
        val cipherStr = Base64.encodeToString(cipherText, Base64.NO_WRAP)

        prefs.edit()
            .putString("encrypted_master_aes", "$ivStr::$cipherStr")
            .putString("master_aes_capsule", capsuleStr)
            .apply()

        cachedMasterAesKey = newAesKey
        return cachedMasterAesKey!!
    }

    fun encryptLocal(plainText: String): String {
        if (plainText.isBlank()) return ""
        return try {
            val masterKey = getOrGenerateMasterAesKey()
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            "AES::${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}::${Base64.encodeToString(cipherText, Base64.NO_WRAP)}"
        } catch (e: Exception) {
            plainText
        }
    }

    fun decryptLocal(encryptedData: String): String {
        if (!encryptedData.startsWith("AES::")) return encryptedData
        val parts = encryptedData.split("::")
        if (parts.size != 3) return encryptedData

        return try {
            val masterKey = getOrGenerateMasterAesKey()
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(parts[1], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            encryptedData
        }
    }


    fun encryptAmount(amount: Long): String = encryptLocal(amount.toString())

    fun decryptAmount(encryptedAmount: String): Long {
        if (!encryptedAmount.startsWith("AES::")) return encryptedAmount.toLongOrNull() ?: 0L
        return decryptLocal(encryptedAmount).toLongOrNull() ?: 0L
    }

    fun encryptDecimal(value: Double): String = encryptLocal(value.toString())

    fun decryptDecimal(encryptedValue: String): Double {
        if (!encryptedValue.startsWith("AES::")) return encryptedValue.toDoubleOrNull() ?: 0.0
        return decryptLocal(encryptedValue).toDoubleOrNull() ?: 0.0
    }

    fun hashForSearch(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
