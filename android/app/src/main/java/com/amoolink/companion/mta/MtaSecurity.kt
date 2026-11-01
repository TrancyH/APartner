package com.amoolink.companion.mta

import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MTA BLE 协商加密(照搬 CatShare BleSecurity,互通关键):
 * ECDH P-256 → 共享密钥(TlsPremasterSecret)→ AES/CTR/NoPadding,
 * IV 固定 "0102030405060708"(协议既定)。
 */
object MtaSecurity {
    private val localPrivateKey: ECPrivateKey
    private val localPublicKey: ECPublicKey

    init {
        val kg = KeyPairGenerator.getInstance("EC")
        kg.initialize(256)
        val kp = kg.generateKeyPair()
        localPublicKey = kp.public as ECPublicKey
        localPrivateKey = kp.private as ECPrivateKey
    }

    fun getEncodedPublicKey(): String =
        Base64.getEncoder().encodeToString(localPublicKey.encoded)

    fun deriveSessionKey(peerPublicKeyB64: String): SessionCipher {
        val kf = KeyFactory.getInstance("EC")
        val peerKey = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peerPublicKeyB64)))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localPrivateKey)
        agreement.doPhase(peerKey, true)
        val secret = agreement.generateSecret("TlsPremasterSecret")
        return SessionCipher(SecretKeySpec(secret.encoded, "AES"))
    }

    class SessionCipher(private val key: SecretKeySpec) {
        private val iv = IvParameterSpec(MtaConst.CTR_IV.toByteArray())

        fun decrypt(encodedData: String): String {
            val data = Base64.getDecoder().decode(encodedData)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return cipher.doFinal(data).decodeToString()
        }

        fun encrypt(data: String): String {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            return Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
        }
    }
}
