package com.notibridge.app

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Utilidades criptográficas: AES-256-GCM (el mismo formato que usa la web
 * con WebCrypto: texto cifrado || etiqueta de autenticación de 16 bytes).
 */
object Crypto {

    fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun b64urlDec(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Cifra con AES-256-GCM.
     * @return par (iv de 12 bytes, ciphertext||tag)
     */
    fun encryptAesGcm(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        require(key.size == 32) { "La clave AES debe ser de 32 bytes" }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv to cipher.doFinal(plain)
    }
}
