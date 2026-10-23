package jp.mushamusha.autotrade.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.mushamusha.autotrade.AiUsage
import jp.mushamusha.autotrade.AppUiState
import jp.mushamusha.autotrade.AppViewModel
import jp.mushamusha.autotrade.Mover

@Composable
fun AutoTradeApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.onboardingDone -> OnboardingScreen(vm)
            else -> MainShell(state, vm)
        }
    }
}

@Composable
private fun OnboardingScreen(vm: AppViewModel) {
    var step by remember { mutableIntStateOf(0) }
    val pages = listOf(
        "このアプリについて" to "Androidは確認画面、Windowsは市場データと分析を担当します。AIなしの数値判定と、必要な時だけ使うAI判定を比較できます。現在版に実資金の注文機能はありません。",
        "権限について" to "現在版は実行時権限を要求しません。位置情報、写真、連絡先、マイク、ストレージ、カメラ、通知は使いません。",
        "APIキーの保護" to "DeepSeek APIキーはAndroidへ永続保存しません。暗号化してWindowsへ送り、WindowsのDPAPIで保護します。設定画面はスクリーンショットも抑止します。",
        "トークン節約" to "通常は0トークンの数値判定を使います。AI比較は手動実行、同一相場は再利用、1日上限も設定できます。",
    )
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("自動売買", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("初回セットアップ ${step + 1}/${pages.size}", style = MaterialTheme.typography.labelLarge)
                Text(pages[step].first, style = MaterialTheme.typography.titleLarge)
                Text(pages[step].second, style = MaterialTheme.typography.bodyLarge)
            }
            Button(
                onClick = { if (step < pages.lastIndex) step++ else vm.completeOnboarding() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (step < pages.lastIndex) "次へ" else "はじめる") }
        }
    }
}

private enum class Tab { HOME, MOVERS, GUIDE, SETTINGS }

@Composable
private fun MainShell(state: AppUiState, vm: AppViewModel) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> vm.startForegroundRefresh()
                    Lifecycle.Event.ON_STOP -> vm.stopForegroundRefresh()
                    else -> Unit
                }
            }
            activity.lifecycle.addObserver(observer)
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.startForegroundRefresh()
            onDispose {
                activity.lifecycle.removeObserver(observer)
                vm.stopForegroundRefresh()
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.HOME, { tab = Tab.HOME }, { Icon(Icons.Outlined.Home, null) }, label = { Text("ホーム") })
                NavigationBarItem(tab == Tab.MOVERS, { tab = Tab.MOVERS }, { Icon(Icons.Outlined.ShowChart, null) }, label = { Text("銘柄") })
                NavigationBarItem(tab == Tab.GUIDE, { tab = Tab.GUIDE }, { Icon(Icons.Outlined.HelpOutline, null) }, label = { Text("使い方") })
                NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("設定") })
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeScreen(state, vm)
                Tab.MOVERS -> MoversScreen(state)
                Tab.GUIDE -> GuideScreen()
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, vm: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("自動売買", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (state.bridgeOnline) "Windows 接続中・前面時5秒更新" else "Windows 未接続", style = MaterialTheme.typography.bodySmall)
                }
                AssistChip(
                    onClick = vm::refresh,
                    enabled = !state.refreshing,
                    label = { Text(if (state.refreshing) "更新中" else "更新") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                )
            }
        }
        state.error?.let { item { WarningCard(it) } }
        item { HealthCard(state) }
        item { ComparisonCard(state, vm) }
        item {
            CompactCard(
                "注目銘柄",
                if (state.freshQuoteCount == 0) "データ未接続" else "${state.movers.size}件",
                "急騰・急落・出来高急増を0トークンで選別し、適性スコア順に表示します。",
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("本日の重要指標", fontWeight = FontWeight.SemiBold)
                    if (state.importantEvents.isEmpty()) Text("データ未取得", style = MaterialTheme.typography.titleMedium)
                    else state.importantEvents.take(3).forEach { event ->
                        Text("${event.time}  ${event.country}  ${event.name}${if (event.importance.isNotBlank()) "  [${event.importance}]" else ""}")
                    }
                    Text("データ元: ${state.calendarSource}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HealthCard(state: AppUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("システム状態", fontWeight = FontWeight.SemiBold)
            Text("Windows ${state.health.bridge}　Excel ${state.health.excelSync}", style = MaterialTheme.typography.bodyMedium)
            Text("市場 ${state.health.marketData}　DeepSeek ${state.health.deepSeek}　指標 ${state.health.calendar}", style = MaterialTheme.typography.bodyMedium)
            val age = state.health.marketFileAgeSeconds
            Text(
                "新鮮 ${state.freshQuoteCount}件 / 古い ${state.staleQuoteCount}件${age?.let { "　最終データ 約${it.toInt()}秒前" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ComparisonCard(state: AppUiState, vm: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AIあり / AIなし比較", fontWeight = FontWeight.SemiBold)
            Text("AIなし（0 tokens）", style = MaterialTheme.typography.labelLarge)
            Text("${state.baseline.stance}　数値スコア ${state.baseline.score}/100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.baseline.summary, style = MaterialTheme.typography.bodySmall)
            state.baseline.reasons.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text("AIあり（DeepSeek）", style = MaterialTheme.typography.labelLarge)
            Text("${state.aiStance}　比較: ${state.comparisonLabel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.aiComment, style = MaterialTheme.typography.bodySmall)
            if (state.aiSnapshotId.isNotBlank() && state.aiSnapshotId != state.currentSnapshotId) {
                Text("現在の相場とはスナップショットが違います。再比較してください。", style = MaterialTheme.typography.bodySmall)
            }
            state.aiReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            state.aiRisks.forEach { Text("注意: $it", style = MaterialTheme.typography.bodySmall) }
            if (state.aiCacheReused) {
                Text("同一相場のAI結果を再利用。今回0 tokens（約${state.savedTokensEstimate} tokens節約）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            state.lastAiUsage?.let { UsageBlock("今回のAI使用量", it, showModel = true) }
            state.todayAiUsage?.let { usage ->
                val limit = state.aiDailyTokenLimit
                val limitText = if (limit == 0) "無制限" else "$limit tokens"
                Text("本日 ${usage.totalTokens} / $limitText　${usage.requests ?: 0}回", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = vm::runAiComparison,
                enabled = state.bridgeOnline && state.deepSeekConfigured && state.freshQuoteCount > 0 && !state.aiRunning,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (state.aiRunning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("比較中")
                } else Text("AIと比較")
            }
            Text("通常表示はAIなし。ボタンを押した時だけAIを使い、同一相場は短時間なら再利用します。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsageBlock(title: String, usage: AiUsage, showModel: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (showModel && usage.model.isNotBlank()) Text("モデル: ${usage.model}", style = MaterialTheme.typography.bodySmall)
        Text("入力 ${usage.promptTokens} / 出力 ${usage.completionTokens} / 合計 ${usage.totalTokens} tokens", style = MaterialTheme.typography.bodySmall)
        if (usage.cacheHitTokens > 0 || usage.cacheMissTokens > 0) Text("APIキャッシュ Hit ${usage.cacheHitTokens} / Miss ${usage.cacheMissTokens}", style = MaterialTheme.typography.bodySmall)
        if (usage.reasoningTokens > 0) Text("推論 ${usage.reasoningTokens} tokens", style = MaterialTheme.typography.bodySmall)
        Text("料金概算上限: \$${"%.6f".format(usage.estimatedMaxUsd)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompactCard(title: String, value: String, sub: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, null)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MoversScreen(state: AppUiState) {
    var filter by remember { mutableStateOf("すべて") }
    val filtered = when (filter) {
        "適性70+" -> state.movers.filter { it.suitabilityScore >= 70 }
        "すべて" -> state.movers
        else -> state.movers.filter { filter in it.categories }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("注目銘柄", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("AIを呼ばずに数値だけで抽出・採点", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("すべて", "急騰", "急落", "出来高", "適性70+")) { label ->
                FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.freshQuoteCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("新鮮な市場データがありません\nWindows側のExcel同期を確認してください") }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("この条件に該当する銘柄はありません") }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.code }) { MoverRow(it) }
            }
        }
    }
}

@Composable
private fun MoverRow(mover: Mover) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${mover.code}  ${mover.name}", fontWeight = FontWeight.SemiBold)
                Text(mover.categories.joinToString("・"), style = MaterialTheme.typography.labelSmall)
                Text("適性 ${mover.suitabilityScore}/100　${mover.suitabilityNotes.joinToString(" ")}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(mover.price?.let { "%,.1f円".format(it) } ?: "—")
                Text(mover.changePct?.let { "%+.2f%%".format(it) } ?: "—", fontWeight = FontWeight.Bold)
                mover.volumeRatio?.let { Text("出来高 ×%.1f".format(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun GuideScreen() {
    val steps = listOf(
        "1. Windowsで付属サーバーを起動し、設定からペアリングします。",
        "2. MarketSpeed II RSSを入れたExcelと読取専用同期を起動します。",
        "3. 通常は『AIなし』の数値判定と注目銘柄を見ます。ここはトークン0です。",
        "4. AIの見方も比べたい時だけ『AIと比較』を押します。同じ相場なら短時間はAIを再実行しません。",
        "5. 設定で1日のAIトークン上限を決めます。0は無制限ですが、通常は上限設定を推奨します。",
        "6. システム状態でExcel・市場データ・DeepSeek・重要指標を確認します。",
        "7. 適性スコアは利益確率ではありません。流動性・値動き・出来高などからの候補整理です。",
    )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("使い方", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(steps) { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(14.dp)) } }
        item { Text("現在版は分析専用です。楽天証券への実注文処理は含みません。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, vm: AppViewModel) {
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var pairing by remember { mutableStateOf("") }
    var deepSeek by remember { mutableStateOf("") }
    var tokenLimit by remember(state.aiDailyTokenLimit) { mutableStateOf(state.aiDailyTokenLimit.toString()) }
    SecureWindow()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Windows接続", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(url, { url = it }, label = { Text("サーバーURL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        pairing,
                        { pairing = it.filter { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }.take(64) },
                        label = { Text("64桁ペアリングコード") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.saveConnection(url, pairing); pairing = "" },
                        enabled = url.isNotBlank() && pairing.length == 64,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (state.paired) "再ペアリング" else "ペアリング") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DeepSeek", fontWeight = FontWeight.SemiBold)
                    Text(if (state.deepSeekConfigured) "APIキー: Windows側に保護して保存済み" else "APIキー: 未設定", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        deepSeek, { deepSeek = it }, label = { Text("DeepSeek APIキー") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { val chars = deepSeek.toCharArray(); deepSeek = ""; vm.saveDeepSeekKey(chars) },
                        enabled = state.paired && deepSeek.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("暗号化してWindowsへ保存") }
                    HorizontalDivider()
                    Text("1日のAIトークン上限", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        tokenLimit,
                        { tokenLimit = it.filter { c -> c.isDigit() }.take(7) },
                        label = { Text("tokens（0=無制限）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.saveAiTokenLimit(tokenLimit.toIntOrNull() ?: state.aiDailyTokenLimit) },
                        enabled = state.paired && tokenLimit.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("AI上限を保存") }
                    Text("通常はAIなし判定を使うため、この上限はAI比較を押した時だけ消費対象になります。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.paired) item {
            OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("ペアリング解除") }
        }
    }
}

@Composable
private fun SecureWindow() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
