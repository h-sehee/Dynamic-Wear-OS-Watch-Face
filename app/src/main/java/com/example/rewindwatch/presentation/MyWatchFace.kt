package com.example.rewindwatch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.app.ActivityCompat
import androidx.wear.watchface.*
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// ==========================================
// Enums & Data Classes
// ==========================================

/**
 * Represents the state of the sky based on solar time.
 */
enum class SkyState { DAWN, DAY, SUNSET, NIGHT, UNKNOWN }

/**
 * Represents simplified weather conditions for animation.
 */
enum class WeatherType { CLEAR, RAIN, SNOW }

/**
 * Holds sunrise and sunset timestamps.
 */
data class SolarSchedule(val sunrise: Long, val sunset: Long)

/**
 * Solar phase for a moment in time. Windows are asymmetric to match how the
 * transitions are perceived: dawn is brief and centred on sunrise; sunset
 * includes the golden-hour build-up and a short afterglow.
 */
internal fun skyStateAt(nowEpochSec: Long, s: SolarSchedule): SkyState {
    val dawnStart = s.sunrise - 30 * 60
    val dawnEnd = s.sunrise + 15 * 60
    val sunsetStart = s.sunset - 45 * 60
    val sunsetEnd = s.sunset + 20 * 60
    return when {
        nowEpochSec in dawnStart..dawnEnd -> SkyState.DAWN
        nowEpochSec in (dawnEnd + 1)..(sunsetStart - 1) -> SkyState.DAY
        nowEpochSec in sunsetStart..sunsetEnd -> SkyState.SUNSET
        else -> SkyState.NIGHT
    }
}

/** ~11 km resolution: enough to debug weather lookups without logging a home address. */
private fun coarse(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

// =============================================================================
// Layout constants. Ratios are fractions of the display width so the face
// scales across 396px / 450px watches; px values are absolute.
// =============================================================================

/** Sky bitmap edge as a multiple of the display; the margin is what parallax pans across. */
private const val SKY_SCALE = 1.4f
/** Logo width as a fraction of the display width. */
private const val LOGO_WIDTH_RATIO = 0.6f

/** Accelerometer delta (m/s²) that maps to full parallax travel. */
private const val TILT_LIMIT = 2.5f
/** Parallax travel in px per unit of tilt: sky moves most, logo a little, frame shadow opposite. */
private const val DEPTH_SKY = 20f
private const val DEPTH_LOGO = 3f
private const val DEPTH_SHADOW = 5f
/** Hand shadow offset in px, applied in the hand's own rotated frame (viewer-as-light-source). */
private const val HAND_SHADOW_OFFSET = 6f
private const val CENTER_CAP_SHADOW_OFFSET = 4f

private const val TIME_TEXT_SIZE = 0.079f
private const val TIME_Y_ABOVE_CENTER = 0.05f
private const val DATE_TEXT_SIZE = 0.05f
private const val DATE_Y_WITH_BATTERY = 0.08f
private const val DATE_Y_ALONE = 0.11f          // also the ambient position
private const val BATTERY_ICON_WIDTH = 0.04f
private const val BATTERY_Y_WITH_DATE = 0.13f
private const val BATTERY_Y_ALONE = 0.11f

/** Low-pass factor for the accelerometer: smoothed = old*(1-a) + sample*a. */
private const val SENSOR_SMOOTHING = 0.1f
/** Tilt change (smoothed units) below which a sensor event doesn't trigger a redraw. */
private const val REDRAW_TILT_THRESHOLD = 0.05f
/** Minimum spacing between sensor-driven redraws (~30 fps cap). */
private const val REDRAW_MIN_INTERVAL_MS = 33L

class MyWatchFace : WatchFaceService() {

    companion object {
        private const val TAG = "RewindWatch"

        // Configuration IDs for UserStyle settings
        const val SETTING_TIME_ID = "setting_time"
        const val SETTING_SECONDS_ID = "setting_seconds"
        const val SETTING_DATE_ID = "setting_date"
        const val SETTING_BATTERY_ID = "setting_battery"
        const val SETTING_WEATHER_ANIM_ID = "setting_weather_anim"

        const val SETTING_FONT_STYLE_ID = "setting_font_style"
        const val SETTING_TIME_WEIGHT_ID = "setting_time_weight"
        const val SETTING_DATE_WEIGHT_ID = "setting_date_weight"
        const val SETTING_BATTERY_WEIGHT_ID = "setting_battery_weight"

        // OpenWeatherMap API Key
        private const val API_KEY = BuildConfig.OPEN_WEATHER_API_KEY

        /** Sent by MainActivity after a location permission grant. */
        const val ACTION_REFRESH_WEATHER = "com.example.rewindwatch.action.REFRESH_WEATHER"

        // Weather refresh cadence; also the staleness threshold used when the
        // face becomes visible again after the watch slept through the timer.
        private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
        // While weather is stale (e.g. offline), visibility-triggered refetches
        // are spaced at least this far apart so wrist raises don't hammer the
        // network; the 30-min loop and the permission broadcast are unaffected.
        private const val WEATHER_VISIBLE_RETRY_MS = 5 * 60 * 1000L

        // Animation Configuration
        private const val FRAME_DURATION_MS = 180L

        // --- User Preferences (Toggles) ---
        @Volatile private var showTime: Boolean = true
        @Volatile private var showSeconds: Boolean = true
        @Volatile private var showDate: Boolean = true
        @Volatile private var showBattery: Boolean = true
        @Volatile private var showWeatherAnimation: Boolean = true
        @Volatile private var currentFontStyleId = "0"
        @Volatile private var timeWeightId = "2"
        @Volatile private var dateWeightId = "0"
        @Volatile private var batteryWeightId = "2"
    }

    override fun createUserStyleSchema(): UserStyleSchema {
        // 1. Time Toggle
        val timeSetting = UserStyleSetting.BooleanUserStyleSetting(
            UserStyleSetting.Id(SETTING_TIME_ID),
            resources,
            R.string.config_time_title,
            R.string.config_time_title,
            null,
            listOf(WatchFaceLayer.BASE),
            true
        )

        // 2. Seconds Toggle
        val secondsSetting = UserStyleSetting.BooleanUserStyleSetting(
            UserStyleSetting.Id(SETTING_SECONDS_ID),
            resources,
            R.string.config_seconds_title,
            R.string.config_seconds_title,
            null,
            listOf(WatchFaceLayer.BASE),
            true

        )

        // 3. Date Toggle
        val dateSetting = UserStyleSetting.BooleanUserStyleSetting(
            UserStyleSetting.Id(SETTING_DATE_ID),
            resources,
            R.string.config_date_title,
            R.string.config_date_title,
            null,
            listOf(WatchFaceLayer.BASE),
            true
        )

        // 4. Battery Toggle
        val batterySetting = UserStyleSetting.BooleanUserStyleSetting(
            UserStyleSetting.Id(SETTING_BATTERY_ID),
            resources,
            R.string.config_battery_title,
            R.string.config_battery_title,
            null,
            listOf(WatchFaceLayer.BASE),
            true
        )

        // 5. Weather Animation Toggle
        val weatherAnimSetting = UserStyleSetting.BooleanUserStyleSetting(
            UserStyleSetting.Id(SETTING_WEATHER_ANIM_ID),
            resources,
            R.string.config_weather_title,
            R.string.config_weather_title,
            null,
            listOf(WatchFaceLayer.BASE),
            true
        )

        // 7. Font Style List Setting
        val fontStyleSetting = UserStyleSetting.ListUserStyleSetting(
            UserStyleSetting.Id(SETTING_FONT_STYLE_ID),
            resources,
            R.string.config_font_style_title,
            R.string.config_font_style_title,
            null,

            listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), resources, R.string.font_style_sans, R.string.font_style_sans, null), // Sans (Basic)
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), resources, R.string.font_style_serif, R.string.font_style_serif, null), // Serif
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), resources, R.string.font_style_mono, R.string.font_style_mono, null), // Mono
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), resources, R.string.font_style_condensed, R.string.font_style_condensed, null), // Condensed
            ),
            listOf(WatchFaceLayer.BASE),
            UserStyleSetting.ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id("0"), resources, R.string.font_style_sans, R.string.font_style_sans, null
            )
        )

        // 7. Font Weight List Setting
        fun createWeightSetting(id: String, defaultId: String): UserStyleSetting.ListUserStyleSetting {
            val options = listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), resources, R.string.font_weight_normal, R.string.font_weight_normal, null), // Normal
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), resources, R.string.font_weight_medium, R.string.font_weight_medium, null), // Medium
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), resources, R.string.font_weight_bold, R.string.font_weight_bold, null), // Bold
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), resources, R.string.font_weight_extrabold, R.string.font_weight_extrabold, null)  // ExtraBold
            )
            val defaultOption = options.find { it.id.toString() == defaultId } ?: options[0]
            return UserStyleSetting.ListUserStyleSetting(
                UserStyleSetting.Id(id), resources, R.string.config_font_weight_title, R.string.config_font_weight_title, null,
                options,
                listOf(WatchFaceLayer.BASE),
                defaultOption
            )
        }

        return UserStyleSchema(listOf(timeSetting, secondsSetting, dateSetting, batterySetting, weatherAnimSetting,
            fontStyleSetting,
            createWeightSetting(SETTING_TIME_WEIGHT_ID, "2"),
            createWeightSetting(SETTING_DATE_WEIGHT_ID, "0"),
            createWeightSetting(SETTING_BATTERY_WEIGHT_ID, "2")))
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = MyRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )
        return WatchFace(WatchFaceType.ANALOG, renderer)
    }

    class MySharedAssets : Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    inner class MyRenderer(
        private val context: Context,
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository
    ) : Renderer.CanvasRenderer2<MySharedAssets>(
        surfaceHolder,
        currentUserStyleRepository,
        watchState,
        CanvasType.HARDWARE,
        // 1000ms baseline tick (covers seconds hand & minute updates).
        // The accelerometer listener will invalidate() out-of-band when the
        // wrist actually tilts, so parallax stays responsive without burning
        // 60fps when the watch is still.
        1000L,
        clearWithBackgroundTintBeforeRenderingHighlightLayer = true
    ), SensorEventListener {

        // --- Hardware & System Managers ---
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private lateinit var fusedLocationClient: FusedLocationProviderClient

        // Static assets are preloaded on IO, but the very first render must
        // never race that load: the system snapshots a headless instance right
        // after creation for the favorites/picker thumbnail, and a frame drawn
        // before hands/frame are decoded gets cached as the face's image.
        private val staticAssetsLock = Any()
        @Volatile private var staticAssetsLoaded = false
        // Set under staticAssetsLock by onDestroy. scope.cancel() cannot interrupt
        // a decode already running on IO; without this flag that decode would
        // finish after teardown and assign bitmaps nobody recycles.
        @Volatile private var destroyed = false

        // --- Sensor State ---
        private var gyroX = 0f
        private var gyroY = 0f
        private var baseX = 0f
        private var baseY = 0f
        private var needsReset = true
        private var isSensorRegistered = false
        private var lastInvalidatedGyroX = 0f
        private var lastInvalidatedGyroY = 0f
        private var lastSensorInvalidateAt = 0L

        // The accelerometer must be off whenever the face isn't actually on
        // screen — a registered listener keeps the SoC out of deep sleep even
        // when nothing is drawn. drawMode alone misses the "not visible but
        // still interactive" case (another app in front, screen off without
        // AOD), so visibility and ambient are tracked separately.
        @Volatile private var isFaceVisible = true
        @Volatile private var isAmbientNow = false
        @Volatile private var isInteractiveDrawMode = true
        // Headless instances (editor preview, system thumbnails) render on
        // demand and never get ambient callbacks — a sensor registered there
        // would stay on until the instance dies. sensorservice showed exactly
        // that: a second listener held for ~17h overnight on v2.0.
        private val isHeadless = watchState.isHeadless

        // --- Weather refresh trigger (see ACTION_REFRESH_WEATHER) ---
        @Volatile private var lastWeatherSuccessAt = 0L   // last parsed response
        @Volatile private var lastWeatherAttemptAt = 0L   // last fetch started (dedupe)
        private var isRefreshReceiverRegistered = false
        private val refreshWeatherReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "Weather refresh requested")
                scope.launch(Dispatchers.IO) { fetchRealWeather(force = true) }
            }
        }

        // --- Battery State ---
        private var batteryLevel = 100
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100 / scale.toFloat()).toInt()
                }
            }
        }

        // --- Graphic Resources ---
        // All four skies are decoded once (630px RGB_565, ~0.8 MB each) so a
        // dawn/day/sunset/night transition is a reference swap on the UI thread,
        // never a decode. skySrc holds the decoded originals; sky holds copies
        // scaled to the current bounds (the same object on a 450px display).
        private val skySrc = java.util.EnumMap<SkyState, Bitmap>(SkyState::class.java)
        private val sky = java.util.EnumMap<SkyState, Bitmap>(SkyState::class.java)
        private var currentBackgroundBitmap: Bitmap? = null
        private var currentSkyState: SkyState = SkyState.UNKNOWN
        private val weatherDestRect = Rect()

        // Weather Animation Frames (Loaded on demand)
        private val rainFrames = Collections.synchronizedList(ArrayList<Bitmap>())
        private val snowFrames = Collections.synchronizedList(ArrayList<Bitmap>())

        // Static Assets — *Src holds the unscaled original so resize never compounds
        @Volatile private var logoBitmapSrc: Bitmap? = null
        @Volatile private var hourHandBitmapSrc: Bitmap? = null
        @Volatile private var minuteHandBitmapSrc: Bitmap? = null
        @Volatile private var centerUnderBitmapSrc: Bitmap? = null
        @Volatile private var centerOverBitmapSrc: Bitmap? = null
        @Volatile private var frameCenterSrc: Bitmap? = null

        @Volatile private var logoBitmap: Bitmap? = null
        @Volatile private var hourHandBitmap: Bitmap? = null
        @Volatile private var minuteHandBitmap: Bitmap? = null
        @Volatile private var centerUnderBitmap: Bitmap? = null
        @Volatile private var centerOverBitmap: Bitmap? = null
        @Volatile private var frameCenter: Bitmap? = null

        // --- Paints ---
        private val digitalTextPaint = Paint().apply {
            color = Color.BLACK
            alpha = 180
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val dateTextPaint = Paint().apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        private val batteryStrokePaint = Paint().apply {
            color = Color.BLACK
            alpha = 180
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true

        }
        private val batteryFillPaint = Paint().apply {
            color = Color.BLACK
            alpha = 180
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val shadowPaint = Paint().apply {
            colorFilter = PorterDuffColorFilter(Color.argb(100, 0, 0, 0), PorterDuff.Mode.SRC_IN)
        }
        private fun createGoldPaint(saturation: Float, shift: Float, contrast: Float = 1.3f): Paint {
            return Paint().apply {
                val cm = ColorMatrix().apply {
                    setSaturation(saturation)
                    val scale = contrast
                    val translate = (-0.5f * scale + 0.5f) * 255f + shift

                    val matrix = floatArrayOf(
                        scale, 0f,    0f,    0f, translate,
                        0f,    scale, 0f,    0f, translate,
                        0f,    0f,    scale, 0f, translate,
                        0f,    0f,    0f,    1f, 0f
                    )
                    postConcat(ColorMatrix(matrix))
                }
                colorFilter = ColorMatrixColorFilter(cm)
                isAntiAlias = true
                isFilterBitmap = true
            }
        }

        private val brightPaint = createGoldPaint(saturation = 1.5f, shift = 50f)
        private val lightPaint  = createGoldPaint(saturation = 1.3f, shift = 30f)
        private val nightPaint  = createGoldPaint(saturation = 1.1f, shift = 20f)

        private val grayScalePaint = Paint().apply {
            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
            // AOD is on ~16h/day and OLED power scales with lit brightness,
            // so this is the single biggest battery lever. 0.4 ≈ Samsung's
            // stock AOD faces; still readable indoors.
            val scale = 0.4f
            val darkMatrix = ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, 0f,
                0f, scale, 0f, 0f, 0f,
                0f, 0f, scale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(darkMatrix)
            colorFilter = ColorMatrixColorFilter(matrix)
            isAntiAlias = true
        }
        private val snowPaint = Paint().apply {
            val brightness = 70f
            val matrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, brightness,
                0f, 1f, 0f, 0f, brightness,
                0f, 0f, 1f, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            colorFilter = ColorMatrixColorFilter(matrix)
            isAntiAlias = true
        }
        private val rainPaint = Paint().apply {
            val brightness = 80f
            val matrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, brightness,
                0f, 1f, 0f, 0f, brightness,
                0f, 0f, 1f, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            colorFilter = ColorMatrixColorFilter(matrix)
            isAntiAlias = true
        }
        private val logoPaint = Paint().apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            alpha = 180
            isAntiAlias = true
        }
        private val vignettePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        // Layout Variables
        private var screenCenterX = 0f
        private var screenCenterY = 0f
        @Volatile private var currentWidth = 0
        @Volatile private var currentHeight = 0

        // Weather & Solar State
        @Volatile private var currentWeather = WeatherType.CLEAR
        // Reasonable fallback until the weather API responds. Anchored to
        // *today's* 06:00/18:00 instead of "now ± offset", so a watch face
        // started at 2 AM doesn't believe sunset is at 9 AM.
        @Volatile private var solarSchedule: SolarSchedule = run {
            val zone = java.time.ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            SolarSchedule(
                sunrise = today.atTime(6, 0).atZone(zone).toEpochSecond(),
                sunset = today.atTime(18, 0).atZone(zone).toEpochSecond()
            )
        }

        // Utils
        private val scope = CoroutineScope(Dispatchers.Main)
        private val res = context.resources
        private val timeFormatterSeconds = DateTimeFormatter.ofPattern("hh:mm:ss")
        private val timeFormatterNoSeconds = DateTimeFormatter.ofPattern("hh:mm")
        private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH)

        override suspend fun createSharedAssets(): MySharedAssets = MySharedAssets()

        init {
            // Start async initialization
            scope.launch(Dispatchers.IO) {
                try {
                    loadStaticResources()
                    // Force the next render to rerun updateLayoutAndScale even
                    // if bounds haven't changed, so the freshly loaded *Src
                    // bitmaps get scaled into the displayed bitmap fields.
                    withContext(Dispatchers.Main.immediate) {
                        currentWidth = 0
                        currentHeight = 0
                        invalidate()
                    }
                    // Headless instances (editor preview, picker thumbnails) only
                    // ever draw a snapshot: skip the network fetch, the 30-min
                    // polling loop and the ~19 MB weather frame set entirely.
                    if (!isHeadless) fetchRealWeather()
                    withContext(Dispatchers.Main.immediate) { invalidate() }
                } catch (e: Exception) {
                    Log.e(TAG, "Init load failed", e)
                }
            }

            // Initialize services
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)

            // Watch user style changes
            scope.launch(Dispatchers.Main) {
                currentUserStyleRepository.userStyle.collect { userStyle ->
                    updateWatchFaceStyle(userStyle)
                }
            }

            // Gate the accelerometer on real visibility, not just drawMode.
            scope.launch(Dispatchers.Main) {
                watchState.isVisible.collect { visible ->
                    isFaceVisible = visible ?: true
                    syncSensorState()
                    // delay()-based polling stalls while the watch sleeps, so a
                    // face that just came back on screen may hold hours-old
                    // weather. Staleness is judged on the last *successful*
                    // response; attempts are spaced so an offline watch doesn't
                    // retry on every wrist raise.
                    val now = System.currentTimeMillis()
                    if (visible == true && !isHeadless &&
                        now - lastWeatherSuccessAt > WEATHER_REFRESH_MS &&
                        now - lastWeatherAttemptAt > WEATHER_VISIBLE_RETRY_MS
                    ) {
                        scope.launch(Dispatchers.IO) { fetchRealWeather() }
                    }
                }
            }
            scope.launch(Dispatchers.Main) {
                watchState.isAmbient.collect { ambient ->
                    isAmbientNow = ambient ?: false
                    syncSensorState()
                }
            }

            if (!isHeadless) {
                startWeatherUpdater()
                // MainActivity fires this right after the user grants location,
                // so the face switches from the Seoul fallback immediately
                // instead of at the next 30-min tick.
                androidx.core.content.ContextCompat.registerReceiver(
                    context, refreshWeatherReceiver,
                    IntentFilter(ACTION_REFRESH_WEATHER),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isRefreshReceiverRegistered = true
            }
        }

        private fun updateWatchFaceStyle(userStyle: UserStyle) {
            val timeSetting = userStyle[UserStyleSetting.Id(MyWatchFace.SETTING_TIME_ID)]
                    as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption
            showTime = timeSetting?.value ?: true

            val secondsSetting = userStyle[UserStyleSetting.Id(MyWatchFace.SETTING_SECONDS_ID)]
                    as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption
            showSeconds= secondsSetting?.value ?: true

            val dateSetting = userStyle[UserStyleSetting.Id(MyWatchFace.SETTING_DATE_ID)]
                    as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption
            showDate = dateSetting?.value ?: true

            val batterySetting = userStyle[UserStyleSetting.Id(MyWatchFace.SETTING_BATTERY_ID)]
                    as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption
            showBattery = batterySetting?.value ?: true

            val weatherAnimSetting =
                userStyle[UserStyleSetting.Id(MyWatchFace.SETTING_WEATHER_ANIM_ID)]
                        as? UserStyleSetting.BooleanUserStyleSetting.BooleanOption
            showWeatherAnimation = weatherAnimSetting?.value ?: true

            fun getListId(id: String) = (userStyle[UserStyleSetting.Id(id)] as? UserStyleSetting.ListUserStyleSetting.ListOption)?.id?.toString() ?: "0"
            currentFontStyleId = getListId(SETTING_FONT_STYLE_ID)
            timeWeightId = getListId(SETTING_TIME_WEIGHT_ID)
            dateWeightId = getListId(SETTING_DATE_WEIGHT_ID)
            batteryWeightId = getListId(SETTING_BATTERY_WEIGHT_ID)

            updateInteractiveTick()
            invalidate()
        }

        /**
         * The interactive baseline redraw is 1s to save battery, but the rain/snow
         * overlay picks its frame from the wall clock on every draw, so at 1 fps it
         * skips 5-6 frames per tick and stutters. While an overlay is active, tick
         * at exactly one animation frame (FRAME_DURATION_MS); otherwise stay at 1s.
         * Ambient is unaffected — it has its own once-a-minute schedule.
         */
        private fun updateInteractiveTick() {
            val animating = showWeatherAnimation && currentWeather != WeatherType.CLEAR
            val wanted = if (animating) FRAME_DURATION_MS else 1000L
            scope.launch(Dispatchers.Main.immediate) {
                if (interactiveDrawModeUpdateDelayMillis != wanted) {
                    interactiveDrawModeUpdateDelayMillis = wanted
                    invalidate()
                }
            }
        }

        private fun getTypefaceFor(weightId: String): Typeface {
            val baseTypeface = when (currentFontStyleId) {
                "1" -> Typeface.SERIF
                "2" -> Typeface.MONOSPACE
                "3" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
            val weightVal = when(weightId) {
                "0" -> 400 // Normal
                "1" -> 500 // Medium
                "2" -> 700 // Bold
                "3" -> 900 // ExtraBold
                else -> 400
            }
            return Typeface.create(baseTypeface, weightVal, false)
        }

        /**
         * Fetches weather data. Optimizes battery by checking cached location first.
         * Falls back to active location request if cache is empty.
         */
        /**
         * @param force bypass the 10s dedupe window (used right after the user
         *   grants location, where a fresh fetch is the whole point).
         */
        private fun fetchRealWeather(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastWeatherAttemptAt < 10_000L) return
            lastWeatherAttemptAt = now

            if (API_KEY.isBlank() || API_KEY == "null") {
                Log.e(TAG, "OPEN_WEATHER_API_KEY is not set in local.properties — skipping weather fetch")
                return
            }

            if (ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Location permission not granted — falling back to Seoul")
                fetchWeatherByCity("Seoul")
                return
            }

            // 1. Check last known location (Battery Saver)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "Using last known location ${coarse(location.latitude)},${coarse(location.longitude)}")
                    fetchWeatherByCoord(location.latitude, location.longitude)
                } else {
                    // 2. Request active location if cache is empty
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { currentLocation ->
                            if (currentLocation != null) {
                                Log.d(TAG, "Got current location ${coarse(currentLocation.latitude)},${coarse(currentLocation.longitude)}")
                                fetchWeatherByCoord(currentLocation.latitude, currentLocation.longitude)
                            } else {
                                Log.w(TAG, "getCurrentLocation returned null — falling back to Seoul")
                                fetchWeatherByCity("Seoul")
                            }
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "getCurrentLocation failed — falling back to Seoul", it)
                            fetchWeatherByCity("Seoul")
                        }
                }
            }.addOnFailureListener {
                Log.w(TAG, "lastLocation failed — falling back to Seoul", it)
                fetchWeatherByCity("Seoul")
            }
        }

        private fun fetchWeatherByCoord(lat: Double, lon: Double) {
            scope.launch(Dispatchers.IO) {
                val urlString = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$API_KEY"
                if (!parseAndApplyWeather(urlString)) {
                    kotlinx.coroutines.delay(60_000L)
                    Log.w(TAG, "Retrying weather fetch after 60s")
                    parseAndApplyWeather(urlString)
                }
            }
        }

        private fun fetchWeatherByCity(city: String) {
            scope.launch(Dispatchers.IO) {
                val urlString = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$API_KEY"
                if (!parseAndApplyWeather(urlString)) {
                    kotlinx.coroutines.delay(60_000L)
                    Log.w(TAG, "Retrying weather fetch after 60s")
                    parseAndApplyWeather(urlString)
                }
            }
        }

        /**
         * Parses JSON from OpenWeatherMap API.
         * Configured with timeouts to handle Wear OS Bluetooth proxy delays.
         * Returns true on success, false on failure (caller may retry).
         */
        private suspend fun parseAndApplyWeather(urlString: String): Boolean {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = withContext(Dispatchers.IO) {
                    url.openConnection() as HttpURLConnection
                }

                // Timeout settings for Wear OS connectivity
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.connect()

                val code = connection.responseCode
                if (code != 200) {
                    val sanitized = urlString.replace(API_KEY, "<redacted>")
                    when (code) {
                        401 -> Log.e(TAG, "Weather API returned 401 (bad API key) for $sanitized")
                        429 -> Log.e(TAG, "Weather API returned 429 (rate limit) for $sanitized")
                        else -> Log.w(TAG, "Weather API returned $code for $sanitized")
                    }
                    return false
                }

                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonText)
                val weatherArray = json.getJSONArray("weather")
                val mainWeather = weatherArray.getJSONObject(0).getString("main")
                val sys = json.getJSONObject("sys")
                val sunrise = sys.getLong("sunrise")
                val sunset = sys.getLong("sunset")

                solarSchedule = SolarSchedule(sunrise, sunset)

                val newWeather = when (mainWeather) {
                    "Rain", "Drizzle", "Thunderstorm" -> WeatherType.RAIN
                    "Snow" -> WeatherType.SNOW
                    else -> WeatherType.CLEAR
                }
                Log.d(TAG, "Weather OK: $mainWeather → $newWeather (sunrise=$sunrise, sunset=$sunset)")

                updateWeatherResources(newWeather)
                lastWeatherSuccessAt = System.currentTimeMillis()
                withContext(Dispatchers.Main) { invalidate() }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Weather fetch failed: ${e.javaClass.simpleName}: ${e.message}")
                return false
            } finally {
                connection?.disconnect()
            }
        }

        /**
         * Efficiently loads or unloads weather animation bitmaps based on the weather type.
         * Frames are shipped at 500x500 and decoded at full size; only the even-numbered
         * frames exist in the APK (the odd ones were never drawn), so a set is ~19 frames
         * ≈ 19 MB — well below the ~48 MB that once OOM'd with all frames at full size.
         */
        private fun updateWeatherResources(newType: WeatherType) {
            if (currentWeather == newType) return

            synchronized(rainFrames) { rainFrames.forEach { it.recycle() }; rainFrames.clear() }
            synchronized(snowFrames) { snowFrames.forEach { it.recycle() }; snowFrames.clear() }

            // 1. Load into a temporary list at full resolution
            val tempFrames = ArrayList<Bitmap>()
            val options = BitmapFactory.Options()

            if (newType == WeatherType.RAIN) {
                loadAnimationFrames(tempFrames, "rain_", 38, options)
            } else if (newType == WeatherType.SNOW) {
                loadAnimationFrames(tempFrames, "snow_", 40, options)
            }

            // 2. Safely swap lists and recycle unused bitmaps
            if (newType == WeatherType.RAIN) {
                synchronized(rainFrames) {
                    rainFrames.forEach { it.recycle() }
                    rainFrames.clear()
                    rainFrames.addAll(tempFrames)
                }
                synchronized(snowFrames) { snowFrames.forEach { it.recycle() }; snowFrames.clear() }
            } else if (newType == WeatherType.SNOW) {
                synchronized(snowFrames) {
                    snowFrames.forEach { it.recycle() }
                    snowFrames.clear()
                    snowFrames.addAll(tempFrames)
                }
                synchronized(rainFrames) { rainFrames.forEach { it.recycle() }; rainFrames.clear() }
            } else {
                // CLEAR weather: Release all memory
                synchronized(rainFrames) { rainFrames.forEach { it.recycle() }; rainFrames.clear() }
                synchronized(snowFrames) { snowFrames.forEach { it.recycle() }; snowFrames.clear() }
            }

            currentWeather = newType
            updateInteractiveTick()
        }

        private fun startWeatherUpdater() {
            scope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(WEATHER_REFRESH_MS)
                    fetchRealWeather()
                    withContext(Dispatchers.Main) { invalidate() }
                }
            }
        }

        /**
         * Loads static assets at full resolution. Each 500x500 asset is ~1 MB,
         * total ~6 MB — well below OOM. Originals are kept in *Src fields so
         * updateLayoutAndScale always resamples from the original instead of
         * compounding scale operations.
         */
        private fun loadStaticResources() = synchronized(staticAssetsLock) {
            if (staticAssetsLoaded || destroyed) return@synchronized
            fun decode(resId: Int): Bitmap? = try {
                BitmapFactory.decodeResource(res, resId, null)
            } catch (e: Exception) {
                Log.w(TAG, "decodeResource failed for $resId", e)
                null
            }

            logoBitmapSrc = decode(R.drawable.logo_rewind)
            hourHandBitmapSrc = decode(R.drawable.hand_hour)
            minuteHandBitmapSrc = decode(R.drawable.hand_min)
            centerUnderBitmapSrc = decode(R.drawable.center_under)
            centerOverBitmapSrc = decode(R.drawable.center_over)

            val frameCenterId = res.getIdentifier("frame_center", "drawable", packageName)
                .takeIf { it != 0 }
                ?: res.getIdentifier("frame_left", "drawable", packageName)
            frameCenterSrc = if (frameCenterId != 0) decode(frameCenterId) else null

            // Skies ship as 1260px centre-cropped squares; inSampleSize=2 lands
            // exactly on the 630px (1.4x of 450) the parallax margin needs.
            val skyOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            fun decodeSky(state: SkyState, resId: Int) {
                try {
                    BitmapFactory.decodeResource(res, resId, skyOptions)?.let { skySrc[state] = it }
                } catch (e: Exception) {
                    Log.w(TAG, "sky decode failed for $state", e)
                }
            }
            decodeSky(SkyState.DAWN, R.drawable.bg_sky_dawn)
            decodeSky(SkyState.DAY, R.drawable.bg_sky_blue)
            decodeSky(SkyState.SUNSET, R.drawable.bg_sky_sunset)
            decodeSky(SkyState.NIGHT, R.drawable.bg_sky_night)
            staticAssetsLoaded = true
        }

        /** Swaps the sky bitmap when the solar phase changes. UI thread, no I/O. */
        private fun updateBackgroundForTime() {
            val newState = skyStateAt(Instant.now().epochSecond, solarSchedule)
            if (newState != currentSkyState) {
                currentSkyState = newState
                currentBackgroundBitmap = sky[newState] ?: sky[SkyState.NIGHT]
            }
        }

        /**
         * Center-crops src into a targetSize × targetSize square sized at 1.4×
         * the longer screen edge. Result is always square so drawInteractive's
         * centered placement and parallax offset behave deterministically.
         */
        private fun getScaledBackground(src: Bitmap, width: Int, height: Int): Bitmap {
            val targetSize = (Math.max(width, height) * SKY_SCALE).toInt()
            if (src.width == targetSize && src.height == targetSize) return src

            val scale = Math.max(
                targetSize.toFloat() / src.width,
                targetSize.toFloat() / src.height
            )
            val newW = (src.width * scale).toInt().coerceAtLeast(targetSize)
            val newH = (src.height * scale).toInt().coerceAtLeast(targetSize)
            val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)

            val cropX = (newW - targetSize) / 2
            val cropY = (newH - targetSize) / 2
            val cropped = Bitmap.createBitmap(scaled, cropX, cropY, targetSize, targetSize)
            if (scaled !== cropped && scaled !== src) scaled.recycle()
            return cropped
        }

        private fun loadAnimationFrames(
            targetList: MutableList<Bitmap>,
            prefix: String,
            count: Int,
            opts: BitmapFactory.Options
        ) {
            targetList.clear()
            for (i in 0 until count step 2) {
                val id = res.getIdentifier("$prefix$i", "drawable", packageName)
                if (id != 0) {
                    try {
                        val bmp = BitmapFactory.decodeResource(res, id, opts)
                        if (bmp != null) targetList.add(bmp)
                    } catch (e: OutOfMemoryError) { break }
                }
            }
        }

        private fun ensureScaled(bmp: Bitmap?, w: Int, h: Int): Bitmap? {
            if (bmp == null) return null
            if (bmp.width == w && bmp.height == h) return bmp
            return try {
                Bitmap.createScaledBitmap(bmp, w, h, true)
            } catch (e: Exception) { bmp }
        }

        private fun updateLayoutAndScale(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            // First frame before the IO preload finished (or a headless snapshot
            // instance): decode synchronously now (~100ms, once) rather than
            // draw a face with no hands/frame.
            if (!staticAssetsLoaded) loadStaticResources()
            if (width == currentWidth && height == currentHeight) {
                updateBackgroundForTime()
                return
            }

            currentWidth = width
            currentHeight = height
            screenCenterX = width / 2f
            screenCenterY = height / 2f

            // Skies: always rescale from the decoded original so a bounds change
            // never resamples an already-resampled bitmap.
            for ((state, src) in skySrc) {
                val old = sky[state]
                val scaled = getScaledBackground(src, width, height)
                sky[state] = scaled
                if (old != null && old !== scaled && old !== src) old.recycle()
            }
            currentSkyState = SkyState.UNKNOWN   // force the swap below to pick the new bitmap
            updateBackgroundForTime()

            // Always rescale from the *Src original — never from a previously
            // scaled result — so repeated bounds changes don't compound losses.
            logoBitmapSrc?.let { src ->
                val targetWidth = (width * LOGO_WIDTH_RATIO).toInt()
                val targetHeight = (src.height * (targetWidth.toFloat() / src.width)).toInt()
                val old = logoBitmap
                logoBitmap = ensureScaled(src, targetWidth, targetHeight)
                if (old != null && old !== logoBitmap && old !== src) old.recycle()
            }
            hourHandBitmapSrc?.let { src ->
                val old = hourHandBitmap
                hourHandBitmap = ensureScaled(src, width, height)
                if (old != null && old !== hourHandBitmap && old !== src) old.recycle()
            }
            minuteHandBitmapSrc?.let { src ->
                val old = minuteHandBitmap
                minuteHandBitmap = ensureScaled(src, width, height)
                if (old != null && old !== minuteHandBitmap && old !== src) old.recycle()
            }
            centerUnderBitmapSrc?.let { src ->
                val old = centerUnderBitmap
                centerUnderBitmap = ensureScaled(src, width, height)
                if (old != null && old !== centerUnderBitmap && old !== src) old.recycle()
            }
            centerOverBitmapSrc?.let { src ->
                val old = centerOverBitmap
                centerOverBitmap = ensureScaled(src, width, height)
                if (old != null && old !== centerOverBitmap && old !== src) old.recycle()
            }
            frameCenterSrc?.let { src ->
                val old = frameCenter
                frameCenter = ensureScaled(src, width, height)
                if (old != null && old !== frameCenter && old !== src) old.recycle()
            }

            // Vignette Shader
            val radius = Math.max(width, height) / 2f
            vignettePaint.shader = RadialGradient(
                screenCenterX,
                screenCenterY,
                radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0.0f, 0.8f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }

        override fun renderHighlightLayer(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: MySharedAssets
        ) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        override fun render(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: MySharedAssets
        ) {
            updateLayoutAndScale(bounds.width(), bounds.height())
            canvas.drawColor(Color.BLACK)

            val now = ZonedDateTime.now(java.time.ZoneId.systemDefault())
            try {
                if (renderParameters.drawMode == DrawMode.AMBIENT) {
                    drawAmbient(canvas, now)
                } else {
                    drawInteractive(canvas, now)
                }
            } catch (e: Exception) {
                Log.w(TAG, "render failed", e)
            }
        }

        private fun drawInteractive(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            val diffX = (gyroX - baseX).coerceIn(-TILT_LIMIT, TILT_LIMIT)
            val diffY = (gyroY - baseY).coerceIn(-TILT_LIMIT, TILT_LIMIT)

            // 1. Draw Background (Parallax Effect)
            val bg = currentBackgroundBitmap
            if (bg != null && !bg.isRecycled) {
                val extraWidth = bg.width - currentWidth
                val extraHeight = bg.height - currentHeight
                val drawX = -extraWidth / 2f + (diffX * DEPTH_SKY)
                val drawY = -extraHeight / 2f + (diffY * DEPTH_SKY)
                canvas.drawBitmap(bg, drawX, drawY, null)
            }

            // 2. Draw Weather Animation
            // Optimization: Scale the small bitmap to fit screen size at draw time
            weatherDestRect.set(0, 0, currentWidth, currentHeight)
            if (showWeatherAnimation) {
                val currentTime = System.currentTimeMillis()

                if (currentWeather == WeatherType.RAIN) {
                    synchronized(rainFrames) {
                        if (rainFrames.isNotEmpty()) {
                            val frameIndex = ((currentTime / FRAME_DURATION_MS) % rainFrames.size).toInt()
                            if (frameIndex < rainFrames.size) {
                                canvas.drawBitmap(rainFrames[frameIndex], null, weatherDestRect, rainPaint)
                            }
                        }
                    }
                } else if (currentWeather == WeatherType.SNOW) {
                    synchronized(snowFrames) {
                        if (snowFrames.isNotEmpty()) {
                            val frameIndex = ((currentTime / FRAME_DURATION_MS) % snowFrames.size).toInt()
                            if (frameIndex < snowFrames.size) {
                                canvas.drawBitmap(snowFrames[frameIndex], null, weatherDestRect, snowPaint)
                            }
                        }
                    }
                }
            }

            // 3. Logo
            logoBitmap?.let {
                drawBitmapAt(canvas, it, diffX * DEPTH_LOGO, diffY * DEPTH_LOGO, logoPaint)
            }

            // 4. Shadow
            frameCenter?.let {
                drawBitmapAt(canvas, it, -diffX * DEPTH_SHADOW, -diffY * DEPTH_SHADOW, shadowPaint)
            }

            val handPaint = when (currentSkyState) {
                SkyState.NIGHT -> {
                    nightPaint
                }
                SkyState.DAWN -> {
                    lightPaint
                }
                else -> {
                    brightPaint
                }
            }

            // 5. Main Frame
            frameCenter?.let {
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }

            // 6. Hands
            val hours = zonedDateTime.hour + zonedDateTime.minute / 60f
            val minutes = zonedDateTime.minute + zonedDateTime.second / 60f

            canvas.save()
            canvas.rotate(minutes * 6f, screenCenterX, screenCenterY)
            minuteHandBitmap?.let {
                drawBitmapAt(canvas, it, HAND_SHADOW_OFFSET, HAND_SHADOW_OFFSET, shadowPaint)
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }
            canvas.restore()

            canvas.save()
            canvas.rotate((hours % 12) * 30f, screenCenterX, screenCenterY)
            hourHandBitmap?.let {
                drawBitmapAt(canvas, it, HAND_SHADOW_OFFSET, HAND_SHADOW_OFFSET, shadowPaint)
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }
            canvas.restore()

            centerUnderBitmap?.let {
                drawBitmapAt(canvas, it, CENTER_CAP_SHADOW_OFFSET, CENTER_CAP_SHADOW_OFFSET, shadowPaint)
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }

            centerOverBitmap?.let {
                drawBitmapAt(canvas, it, CENTER_CAP_SHADOW_OFFSET, CENTER_CAP_SHADOW_OFFSET, shadowPaint)
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }

            // 7. Info Text
            drawDigitalInfo(canvas, zonedDateTime)
            if (showDate) {
                drawDateInfo(canvas, zonedDateTime, false)
            }

            // 8. Vignette
            if (currentWidth > 0) {
                canvas.drawCircle(screenCenterX, screenCenterY, currentWidth / 2f, vignettePaint)
            }
        }

        private fun drawDigitalInfo(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            digitalTextPaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.WHITE)
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            if (showTime) {
                digitalTextPaint.typeface = getTypefaceFor(timeWeightId)
                digitalTextPaint.textSize = currentWidth * TIME_TEXT_SIZE
                val formatter = if (showSeconds) timeFormatterSeconds else timeFormatterNoSeconds
                val timeText = zonedDateTime.format(formatter)
                canvas.drawText(
                    timeText,
                    screenCenterX,
                    screenCenterY - (currentWidth * TIME_Y_ABOVE_CENTER),
                    digitalTextPaint
                )
            }

            if (showBattery) {
                digitalTextPaint.typeface = getTypefaceFor(batteryWeightId)

                val batteryWidth = currentWidth * BATTERY_ICON_WIDTH
                val batteryY = if (showDate) {
                    screenCenterY + (currentWidth * BATTERY_Y_WITH_DATE)
                } else {
                    screenCenterY + (currentWidth * BATTERY_Y_ALONE)
                }
                drawBatteryBar(canvas, screenCenterX, batteryY, batteryWidth)
            }
        }

        private fun drawDateInfo(canvas: Canvas, zonedDateTime: ZonedDateTime, isAmbient: Boolean) {
            dateTextPaint.apply {
                if (isAmbient) {
                    color = Color.LTGRAY
                    alpha = 100
                    clearShadowLayer()
                } else {
                    color = Color.BLACK
                    if (currentSkyState == SkyState.NIGHT) {
                        setShadowLayer(18f, 0f, 0f, Color.WHITE)
                        alpha = 225
                    } else {
                        clearShadowLayer()
                        alpha = 180
                    }
                }
                typeface = getTypefaceFor(dateWeightId)
                textSize = currentWidth * DATE_TEXT_SIZE
            }
            val dateText = zonedDateTime.format(dateFormatter)
            val dateY = if (isAmbient || !showBattery) {
                screenCenterY + (currentWidth * DATE_Y_ALONE)
            } else {
                screenCenterY + (currentWidth * DATE_Y_WITH_BATTERY)
            }
            canvas.drawText(dateText, screenCenterX, dateY, dateTextPaint)
        }

        private fun drawBatteryBar(canvas: Canvas, cx: Float, cy: Float, width: Float) {
            val bWidth = width * 0.9f
            val bHeight = width * 1.3f
            val batteryText = "$batteryLevel%"

            batteryFillPaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.WHITE)
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            batteryStrokePaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.WHITE)
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            digitalTextPaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.WHITE)
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
                textSize = width + 2
            }

            val gap = 4f
            val totalWidth = bWidth + gap + digitalTextPaint.measureText(batteryText)
            val startX = cx - (totalWidth / 3f)
            val iconCx = startX + (bWidth / 5f)

            val rect = RectF(
                iconCx - bWidth / 2f,
                cy - bHeight / 2f,
                iconCx + bWidth / 2f,
                cy + bHeight / 2f
            )
            canvas.drawRoundRect(rect, 3f, 3f, batteryStrokePaint)

            val capWidth = bWidth * 0.5f
            val capHeight = bHeight * 0.15f
            val capRect = RectF(
                iconCx - capWidth / 2f,
                rect.top - capHeight,
                iconCx + capWidth / 2f,
                rect.top
            )
            canvas.drawRect(capRect, batteryFillPaint)

            if (batteryLevel > 0) {
                val padding = 3f
                val fillH = (bHeight - padding * 2) * (batteryLevel / 100f)
                val fillRect = RectF(
                    rect.left + padding,
                    rect.bottom - padding - fillH,
                    rect.right - padding,
                    rect.bottom - padding
                )
                canvas.drawRect(fillRect, batteryFillPaint)
            }

            digitalTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                batteryText,
                startX + bWidth + gap - 2,
                cy + (digitalTextPaint.textSize / 3),
                digitalTextPaint
            )
            digitalTextPaint.textAlign = Paint.Align.CENTER
        }

        private fun drawAmbient(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            // OLED burn-in defense: nudge the whole frame by 0-1 px on a 4-min
            // cycle so the same pixels don't stay lit hours at a time.
            // (0,0) → (1,0) → (1,1) → (0,1) → repeat
            val cycle = zonedDateTime.minute % 4
            val shiftX = if (cycle == 1 || cycle == 2) 1f else 0f
            val shiftY = if (cycle == 2 || cycle == 3) 1f else 0f
            canvas.save()
            canvas.translate(shiftX, shiftY)

            frameCenter?.let { drawBitmapAt(canvas, it, 0f, 0f, grayScalePaint) }
            val hours = zonedDateTime.hour + zonedDateTime.minute / 60f
            val minutes = zonedDateTime.minute.toFloat()

            canvas.save()
            canvas.rotate(minutes * 6f, screenCenterX, screenCenterY)
            minuteHandBitmap?.let { drawBitmapAt(canvas, it, 0f, 0f, grayScalePaint) }
            canvas.restore()

            canvas.save()
            canvas.rotate((hours % 12) * 30f, screenCenterX, screenCenterY)
            hourHandBitmap?.let { drawBitmapAt(canvas, it, 0f, 0f, grayScalePaint) }
            canvas.restore()

            centerUnderBitmap?.let { drawBitmapAt(canvas, it, 0f, 0f, grayScalePaint)}
            centerOverBitmap?.let { drawBitmapAt(canvas, it, 0f, 0f, grayScalePaint)}

            if (showDate) drawDateInfo(canvas, zonedDateTime, true)

            canvas.restore()
        }

        private fun drawBitmapAt(
            canvas: Canvas,
            bitmap: Bitmap,
            offsetX: Float,
            offsetY: Float,
            paint: Paint? = null
        ) {
            val x = screenCenterX - (bitmap.width / 2f) + offsetX
            val y = screenCenterY - (bitmap.height / 2f) + offsetY
            canvas.drawBitmap(bitmap, x, y, paint)
        }

        override fun onRenderParametersChanged(renderParameters: RenderParameters) {
            super.onRenderParametersChanged(renderParameters)
            isInteractiveDrawMode = renderParameters.drawMode == DrawMode.INTERACTIVE
            syncSensorState()
        }

        private fun syncSensorState() {
            val shouldRun = !isHeadless && isFaceVisible && !isAmbientNow && isInteractiveDrawMode
            if (shouldRun && !isSensorRegistered) {
                needsReset = true
                // 50Hz keeps the parallax fluid; the sensor is only live while
                // the face is actually on screen, so this costs little.
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                isSensorRegistered = true
            } else if (!shouldRun && isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            if (needsReset) {
                baseX = x
                baseY = y
                needsReset = false
            }
            gyroX = gyroX * (1f - SENSOR_SMOOTHING) + x * SENSOR_SMOOTHING
            gyroY = gyroY * (1f - SENSOR_SMOOTHING) + y * SENSOR_SMOOTHING

            // Only repaint when tilt meaningfully changed and at most ~30Hz.
            // Eliminates the previous ~50Hz invalidate flood when the wrist
            // was sitting still (sensor still fires but values barely move).
            val dx = Math.abs(gyroX - lastInvalidatedGyroX)
            val dy = Math.abs(gyroY - lastInvalidatedGyroY)
            val now = System.currentTimeMillis()
            if ((dx > REDRAW_TILT_THRESHOLD || dy > REDRAW_TILT_THRESHOLD) &&
                now - lastSensorInvalidateAt > REDRAW_MIN_INTERVAL_MS
            ) {
                lastInvalidatedGyroX = gyroX
                lastInvalidatedGyroY = gyroY
                lastSensorInvalidateAt = now
                invalidate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onDestroy() {
            super.onDestroy()
            sensorManager.unregisterListener(this)
            try { context.unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
            if (isRefreshReceiverRegistered) {
                try { context.unregisterReceiver(refreshWeatherReceiver) } catch (e: Exception) {}
            }
            scope.cancel()

            // Resource cleanup. Taking staticAssetsLock means a decode that is
            // still running on IO finishes (and its results are assigned) before
            // we recycle, and any load that hasn't started yet sees `destroyed`.
            // recycle() is idempotent, so sky/skySrc sharing an instance is fine.
            synchronized(staticAssetsLock) {
                destroyed = true
                currentBackgroundBitmap = null
                sky.values.forEach { it.recycle() }
                skySrc.values.forEach { it.recycle() }
                synchronized(rainFrames) { rainFrames.forEach { it.recycle() } }
                synchronized(snowFrames) { snowFrames.forEach { it.recycle() } }
                listOf(
                    logoBitmap, frameCenter, hourHandBitmap, minuteHandBitmap,
                    centerUnderBitmap, centerOverBitmap,
                    logoBitmapSrc, frameCenterSrc, hourHandBitmapSrc, minuteHandBitmapSrc,
                    centerUnderBitmapSrc, centerOverBitmapSrc
                ).forEach { it?.recycle() }
            }
        }
    }
}