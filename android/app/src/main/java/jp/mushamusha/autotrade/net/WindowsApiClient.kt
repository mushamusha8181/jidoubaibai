package jp.mushamusha.autotrade.net

import jp.mushamusha.autotrade.crypto.CryptoEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

class WindowsApiClient {
    private data class SignedCall(val request: Request, val nonce: String, val path: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun validateBaseUrl(baseUrl: String) {
        val uri = URI(baseUrl)
        require(uri.scheme == "http" || uri.scheme == "https") { "http/https のURLを入力してください" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "URLにクエリや#は付けないでください" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "URLはIPアドレスとポートまで入力してください" }
        val host = uri.host ?: error("WindowsのIPアドレスを入力してください")
        if (uri.scheme == "http") {
            val addr = InetAddress.getByName(host)
            require(addr.isSiteLocalAddress || addr.isLoopbackAddress) {
                "HTTP接続は同一LAN内のプライベートIPだけ利用できます"
            }
        }
    }

    private fun signedRequest(
        baseUrl: String,
        path: String,
        body: ByteArray,
        secret: String,
        method: String,
    ): SignedCall {
        validateBaseUrl(baseUrl)
        val normalizedMethod = method.uppercase()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = CryptoEnvelope.newRequestNonce()
        val signature = CryptoEnvelope.sign(secret, normalizedMethod, path, timestamp, nonce, body)
        val builder = Request.Builder()
            .url(baseUrl.removeSuffix("/") + path)
            .header("X-AT-Timestamp", timestamp)
            .header("X-AT-Nonce", nonce)
            .header("X-AT-Signature", signature)
            .header("Cache-Control", "no-store")
        val request = when (normalizedMethod) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(body.toRequestBody(json)).build()
            else -> error("unsupported method")
        }
        return SignedCall(request, nonce, path)
    }

    private fun executeText(
        baseUrl: String,
        path: String,
        secret: String,
        method: String = "GET",
        body: ByteArray = ByteArray(0),
        errorLabel: String,
    ): String {
        val call = signedRequest(baseUrl, path, body, secret, method)
        return client.newCall(call.request).execute().use { response ->
            if (!response.isSuccessful) error("$errorLabel (${response.code})")
            val responseBytes = response.body?.bytes() ?: "{}".toByteArray(Charsets.UTF_8)
            val signature = response.header("X-AT-Response-Signature") ?: error("Windows応答の署名がありません")
            val valid = CryptoEnvelope.verifyResponseSignature(
                pairingSecretHex = secret,
                path = call.path,
                requestNonce = call.nonce,
                body = responseBytes,
                suppliedSignature = signature,
            )
            if (!valid) error("Windows応答の改ざんを検知しました")
            responseBytes.toString(Charsets.UTF_8)
        }
    }

    fun testPair(baseUrl: String, secret: String): Result<Unit> = runCatching {
        executeText(baseUrl, "/v1/pair/test", secret, errorLabel = "接続認証に失敗しました")
    }.map { Unit }

    fun uploadDeepSeekKey(baseUrl: String, secret: String, apiKey: CharArray): Result<Unit> = runCatching {
        val sealed = CryptoEnvelope.sealDeepSeekKey(secret, apiKey)
        val body = JSONObject()
            .put("nonce", sealed.nonce)
            .put("ciphertext", sealed.ciphertext)
            .toString()
            .toByteArray(Charsets.UTF_8)
        executeText(
            baseUrl = baseUrl,
            path = "/v1/secrets/deepseek",
            secret = secret,
            method = "POST",
            body = body,
            errorLabel = "APIキー保存に失敗しました",
        )
    }.map { Unit }

    fun getDashboard(baseUrl: String, secret: String): Result<String> = runCatching {
        executeText(baseUrl, "/v1/dashboard", secret, errorLabel = "ダッシュボードを取得できません")
    }

    fun compareAnalysis(baseUrl: String, secret: String): Result<String> = runCatching {
        executeText(
            baseUrl = baseUrl,
            path = "/v1/analysis/compare",
            secret = secret,
            method = "POST",
            body = "{}".toByteArray(Charsets.UTF_8),
            errorLabel = "AI比較に失敗しました",
        )
    }

    fun saveAiSettings(baseUrl: String, secret: String, dailyTokenLimit: Int): Result<String> = runCatching {
        val body = JSONObject()
            .put("ai_daily_token_limit", dailyTokenLimit)
            .toString()
            .toByteArray(Charsets.UTF_8)
        executeText(
            baseUrl = baseUrl,
            path = "/v1/settings/ai",
            secret = secret,
            method = "POST",
            body = body,
            errorLabel = "AI上限設定を保存できません",
        )
    }
}
