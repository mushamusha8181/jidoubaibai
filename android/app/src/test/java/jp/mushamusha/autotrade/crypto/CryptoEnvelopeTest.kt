package jp.mushamusha.autotrade.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoEnvelopeTest {
    private val secret = (0 until 32).joinToString("") { "%02x".format(it) }

    @Test
    fun requestSignatureMatchesWindowsVector() {
        val signature = CryptoEnvelope.sign(
            pairingSecretHex = secret,
            method = "POST",
            path = "/v1/analysis/compare",
            timestamp = "1700000000",
            nonce = "nonce",
            body = "{}".toByteArray(),
        )
        assertEquals("ZjIbN4awF3swgkOLVD0etZO-MTguBLDOgYaK--YtaaE", signature)
    }

    @Test
    fun responseSignatureMatchesWindowsVector() {
        assertTrue(
            CryptoEnvelope.verifyResponseSignature(
                pairingSecretHex = secret,
                path = "/v1/analysis/compare",
                requestNonce = "nonce",
                body = "{}".toByteArray(),
                suppliedSignature = "fD6xhAI4q4Lizfy7hiuAhPBfFtXsWSu8bGuaXzKfptA",
            ),
        )
    }
}
