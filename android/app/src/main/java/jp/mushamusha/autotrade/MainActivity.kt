package jp.mushamusha.autotrade

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.mushamusha.autotrade.crypto.SecureVault
import jp.mushamusha.autotrade.data.AppPreferences
import jp.mushamusha.autotrade.net.WindowsApiClient
import jp.mushamusha.autotrade.ui.AutoTradeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AiUsage(
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val requests: Int? = null,
    val estimatedMaxUsd: Double = 0.0,
    val pricingBasis: String = "",
)

data class BaselineAnalysis(
    val stance: String = "中立",
    val score: Int = 50,
    val summary: String = "データ待ち",
    val reasons: List<String> = emptyList(),
)

data class SystemHealth(
    val bridge: String = "未接続",
    val excelSync: String = "未接続",
    val marketData: String = "未接続",
    val deepSeek: String = "未設定",
    val calendar: String = "未取得",
    val marketFileAgeSeconds: Double? = null,
)

data class AppUiState(
    val loading: Boolean = true,
    val onboardingDone: Boolean = false,
    val serverUrl: String = "",
    val paired: Boolean = false,
    val deepSeekConfigured: Boolean = false,
    val bridgeOnline: Boolean = false,
    val freshQuoteCount: Int = 0,
    val staleQuoteCount: Int = 0,
    val currentSnapshotId: String = "",
    val baseline: BaselineAnalysis = BaselineAnalysis(),
    val aiStance: String = "未比較",
    val aiComment: String = "AIなしの数値判定は常時0トークンで表示します",
    val aiReasons: List<String> = emptyList(),
    val aiRisks: List<String> = emptyList(),
    val aiSnapshotId: String = "",
    val comparisonLabel: String = "未比較",
    val aiCacheReused: Boolean = false,
    val savedTokensEstimate: Int = 0,
    val lastAiUsage: AiUsage? = null,
    val todayAiUsage: AiUsage? = null,
    val aiDailyTokenLimit: Int = 20_000,
    val aiRunning: Boolean = false,
    val refreshing: Boolean = false,
    val movers: List<Mover> = emptyList(),
    val importantEvents: List<ImportantEvent> = emptyList(),
    val calendarSource: String = "未接続",
    val health: SystemHealth = SystemHealth(),
    val error: String? = null,
)

data class ImportantEvent(val time: String, val country: String, val name: String, val importance: String)

data class Mover(
    val code: String,
    val name: String,
    val price: Double?,
    val changePct: Double?,
    val volumeRatio: Double?,
    val suitabilityScore: Int,
    val suitabilityNotes: List<String>,
    val categories: List<String>,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPreferences(app)
    private val vault = SecureVault(app)
    private val api = WindowsApiClient()
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var aiJob: Job? = null
    private var autoRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            val url = prefs.serverUrl.first()
            val onboarded = prefs.onboardingDone.first()
            val secret = vault.loadPairingSecret()
            _state.value = _state.value.copy(
                loading = false,
                onboardingDone = onboarded,
                serverUrl = url,
                paired = secret != null,
            )
            if (secret != null && url.isNotBlank()) refresh()
        }
    }

    fun completeOnboarding() = viewModelScope.launch {
        prefs.setOnboardingDone(true)
        _state.value = _state.value.copy(onboardingDone = true)
    }

    fun startForegroundRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (_state.value.paired) refresh()
            }
        }
    }

    fun stopForegroundRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun saveConnection(url: String, pairingSecret: String) = viewModelScope.launch {
        val cleanUrl = url.trim().removeSuffix("/")
        val cleanSecret = pairingSecret.trim()
        _state.value = _state.value.copy(error = null)
        val test = withContext(Dispatchers.IO) { api.testPair(cleanUrl, cleanSecret) }
        if (test.isFailure) {
            _state.value = _state.value.copy(bridgeOnline = false, error = test.exceptionOrNull()?.message ?: "接続に失敗しました")
            return@launch
        }
        val saved = runCatching {
            prefs.setServerUrl(cleanUrl)
            vault.savePairingSecret(cleanSecret)
        }
        if (saved.isFailure) {
            _state.value = _state.value.copy(error = "接続情報を安全に保存できませんでした")
            return@launch
        }
        _state.value = _state.value.copy(serverUrl = cleanUrl, paired = true, bridgeOnline = true, error = null)
        refresh()
    }

    fun saveDeepSeekKey(chars: CharArray) = viewModelScope.launch {
        val secret = vault.loadPairingSecret()
        if (secret == null) {
            chars.fill('\u0000')
            _state.value = _state.value.copy(error = "先にWindowsとペアリングしてください")
            return@launch
        }
        val result = withContext(Dispatchers.IO) { api.uploadDeepSeekKey(_state.value.serverUrl, secret, chars) }
        result.onSuccess {
            _state.value = _state.value.copy(deepSeekConfigured = true, error = null)
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "APIキーを保存できません")
        }
    }

    fun saveAiTokenLimit(limit: Int) = viewModelScope.launch {
        if (limit != 0 && limit !in 1_000..2_000_000) {
            _state.value = _state.value.copy(error = "AI上限は0（無制限）または1,000〜2,000,000 tokensで設定してください")
            return@launch
        }
        val secret = vault.loadPairingSecret() ?: return@launch
        val result = withContext(Dispatchers.IO) { api.saveAiSettings(_state.value.serverUrl, secret, limit) }
        result.onSuccess {
            _state.value = _state.value.copy(aiDailyTokenLimit = limit, error = null)
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "AI上限を保存できません")
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val secret = vault.loadPairingSecret() ?: return@launch
            val url = _state.value.serverUrl
            if (url.isBlank()) return@launch
            _state.value = _state.value.copy(refreshing = true)
            val result = withContext(Dispatchers.IO) { api.getDashboard(url, secret) }
            if (result.isFailure) {
                _state.value = _state.value.copy(bridgeOnline = false, refreshing = false, error = result.exceptionOrNull()?.message ?: "Windowsに接続できません")
                return@launch
            }
            runCatching { parseDashboard(JSONObject(result.getOrThrow())) }
                .onSuccess { parsed -> _state.value = parsed.copy(bridgeOnline = true, refreshing = false, error = null) }
                .onFailure { _state.value = _state.value.copy(bridgeOnline = true, refreshing = false, error = "受信データを読み取れません") }
        }
    }

    private fun parseDashboard(root: JSONObject): AppUiState {
        val market = root.optJSONObject("market") ?: JSONObject()
        val calendar = root.optJSONObject("calendar") ?: JSONObject()
        val snapshotId = root.optString("snapshot_id", "")
        val moversArray = market.optJSONArray("movers")
        val movers = if (moversArray == null) emptyList() else (0 until moversArray.length()).map { index ->
            val item = moversArray.getJSONObject(index)
            Mover(
                code = item.optString("code"),
                name = item.optString("name"),
                price = item.optDouble("price").takeIf { !it.isNaN() },
                changePct = item.optDouble("change_pct").takeIf { !it.isNaN() },
                volumeRatio = item.optDouble("volume_ratio").takeIf { !it.isNaN() },
                suitabilityScore = item.optInt("suitability_score", 0),
                suitabilityNotes = jsonStrings(item, "suitability_notes"),
                categories = jsonStrings(item, "categories"),
            )
        }
        val eventArray = calendar.optJSONArray("events")
        val events = if (eventArray == null) emptyList() else (0 until eventArray.length()).map { index ->
            val item = eventArray.getJSONObject(index)
            ImportantEvent(item.optString("time", "—"), item.optString("country", ""), item.optString("name", ""), item.optString("importance", ""))
        }
        val healthObj = root.optJSONObject("health")
        val settingsObj = root.optJSONObject("ai_settings")
        return _state.value.copy(
            deepSeekConfigured = root.optBoolean("deepseek_configured", false),
            freshQuoteCount = market.optInt("fresh_quote_count", 0),
            staleQuoteCount = market.optInt("stale_quote_count", 0),
            currentSnapshotId = snapshotId,
            baseline = parseBaseline(root.optJSONObject("baseline")),
            comparisonLabel = if (_state.value.aiSnapshotId.isNotBlank() && _state.value.aiSnapshotId == snapshotId) _state.value.comparisonLabel else "未比較",
            movers = movers,
            importantEvents = events,
            calendarSource = calendar.optString("source", "未接続"),
            todayAiUsage = parseUsage(root.optJSONObject("ai_usage_today")),
            aiDailyTokenLimit = settingsObj?.optInt("ai_daily_token_limit", 20_000) ?: 20_000,
            health = parseHealth(healthObj),
        )
    }

    fun runAiComparison() {
        if (aiJob?.isActive == true) return
        aiJob = viewModelScope.launch {
            val secret = vault.loadPairingSecret() ?: return@launch
            _state.value = _state.value.copy(aiRunning = true, error = null)
            val result = withContext(Dispatchers.IO) { api.compareAnalysis(_state.value.serverUrl, secret) }
            result.onSuccess { raw ->
                runCatching {
                    val obj = JSONObject(raw)
                    val ai = obj.optJSONObject("ai")
                    val snapshotId = obj.optString("snapshot_id", "")
                    if (ai == null) {
                        _state.value = _state.value.copy(
                            baseline = parseBaseline(obj.optJSONObject("baseline")),
                            currentSnapshotId = snapshotId,
                            aiRunning = false,
                            error = obj.optString("message", "AIを実行できませんでした"),
                        )
                    } else {
                        val comparison = obj.optJSONObject("comparison")
                        _state.value = _state.value.copy(
                            baseline = parseBaseline(obj.optJSONObject("baseline")),
                            currentSnapshotId = snapshotId,
                            aiSnapshotId = snapshotId,
                            aiStance = ai.optString("stance", "中立"),
                            aiComment = ai.optString("summary", "分析結果なし"),
                            aiReasons = jsonStrings(ai, "reasons"),
                            aiRisks = jsonStrings(ai, "risks"),
                            comparisonLabel = comparison?.optString("label", "未比較") ?: "未比較",
                            aiCacheReused = ai.optBoolean("cache_reused", false),
                            savedTokensEstimate = ai.optInt("saved_tokens_estimate", 0),
                            lastAiUsage = parseUsage(ai.optJSONObject("usage")),
                            todayAiUsage = parseUsage(ai.optJSONObject("today_usage")) ?: _state.value.todayAiUsage,
                            aiRunning = false,
                            error = null,
                        )
                    }
                }.onFailure { _state.value = _state.value.copy(aiRunning = false, error = "AI比較結果を読み取れません") }
            }.onFailure {
                val rawMessage = it.message ?: "AI比較に失敗しました"
                val message = if ("(429)" in rawMessage) "本日のAIトークン上限に近いため、新しいAI分析を停止しました" else rawMessage
                _state.value = _state.value.copy(aiRunning = false, error = message)
            }
        }
    }

    private fun parseBaseline(obj: JSONObject?): BaselineAnalysis {
        if (obj == null) return BaselineAnalysis()
        return BaselineAnalysis(
            stance = obj.optString("stance", "中立"),
            score = obj.optInt("score", 50),
            summary = obj.optString("summary", ""),
            reasons = jsonStrings(obj, "reasons"),
        )
    }

    private fun parseHealth(obj: JSONObject?): SystemHealth {
        if (obj == null) return SystemHealth()
        return SystemHealth(
            bridge = obj.optString("bridge", "未接続"),
            excelSync = obj.optString("excel_sync", "未接続"),
            marketData = obj.optString("market_data", "未接続"),
            deepSeek = obj.optString("deepseek", "未設定"),
            calendar = obj.optString("calendar", "未取得"),
            marketFileAgeSeconds = obj.optDouble("market_file_age_seconds").takeIf { !it.isNaN() },
        )
    }

    private fun jsonStrings(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    private fun parseUsage(obj: JSONObject?): AiUsage? {
        if (obj == null) return null
        return AiUsage(
            model = obj.optString("model", ""),
            promptTokens = obj.optInt("prompt_tokens", 0),
            completionTokens = obj.optInt("completion_tokens", 0),
            totalTokens = obj.optInt("total_tokens", 0),
            cacheHitTokens = obj.optInt("cache_hit_tokens", 0),
            cacheMissTokens = obj.optInt("cache_miss_tokens", 0),
            reasoningTokens = obj.optInt("reasoning_tokens", 0),
            requests = if (obj.has("requests")) obj.optInt("requests", 0) else null,
            estimatedMaxUsd = obj.optDouble("estimated_max_usd", 0.0),
            pricingBasis = obj.optString("pricing_basis", ""),
        )
    }

    fun disconnect() {
        stopForegroundRefresh()
        refreshJob?.cancel()
        aiJob?.cancel()
        viewModelScope.launch {
            vault.clear()
            prefs.setServerUrl("")
            _state.value = AppUiState(loading = false, onboardingDone = true)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoTradeApp() }
    }
}
