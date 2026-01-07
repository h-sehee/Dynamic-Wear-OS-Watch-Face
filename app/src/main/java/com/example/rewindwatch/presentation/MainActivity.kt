package com.example.rewindwatch.presentation

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.*
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import com.example.rewindwatch.MyWatchFace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Main activity for the Watch Face customization UI.
 */
class MainActivity : ComponentActivity() {
    private val editorSessionState = mutableStateOf<EditorSession?>(null)
    private val isReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make window transparent to see the watch face behind
        window.setBackgroundDrawableResource(android.R.color.transparent)

        if (intent.action != "androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR") {
            setContent { MaterialTheme { Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Customize Mode", color = Color.White) } } }
            return
        }

        lifecycleScope.launch(Dispatchers.Main.immediate) {
            try {
                editorSessionState.value = EditorSession.createOnWatchEditorSession(this@MainActivity)
                isReady.value = true
            } catch (e: Exception) { finish() }
        }
        setContent { MaterialTheme { if (isReady.value && editorSessionState.value != null) EditWatchFaceScreen(editorSessionState.value!!) } }
    }
}

@Composable
fun EditWatchFaceScreen(session: EditorSession) {
    val userStyle by session.userStyle.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Retrieve settings
    val showTime = getBoolStyle(userStyle, MyWatchFace.SETTING_TIME_ID)
    val timeWeight = getListStyleId(userStyle, MyWatchFace.SETTING_TIME_WEIGHT_ID)

    val showSeconds = getBoolStyle(userStyle, MyWatchFace.SETTING_SECONDS_ID)
    val showDate = getBoolStyle(userStyle, MyWatchFace.SETTING_DATE_ID)
    val dateWeight = getListStyleId(userStyle, MyWatchFace.SETTING_DATE_WEIGHT_ID)
    val showBattery = getBoolStyle(userStyle, MyWatchFace.SETTING_BATTERY_ID)
    val batteryWeight = getListStyleId(userStyle, MyWatchFace.SETTING_BATTERY_WEIGHT_ID)
    val fontStyle = getListStyleId(userStyle, MyWatchFace.SETTING_FONT_STYLE_ID)
    val showWeather = getBoolStyle(userStyle, MyWatchFace.SETTING_WEATHER_ANIM_ID)

    val pagerState = rememberPagerState(pageCount = { 5 })

    // ★ Global background removed. Each page now handles its own background.
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (page) {
                    // Page 0: Time & Seconds
                    0 -> TimeAndSecondsPage(
                        timeChecked = showTime,
                        secondsChecked = showSeconds,
                        timeWeightIndex = timeWeight.toIntOrNull() ?: 2,
                        onTimeCheckChanged = { saveBoolStyle(context, session, MyWatchFace.SETTING_TIME_ID, it, scope) },
                        onSecondsCheckChanged = { saveBoolStyle(context, session, MyWatchFace.SETTING_SECONDS_ID, it, scope) },
                        onTimeWeightChanged = { saveListStyle(context, session, MyWatchFace.SETTING_TIME_WEIGHT_ID, it.toString(), scope) }
                    )
                    // Page 1: Date
                    1 -> VerticalSwitchWeightPage(
                        title = "날짜",
                        isChecked = showDate,
                        weightIndex = dateWeight.toIntOrNull() ?: 0,
                        currentPage = 1,
                        isOtherElementVisible = showBattery,
                        onCheckChanged = { saveBoolStyle(context, session, MyWatchFace.SETTING_DATE_ID, it, scope) },
                        onWeightChanged = { saveListStyle(context, session, MyWatchFace.SETTING_DATE_WEIGHT_ID, it.toString(), scope) }
                    )
                    // Page 2: Battery
                    2 -> VerticalSwitchWeightPage(
                        title = "배터리",
                        isChecked = showBattery,
                        weightIndex = batteryWeight.toIntOrNull() ?: 2,
                        currentPage = 2,
                        isOtherElementVisible = showDate,
                        onCheckChanged = { saveBoolStyle(context, session, MyWatchFace.SETTING_BATTERY_ID, it, scope) },
                        onWeightChanged = { saveListStyle(context, session, MyWatchFace.SETTING_BATTERY_WEIGHT_ID, it.toString(), scope) }
                    )

                    // Page 3: Font Style
                    3 -> FontStyleVisualPage(
                        selectedIndex = fontStyle.toIntOrNull() ?: 0,
                        optionsCount = 4
                    ) { idx -> saveListStyle(context, session, MyWatchFace.SETTING_FONT_STYLE_ID, idx.toString(), scope) }

                    // Page 4: Weather
                    4 -> WeatherPage(
                        isChecked = showWeather,
                        onCheckChanged = { saveBoolStyle(context, session, MyWatchFace.SETTING_WEATHER_ANIM_ID, it, scope) }
                    )
                }
            }
        }
        SystemTopPageIndicator(pagerState = pagerState)
    }
}

/**
 * Font Style Page with Visual Cutout
 * Renders a dim overlay with a hole in the center to highlight the watch face content.
 */
@Composable
fun FontStyleVisualPage(
    selectedIndex: Int,
    optionsCount: Int,
    onSelect: (Int) -> Unit
) {
    val vPagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { optionsCount })

    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )


    LaunchedEffect(vPagerState.currentPage) {
        if (vPagerState.currentPage != selectedIndex) onSelect(vPagerState.currentPage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Cutout Background (Dark overlay with a clear hole)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } // Essential for BlendMode.Clear
        ) {
            // Draw standard dim background
            drawRect(color = Color.Black.copy(alpha = 0.6f))

            // Calculate cutout area (Center rectangle, approx size for a clock)
            val cutoutWidth = size.width * 0.4f
            val cutoutHeight = size.height * 0.32f
            val left = (size.width - cutoutWidth) / 2
            val top = (size.height - cutoutHeight) / 2 + 9

            // Punch the hole (Clear mode)
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                blendMode = BlendMode.Clear
            )

            // Draw Blue Highlight Border
            drawRoundRect(
                color = Color(0xFF5F97FF).copy(alpha = blinkAlpha),
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // 2. Invisible Pager for gestures
        VerticalPager(state = vPagerState, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Transparent))
        }

        // 3. Top Title (Small, like the photo)
        Text(
            text = "글꼴",
            color = Color.White,
            style = MaterialTheme.typography.caption3,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 15.dp)
        )

        // 4. Right Indicator
        RightSideDotIndicator(totalCount = optionsCount, selectedIndex = vPagerState.currentPage)
    }
}

@Composable
fun TimeAndSecondsPage(
    timeChecked: Boolean, secondsChecked: Boolean, timeWeightIndex: Int,
    onTimeCheckChanged: (Boolean) -> Unit, onSecondsCheckChanged: (Boolean) -> Unit, onTimeWeightChanged: (Int) -> Unit
) {
    val vPagerState = rememberPagerState(initialPage = timeWeightIndex, pageCount = { 4 })
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(vPagerState.currentPage) { if (vPagerState.currentPage != timeWeightIndex) onTimeWeightChanged(vPagerState.currentPage) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 1. Cutout Background (Dark overlay with a clear hole)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } // Essential for BlendMode.Clear
        ) {
            // Draw standard dim background
            drawRect(color = Color.Black.copy(alpha = 0.6f))

            // Calculate cutout area (Center rectangle, approx size for a clock)
            val cutoutWidth = size.width * 0.4f
            val cutoutHeight = size.height * 0.15f
            val left = (size.width - cutoutWidth) / 2
            val top = (size.height - cutoutHeight) / 3 + 23

            // Punch the hole (Clear mode)
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                blendMode = BlendMode.Clear
            )

            // Draw Blue Highlight Border
            drawRoundRect(
                color = Color(0xFF5F97FF).copy(alpha = blinkAlpha),
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        VerticalPager(state = vPagerState, modifier = Modifier.fillMaxSize()) { Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(-10.dp)
        ) {
            Row(modifier = Modifier.heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "시간",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = timeChecked,
                    onCheckedChange = onTimeCheckChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.DarkGray))
            }
            Row(
                modifier = Modifier.heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "초",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 10.dp))
                Switch(
                    checked = secondsChecked,
                    onCheckedChange = onSecondsCheckChanged,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.DarkGray))
            }
        }
        RightSideDotIndicator(totalCount = 4, selectedIndex = vPagerState.currentPage)
    }
}

@Composable
fun VerticalSwitchWeightPage(
    title: String,
    isChecked: Boolean,
    weightIndex: Int,
    currentPage: Int,
    isOtherElementVisible: Boolean,
    onCheckChanged: (Boolean) -> Unit,
    onWeightChanged: (Int) -> Unit
) {
    val vPagerState = rememberPagerState(initialPage = weightIndex, pageCount = { 4 })
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(vPagerState.currentPage) { if (vPagerState.currentPage != weightIndex) onWeightChanged(vPagerState.currentPage) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 1. Cutout Background (Dark overlay with a clear hole)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } // Essential for BlendMode.Clear
        ) {
            // Draw standard dim background
            drawRect(color = Color.Black.copy(alpha = 0.6f))

            // Calculate cutout area
            val cutoutWidth = if (currentPage == 1) size.width * 0.25f else size.width * 0.2f
            val cutoutHeight = if (currentPage == 1) size.height * 0.1f else size.height * 0.1f
            val left = (size.width - cutoutWidth) / 2
            val baseTop = (size.height - cutoutHeight) * 2 / 3
            val top = if (currentPage == 1) {
                if (isOtherElementVisible) {
                    baseTop - 35
                } else {
                    baseTop - 22
                }
            } else {
                if (isOtherElementVisible) {
                    baseTop - 7
                } else {
                    baseTop - 18
                }
            }

            // Punch the hole (Clear mode)
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                blendMode = BlendMode.Clear
            )

            // Draw Blue Highlight Border
            drawRoundRect(
                color = Color(0xFF5F97FF).copy(alpha = blinkAlpha),
                topLeft = Offset(left, top),
                size = Size(cutoutWidth, cutoutHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        VerticalPager(state = vPagerState, modifier = Modifier.fillMaxSize()) { Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) }

        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 35.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 8.dp)
            )
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4CAF50),
                    uncheckedThumbColor = Color.LightGray,
                    uncheckedTrackColor = Color.DarkGray)
            )
        }
        RightSideDotIndicator(totalCount = 4, selectedIndex = vPagerState.currentPage)
    }
}

@Composable
fun WeatherPage(isChecked: Boolean, onCheckChanged: (Boolean) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        ConfigToggleRow("날씨 효과", isChecked, onCheckChanged)
    }
}

@Composable
fun RightSideDotIndicator(totalCount: Int, selectedIndex: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        val activeColor = Color.White
        val inactiveColor = Color(0xFF888888) // Light gray
        val activeDotSize = 6.dp
        val inactiveDotSize = 4.dp
        val spacingDegrees = 6.0

        val indicatorPadding = 8.dp
        val radiusPx = (width / 2f) - with(density) { indicatorPadding.toPx() }
        val centerX = width / 2f
        val centerY = height / 2f

        val startAngle = 0.0 - ((totalCount - 1) * spacingDegrees / 2.0)

        repeat(totalCount) { index ->
            val angleDeg = startAngle + (index * spacingDegrees)
            val angleRad = Math.toRadians(angleDeg)

            val dotX = centerX + (radiusPx * cos(angleRad)).toFloat()
            val dotY = centerY + (radiusPx * sin(angleRad)).toFloat()

            val isSelected = index == selectedIndex
            val sizeDp = if (isSelected) activeDotSize else inactiveDotSize
            val color = if (isSelected) activeColor else inactiveColor
            val sizePx = with(density) { sizeDp.toPx() }

            Box(
                modifier = Modifier
                    .offset { IntOffset((dotX - sizePx / 2).toInt(), (dotY - sizePx / 2).toInt()) }
                    .size(sizeDp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
fun ConfigToggleRow(title: String, checked: Boolean, onDataChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onDataChanged, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4CAF50), uncheckedThumbColor = Color.LightGray, uncheckedTrackColor = Color.DarkGray))
    }
}

// UserStyle Helper Functions (Keep as is)
fun getBoolStyle(
    style: UserStyle,settingId: String): Boolean { for ((setting, option) in style) { if (setting.id.value.toString() == settingId && option is UserStyleSetting.BooleanUserStyleSetting.BooleanOption) return option.value }; return true }
fun getListStyleId(style: UserStyle, settingId: String): String { for ((setting, option) in style) { if (setting.id.value.toString() == settingId) return option.id.toString() }; return "0" }
fun saveBoolStyle(context: Context, session: EditorSession, settingId: String, isEnabled: Boolean, scope: CoroutineScope) { scope.launch(Dispatchers.Main.immediate) { val mutableStyle = session.userStyle.value.toMutableUserStyle(); var targetSetting: UserStyleSetting.BooleanUserStyleSetting? = null; for ((setting, _) in mutableStyle) { if (setting.id.value.toString() == settingId && setting is UserStyleSetting.BooleanUserStyleSetting) { targetSetting = setting; break } }; if (targetSetting != null) { val newOption = targetSetting.options.find { (it as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption)?.value == isEnabled }; if (newOption != null) { mutableStyle[targetSetting] = newOption; session.userStyle.value = mutableStyle.toUserStyle() } }; context.getSharedPreferences("MyWatchPrefs", Context.MODE_PRIVATE).edit().putBoolean(settingId, isEnabled).apply() } }
fun saveListStyle(context: Context, session: EditorSession, settingId: String, optionId: String, scope: CoroutineScope) { scope.launch(Dispatchers.Main.immediate) { val mutableStyle = session.userStyle.value.toMutableUserStyle(); var targetSetting: UserStyleSetting.ListUserStyleSetting? = null; for ((setting, _) in mutableStyle) { if (setting.id.value.toString() == settingId && setting is UserStyleSetting.ListUserStyleSetting) { targetSetting = setting; break } }; if (targetSetting != null) { val newOption = targetSetting.options.find { it.id.toString() == optionId }; if (newOption != null) { mutableStyle[targetSetting] = newOption; session.userStyle.value = mutableStyle.toUserStyle() } } } }

@Composable
fun SystemTopPageIndicator(pagerState: androidx.wear.compose.foundation.pager.PagerState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
        val spacingDegrees = 6.0; val indicatorPadding = 8.dp
        val radiusPx = (height / 2f) - with(density) { indicatorPadding.toPx() }
        val centerX = width / 2f; val centerY = height / 2f
        val startAngle = 270.0 - ((pagerState.pageCount - 1) * spacingDegrees / 2.0)
        repeat(pagerState.pageCount) { index ->
            val angleRad = Math.toRadians(startAngle + (index * spacingDegrees))
            val dotX = centerX + (radiusPx * cos(angleRad)).toFloat(); val dotY = centerY + (radiusPx * sin(angleRad)).toFloat()
            val isSelected = pagerState.currentPage == index
            val sizeDp = if (isSelected) 6.dp else 4.dp
            val color = if (isSelected) Color.White else Color(0xFF888888)
            val sizePx = with(density) { sizeDp.toPx() }
            Box(modifier = Modifier.offset { IntOffset((dotX - sizePx / 2).toInt(), (dotY - sizePx / 2).toInt()) }.size(sizeDp).background(color, CircleShape))
        }
    }
}