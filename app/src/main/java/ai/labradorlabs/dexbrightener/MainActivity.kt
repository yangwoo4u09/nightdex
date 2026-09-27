package ai.labradorlabs.dexbrightener

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DimEngine.init(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                DimScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore the saved brightness as soon as the app is opened, which is the whole
        // point of the app: plug the monitor in, launch, and the monitor is already dimmed.
        if (DimEngine.prefs.current.enabled && DimEngine.canDrawOverlays()) {
            DimService.start(this)
        }
        DimEngine.apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DimScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by DimEngine.prefs.state.collectAsStateWithLifecycle()
    val displays by DimEngine.displays.collectAsStateWithLifecycle()
    val problem by DimEngine.problem.collectAsStateWithLifecycle()
    val lux by DimEngine.lux.collectAsStateWithLifecycle()
    val rawLux by DimEngine.rawLux.collectAsStateWithLifecycle()
    val autoLevel by DimEngine.autoLevel.collectAsStateWithLifecycle()
    val hostLabel by DimEngine.activeHostLabel.collectAsStateWithLifecycle()

    var canOverlay by remember { mutableStateOf(DimEngine.canDrawOverlays()) }
    var a11yOn by remember { mutableStateOf(DimAccessibilityService.connected) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = DimEngine.canDrawOverlays()
                a11yOn = DimAccessibilityService.connected
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val targets = displays.filterNot { it.isDefault }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NightDeX") }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!canOverlay) {
                SectionCard(tone = Tone.WARNING) {
                    Text("권한 필요", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "외부 디스플레이를 어둡게 하려면 '다른 앱 위에 표시' 권한이 필요합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }) { Text("권한 설정 열기") }
                }
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("밝기 조절 사용", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !settings.enabled -> "꺼짐"
                                problem != null -> problem!!
                                else -> "동작 중" + (hostLabel?.let { " · $it" } ?: "")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        enabled = canOverlay,
                        onCheckedChange = { on ->
                            DimEngine.setEnabled(on)
                            if (on) DimService.start(context) else DimService.stop(context)
                        },
                    )
                }
            }

            SectionCard {
                Text("모드", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == DimMode.FIXED,
                        onClick = { DimEngine.setMode(DimMode.FIXED) },
                        label = { Text("고정") },
                    )
                    FilterChip(
                        selected = settings.mode == DimMode.AUTO,
                        onClick = { DimEngine.setMode(DimMode.AUTO) },
                        label = { Text("자동 (조도 센서)") },
                        enabled = DimEngine.hasLightSensor,
                    )
                }
            }

            if (settings.mode == DimMode.FIXED) {
                SectionCard {
                    Text("밝기", style = MaterialTheme.typography.titleMedium)
                    if (targets.isEmpty()) {
                        Text(
                            "외부 디스플레이가 연결되면 아래 값이 바로 적용됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LevelSlider(
                            label = "기본값",
                            level = settings.defaultLevel,
                            onPreview = { },
                            onCommit = { DimEngine.setLevel("__default__", it) },
                        )
                    } else {
                        targets.forEach { display ->
                            LevelSlider(
                                label = "${display.name}  (${display.widthPx}×${display.heightPx})",
                                level = settings.levelFor(display.key),
                                onPreview = { DimEngine.preview(display.key, it) },
                                onCommit = { DimEngine.commitPreview(display.key, it) },
                            )
                        }
                    }
                }
            } else {
                SectionCard {
                    Text("자동 모드", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (lux.isNaN()) "조도 측정 대기 중…"
                        else "조도 ${lux.roundToInt()} lx → 밝기 $autoLevel%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!rawLux.isNaN()) {
                        Text(
                            "센서 원값 ${rawLux.roundToInt()} lx",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "어두운 곳일수록 모니터를 어둡게, 밝은 곳일수록 원래 밝기에 가깝게 맞춥니다. " +
                            "주행 중 마주 오는 차의 헤드라이트나 가로등에 반응하지 않도록 " +
                            "밝아지는 방향은 아주 천천히(약 25초), 어두워지는 방향은 " +
                            "조금 빠르게(약 5초) 따라갑니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LevelSlider(
                        label = "가장 어두울 때 (최소 밝기)",
                        level = settings.autoMinLevel,
                        onPreview = { },
                        onCommit = { DimEngine.setAutoRange(it, settings.autoMaxLevel) },
                    )
                    LevelSlider(
                        label = "가장 밝을 때 (최대 밝기)",
                        level = settings.autoMaxLevel,
                        onPreview = { },
                        onCommit = { DimEngine.setAutoRange(settings.autoMinLevel, it) },
                    )
                }
            }

            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("더 어둡게 (접근성)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android 12부터 일반 오버레이는 불투명도 0.8을 넘으면 터치가 차단됩니다. " +
                                "접근성 오버레이를 쓰면 그 제한 없이 더 어둡게 할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (settings.deepDim && !a11yOn) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "설정 > 접근성 > 설치된 앱에서 'NightDeX 딥 디밍'을 켜주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = settings.deepDim,
                        onCheckedChange = { on ->
                            DimEngine.setDeepDim(on)
                            if (on && !DimAccessibilityService.connected) {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                    )
                }
            }

            SectionCard {
                Text("감지된 디스플레이", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (displays.isEmpty()) {
                    Text("없음", style = MaterialTheme.typography.bodySmall)
                } else {
                    displays.forEach { d ->
                        Text(
                            "#${d.id}  ${d.name}  ${d.widthPx}×${d.heightPx}" +
                                if (d.isDefault) "  (폰 화면 — 조절 안 함)" else "  (대상)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Text(
                "참고: 이 앱은 모니터의 실제 백라이트를 바꾸는 것이 아니라 반투명 검정 " +
                    "오버레이로 어둡게 만듭니다. 터치·마우스 입력은 그대로 통과하지만, " +
                    "보안이 민감한 화면(권한 대화상자 등)은 오버레이가 떠 있으면 입력을 " +
                    "무시할 수 있습니다. 그럴 때는 빠른 설정 타일로 잠시 꺼주세요.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionCard {
                Text("앱 종료", style = MaterialTheme.typography.titleMedium)
                Text(
                    "디밍을 끄고 오버레이를 모두 내린 뒤 앱을 닫습니다. " +
                        "창만 닫으면 포그라운드 서비스가 남아 디밍이 계속됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    // Clear `enabled` before stopping the service: onResume restarts the
                    // service whenever the flag is still set, so the order decides whether
                    // reopening the app brings the overlay straight back.
                    DimEngine.setEnabled(false)
                    DimService.stop(context)
                    activity?.finishAndRemoveTask()
                }) { Text("종료") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    LaunchedEffect(settings.enabled, settings.mode, targets.size) {
        DimEngine.apply()
    }
}

private enum class Tone { NORMAL, WARNING }

@Composable
private fun SectionCard(
    tone: Tone = Tone.NORMAL,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (tone == Tone.WARNING) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun LevelSlider(
    label: String,
    level: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    // Keyed on `level` only: during a drag the stored level does not change, so the
    // slider keeps the finger position; once committed it snaps to the stored value.
    var local by remember(level) { mutableStateOf(level.toFloat()) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${local.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = local,
            onValueChange = {
                local = it
                onPreview(it.roundToInt())
            },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}
