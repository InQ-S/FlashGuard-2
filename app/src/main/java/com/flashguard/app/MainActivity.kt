package com.flashguard.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flashguard.app.data.ConfigStore
import com.flashguard.app.model.FilterConfig
import com.flashguard.app.model.FilterPresetId
import com.flashguard.app.model.FilterPresets
import com.flashguard.app.service.FlashGuardService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.init(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF131924),
                    primary = Color(0xFFF59E0B),
                    onPrimary = Color(0xFF0B0F17),
                    onBackground = Color(0xFFF1F5F9),
                    onSurface = Color(0xFFF1F5F9)
                )
            ) {
                FlashGuardScreen()
            }
        }
    }
}

@Composable
fun FlashGuardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val config by ConfigStore.config.collectAsState()
    val serviceStatus by FlashGuardService.status.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var showFlashTest by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(showFlashTest) {
        if (showFlashTest) {
            delay(1600L)
            showFlashTest = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, FlashGuardService::class.java).apply {
                action = FlashGuardService.ACTION_START
                putExtra(FlashGuardService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FlashGuardService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    if (showFlashTest) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable { showFlashTest = false },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "테스트 섬광 화면 (자동 종료됨)",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
        return
    }

    Scaffold(
        containerColor = Color(0xFF0B0F17)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "FlashGuard",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF1F5F9)
                )
                Text(
                    text = "야간 모바일 게임 보구·화면전환 눈부심 방지 필터",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // Permissions & Start Control Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF131924))
                    .border(1.dp, Color(0xFF232D3F), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PermissionRow(
                    title = "다른 앱 위에 표시 (오버레이) 권한",
                    granted = hasOverlayPermission,
                    actionLabel = "권한 설정",
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        title = "상주 알림 표시 권한",
                        granted = hasNotificationPermission,
                        actionLabel = "허용하기",
                        onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }

                HorizontalDivider(color = Color(0xFF232D3F))

                Text(
                    text = "※ 시작 시 화면 공유 팝업에서는 반드시 '전체 화면'을 선택해 주세요. 세로에서 시작해 가로 게임으로 넘어가도 자동으로 유지됩니다.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFFFBBF24)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (serviceStatus.isRunning) {
                                val stopIntent = Intent(context, FlashGuardService::class.java).apply {
                                    action = FlashGuardService.ACTION_STOP
                                }
                                context.startService(stopIntent)
                            } else {
                                val projectionManager = context.getSystemService(
                                    Context.MEDIA_PROJECTION_SERVICE
                                ) as MediaProjectionManager
                                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        },
                        enabled = hasOverlayPermission,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serviceStatus.isRunning) Color(0xFFEF4444) else Color(0xFFF59E0B),
                            contentColor = if (serviceStatus.isRunning) Color.White else Color(0xFF0B0F17)
                        )
                    ) {
                        Icon(
                            imageVector = if (serviceStatus.isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (serviceStatus.isRunning) "필터 중지" else "감지 시작",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = { showFlashTest = true },
                        enabled = serviceStatus.isRunning
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("섬광 테스트")
                    }
                }

                if (serviceStatus.isRunning) {
                    Text(
                        text = "상태: ${if (serviceStatus.isPaused) "일시정지" else "감지 중"} · 현재 하얀 비율 ${serviceStatus.lastWhiteRatioPct.roundToInt()}% · 차단 횟수 ${serviceStatus.triggerCount}회",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Presets (2x2 Matrix: Pure White vs Bright × Medium vs Dark)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "추천 프리셋 (원하는 방식을 고르세요)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF1F5F9)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        FilterPresetId.PURE_WHITE_MEDIUM,
                        FilterPresetId.PURE_WHITE_DARK,
                        FilterPresetId.BRIGHT_MEDIUM,
                        FilterPresetId.BRIGHT_DARK
                    ).forEach { presetId ->
                        val selected = config.presetId == presetId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Color(0x26F59E0B) else Color(0xFF131924))
                                .border(
                                    1.dp,
                                    if (selected) Color(0xFFF59E0B) else Color(0xFF232D3F),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    ConfigStore.update(
                                        FilterPresets.getPreset(presetId, config.showFloatingControl)
                                    )
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = presetId.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) Color(0xFFFBBF24) else Color(0xFFF1F5F9)
                            )
                            Text(
                                text = presetId.shortDesc,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // ROI Crop Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF131924))
                    .border(1.dp, Color(0xFF232D3F), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "감지 영역 미리보기",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "노란 박스 안쪽만 검사합니다. 양옆의 검은 여백이나 위아래 버튼 때문에 감지가 빗나가는 것을 막아줍니다.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFF94A3B8)
                )
                RoiPreviewBox(config = config)
            }

            // Detailed Sliders with Clear Korean Explanations
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF131924))
                    .border(1.dp, Color(0xFF232D3F), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "세부 설정 (직접 미세 조정)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                SettingSlider(
                    label = "1. 검정 필터 진하기 (필터 농도)",
                    description = "눈부실 때 화면에 덮는 검은 막의 진하기입니다. 숫자가 높을수록 더 캄캄하게 가려줍니다.",
                    valueText = "${config.overlayOpacityPct}%",
                    value = config.overlayOpacityPct.toFloat(),
                    range = 20f..92f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, overlayOpacityPct = it.roundToInt()))
                    }
                )

                SettingSlider(
                    label = "2. 얼마나 밝아야 하얗다고 볼지 (최소 RGB)",
                    description = "화면 빛(빨강·초록·파랑, 0~255) 모두가 이 수치 이상이어야 작동합니다. 238 이상이면 진짜 하얀 섬광에만, 210 정도면 일반 밝은 연출에도 반응합니다.",
                    valueText = "${config.minRgbThreshold} / 255",
                    value = config.minRgbThreshold.toFloat(),
                    range = 150f..250f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, minRgbThreshold = it.roundToInt()))
                    }
                )

                SettingSlider(
                    label = "3. 색깔이 섞여도 반응할지 (채도 허용폭)",
                    description = "숫자가 작을수록(±22) 색감 없는 순수 흰색만 잡습니다. 숫자가 크면(±50) 연한 파랑·노랑 같은 밝은 이펙트도 함께 잡습니다.",
                    valueText = "±${config.maxChannelSpread}",
                    value = config.maxChannelSpread.toFloat(),
                    range = 10f..120f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, maxChannelSpread = it.roundToInt()))
                    }
                )

                SettingSlider(
                    label = "4. 화면의 몇 %가 하얘야 켤지 (감지 면적 비율)",
                    description = "노란 박스 영역 전체 중 이 비율 이상이 하얗게 변할 때 필터가 작동합니다. 80% 이상이면 화면 거의 전체가 하얘질 때만 켜집니다.",
                    valueText = "${config.triggerRatioPct}% 이상",
                    value = config.triggerRatioPct.toFloat(),
                    range = 25f..98f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, triggerRatioPct = it.roundToInt()))
                    }
                )

                SettingSlider(
                    label = "5. 좌우 가장자리 빼고 검사 (레터박스 제외)",
                    description = "화면 양옆의 검은 여백이나 사이드 메뉴를 검사에서 빼서 더 정확하게 섬광을 감지합니다.",
                    valueText = "좌 ${config.cropLeftPct}% · 우 ${config.cropRightPct}%",
                    value = config.cropLeftPct.toFloat(),
                    range = 0f..35f,
                    onChange = {
                        val v = it.roundToInt()
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, cropLeftPct = v, cropRightPct = v))
                    }
                )

                SettingSlider(
                    label = "6. 위아래 가장자리 빼고 검사 (UI 제외)",
                    description = "상단 상태바나 하단 체력바·스킬 버튼 부분을 제외하고 가운데 연출 화면만 봅니다.",
                    valueText = "상 ${config.cropTopPct}% · 하 ${config.cropBottomPct}%",
                    value = config.cropTopPct.toFloat(),
                    range = 0f..35f,
                    onChange = {
                        val v = it.roundToInt()
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, cropTopPct = v, cropBottomPct = v))
                    }
                )

                SettingSlider(
                    label = "7. 최소 필터 유지 시간",
                    description = "한 번 검은 필터가 켜진 뒤, 깜빡임을 막기 위해 최소한 이 시간 동안은 어둡게 유지합니다.",
                    valueText = "${config.minHoldMs} ms",
                    value = config.minHoldMs.toFloat(),
                    range = 100f..2000f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, minHoldMs = (it / 50f).roundToInt() * 50))
                    }
                )

                SettingSlider(
                    label = "8. 서서히 밝아지는 시간 (해제 속도)",
                    description = "하얀 섬광이 끝난 뒤 필터가 뚝 끊기지 않고 이 시간에 걸쳐 부드럽게 투명해집니다.",
                    valueText = "${config.fadeOutMs} ms",
                    value = config.fadeOutMs.toFloat(),
                    range = 100f..2000f,
                    onChange = {
                        ConfigStore.update(config.copy(presetId = FilterPresetId.CUSTOM, fadeOutMs = (it / 50f).roundToInt() * 50))
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("플로팅 일시정지 버튼 표시", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("게임 중 작은 버튼을 눌러 1초 만에 켜고 끕니다", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                    Switch(
                        checked = config.showFloatingControl,
                        onCheckedChange = {
                            ConfigStore.update(config.copy(showFloatingControl = it))
                        }
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF22C55E) else Color(0xFFF59E0B),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                fontSize = 13.sp,
                color = Color(0xFFE2E8F0)
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onClick) {
                Text(actionLabel, fontSize = 12.sp)
            }
        } else {
            Text("허용됨", fontSize = 12.sp, color = Color(0xFF22C55E), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RoiPreviewBox(config: FilterConfig) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF090C12))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftPx = size.width * (config.cropLeftPct / 100f)
            val rightPx = size.width * (1f - config.cropRightPct / 100f)
            val topPx = size.height * (config.cropTopPct / 100f)
            val bottomPx = size.height * (1f - config.cropBottomPct / 100f)

            val roiWidth = (rightPx - leftPx).coerceAtLeast(4f)
            val roiHeight = (bottomPx - topPx).coerceAtLeast(4f)

            drawRect(color = Color(0x99000000), size = size)

            drawRect(
                color = Color(0x33F59E0B),
                topLeft = Offset(leftPx, topPx),
                size = Size(roiWidth, roiHeight)
            )
            drawRect(
                color = Color(0xFFF59E0B),
                topLeft = Offset(leftPx, topPx),
                size = Size(roiWidth, roiHeight),
                style = Stroke(width = 3f)
            )
        }

        Text(
            text = "노란 박스 내부만 실시간 감지",
            fontSize = 11.sp,
            color = Color(0xFFFBBF24),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    description: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE2E8F0))
            Text(valueText, fontSize = 12.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = description,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = Color(0xFF94A3B8)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFF59E0B),
                activeTrackColor = Color(0xFFF59E0B),
                inactiveTrackColor = Color(0xFF232D3F)
            )
        )
    }
}
