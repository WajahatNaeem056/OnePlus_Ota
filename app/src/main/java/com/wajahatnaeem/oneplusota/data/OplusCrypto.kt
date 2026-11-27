package com.wajahatnaeem.oneplusota.data

import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v2 request protocol:
 *  - body JSON encrypted with random AES-256-CTR key + 16 byte IV
 *  - Base64(key) (as UTF-8 text) encrypted with server RSA public key, RSA/ECB/OAEPWithSHA-1AndMGF1Padding
 *  - sent in the "protectedKey" header; response is {cipher, iv} decrypted with the same AES key
 */
object OplusCrypto {
    class Session(val aesKey: ByteArray, val iv: ByteArray) {
        val keyB64: String get() = Base64.getEncoder().encodeToString(aesKey)
        val ivB64: String get() = Base64.getEncoder().encodeToString(iv)
    }

    fun newSession(): Session {
        val rnd = SecureRandom()
        return Session(ByteArray(32).also(rnd::nextBytes), ByteArray(16).also(rnd::nextBytes))
    }

    /** Outer request body: {"params":"{\"cipher\":\"..\",\"iv\":\"..\"}"} */
    fun encryptBody(plainJson: String, s: Session): String {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(s.aesKey, "AES"), IvParameterSpec(s.iv))
        val cipher = Base64.getEncoder().encodeToString(c.doFinal(plainJson.toByteArray(Charsets.UTF_8)))
        val inner = JSONObject().put("cipher", cipher).put("iv", s.ivB64).toString()
        return JSONObject().put("params", inner).toString()
    }

    fun protectedKeyHeader(s: Session, server: OplusServers.Server, timeMs: Long): String {
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(server.pubKeyB64)))
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub)
        val wrapped = Base64.getEncoder().encodeToString(rsa.doFinal(s.keyB64.toByteArray(Charsets.UTF_8)))
        val scene = JSONObject()
            .put("protectedKey", wrapped)
            .put("version", (timeMs + 86_400_000L).toString())
            .put("negotiationVersion", server.negotiationVersion)
        return JSONObject().put("SCENE_1", scene).toString()
    }

    fun decrypt(cipherB64: String, ivB64: String, s: Session): String {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(s.aesKey, "AES"),
            IvParameterSpec(Base64.getDecoder().decode(ivB64)),
        )
        return String(c.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }
}
