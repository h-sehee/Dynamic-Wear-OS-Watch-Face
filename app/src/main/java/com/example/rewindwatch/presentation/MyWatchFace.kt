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

class MyWatchFace : WatchFaceService() {

    companion object {
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

        // Animation Configuration
        private const val FRAME_DURATION_MS = 180L
        private const val LOGO_SCALE_FACTOR = 0.6f

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
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), resources, R.string.font_style_sans, null), // Sans (Basic)
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), resources, R.string.font_style_serif, null), // Serif
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), resources, R.string.font_style_mono, null), // Mono
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), resources, R.string.font_style_condensed, null), // Condensed
            ),
            listOf(WatchFaceLayer.BASE),
            UserStyleSetting.ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id("0"), resources, R.string.font_style_sans, null
            )
        )

        // 7. Font Weight List Setting
        fun createWeightSetting(id: String, defaultId: String): UserStyleSetting.ListUserStyleSetting {
            val options = listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), resources, R.string.font_weight_normal, null), // Normal
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), resources, R.string.font_weight_medium, null), // Medium
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), resources, R.string.font_weight_bold, null), // Bold
                UserStyleSetting.ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), resources, R.string.font_weight_extrabold, null)  // ExtraBold
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
        16L,
        clearWithBackgroundTintBeforeRenderingHighlightLayer = true
    ), SensorEventListener {

        // --- Hardware & System Managers ---
        private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private lateinit var fusedLocationClient: FusedLocationProviderClient
        private val bitmapLock = Any()
        private var lastStyleUpdateTime = 0L

        // --- Sensor State ---
        private var gyroX = 0f
        private var gyroY = 0f
        private var baseX = 0f
        private var baseY = 0f
        private var needsReset = true
        private var isSensorRegistered = false

        // --- Battery State ---
        private var batteryLevel = 100
        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevel = (level * 100 / scale.toFloat()).toInt()
            }
        }

        // --- Graphic Resources ---
        // Memory Optimization: Keep only the current background bitmap
        @Volatile private var currentBackgroundBitmap: Bitmap? = null
        private var currentSkyState: SkyState = SkyState.UNKNOWN

        // Weather Animation Frames (Loaded on demand)
        private val rainFrames = Collections.synchronizedList(ArrayList<Bitmap>())
        private val snowFrames = Collections.synchronizedList(ArrayList<Bitmap>())

        // Static Assets
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
            val scale = 0.6f
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
        private var currentWeather = WeatherType.CLEAR
        private var solarSchedule = SolarSchedule(
            sunrise = System.currentTimeMillis() / 1000 - 3600 * 4,
            sunset = System.currentTimeMillis() / 1000 + 3600 * 8
        )

        // Utils
        private val scope = CoroutineScope(Dispatchers.Main)
        private val res = context.resources
        private val timeFormatterSeconds = DateTimeFormatter.ofPattern("hh:mm:ss")
        private val timeFormatterNoSeconds = DateTimeFormatter.ofPattern("hh:mm")
        private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH)

        override suspend fun createSharedAssets(): MySharedAssets = MySharedAssets()

        private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "time" -> showTime = prefs.getBoolean(SETTING_TIME_ID, true)
                "seconds" -> showSeconds = prefs.getBoolean(SETTING_SECONDS_ID, true)
                "date" -> showDate = prefs.getBoolean(SETTING_DATE_ID, true)
                "battery" -> showBattery = prefs.getBoolean(SETTING_BATTERY_ID, true)
                "weather_anim" -> showWeatherAnimation = prefs.getBoolean(SETTING_WEATHER_ANIM_ID, true)
            }
            invalidate()
        }

        init {
            // Start async initialization
            scope.launch(Dispatchers.IO) {
                try {
                    loadStaticResources()
                    fetchRealWeather()
                    withContext(Dispatchers.Main.immediate) { invalidate() }
                } catch (e: Exception) {
                    e.printStackTrace()
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

            // Initialize Preferences
            val prefs = context.getSharedPreferences("MyWatchPrefs", Context.MODE_PRIVATE)
            showTime = prefs.getBoolean(SETTING_TIME_ID, true)
            showSeconds = prefs.getBoolean(SETTING_SECONDS_ID, true)
            showDate = prefs.getBoolean(SETTING_DATE_ID, true)
            showBattery = prefs.getBoolean(SETTING_BATTERY_ID, true)
            showWeatherAnimation = prefs.getBoolean(SETTING_WEATHER_ANIM_ID, true)

            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            startWeatherUpdater()
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

            lastStyleUpdateTime = System.currentTimeMillis()
            invalidate()
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
        private fun fetchRealWeather() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&

                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                fetchWeatherByCity("Seoul")
                return
            }

            // 1. Check last known location (Battery Saver)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    fetchWeatherByCoord(location.latitude, location.longitude)
                } else {
                    // 2. Request active location if cache is empty
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { currentLocation ->
                            if (currentLocation != null) {
                                fetchWeatherByCoord(currentLocation.latitude, currentLocation.longitude)
                            } else {
                                fetchWeatherByCity("Seoul")
                            }
                        }
                        .addOnFailureListener { fetchWeatherByCity("Seoul") }
                }
            }.addOnFailureListener { fetchWeatherByCity("Seoul") }
        }

        private fun fetchWeatherByCoord(lat: Double, lon: Double) {
            scope.launch(Dispatchers.IO) {
                try {
                    val urlString = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lon&appid=$API_KEY"
                    parseAndApplyWeather(urlString)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        private fun fetchWeatherByCity(city: String) {
            scope.launch(Dispatchers.IO) {
                try {
                    val urlString = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$API_KEY"
                    parseAndApplyWeather(urlString)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        /**
         * Parses JSON from OpenWeatherMap API.
         * Configured with timeouts to handle Wear OS Bluetooth proxy delays.
         */
        private suspend fun parseAndApplyWeather(urlString: String) {
            try {
                val url = URL(urlString)
                val connection = withContext(Dispatchers.IO) {
                    url.openConnection() as HttpURLConnection
                }

                // Timeout settings for Wear OS connectivity
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == 200) {
                    val stream = connection.inputStream
                    val jsonText = stream.bufferedReader().use { it.readText() }

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

                    updateWeatherResources(newWeather)
                    withContext(Dispatchers.Main) { invalidate() }
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Log exception implicitly via debugger if needed, removed println
            }
        }

        /**
         * Efficiently loads or unloads weather animation bitmaps based on the weather type.
         * Uses inSampleSize = 2 to prevent OutOfMemory errors with high-res assets.
         */
        private fun updateWeatherResources(newType: WeatherType) {
            if (currentWeather == newType) return

            synchronized(rainFrames) { rainFrames.forEach { it.recycle() }; rainFrames.clear() }
            synchronized(snowFrames) { snowFrames.forEach { it.recycle() }; snowFrames.clear() }
            System.gc()

            // 1. Load into temporary list with downsampling (1/2 size)
            val tempFrames = ArrayList<Bitmap>()
            val options = BitmapFactory.Options().apply { inSampleSize = 2 } // CRITICAL: Fix for 48MB allocation error

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
                System.gc() // Request garbage collection
            }

            currentWeather = newType
        }

        private fun startWeatherUpdater() {
            scope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(30 * 60 * 1000L) // Update every 30 mins
                    fetchRealWeather()
                    withContext(Dispatchers.Main) { invalidate() }
                }
            }
        }

        /**
         * Loads static assets like hands and logos.
         * Uses inSampleSize = 2 to optimize memory usage for large assets.
         */
        private fun loadStaticResources() {
            val optionsAlpha = BitmapFactory.Options().apply { inSampleSize = 2 }
            logoBitmap = try {
                BitmapFactory.decodeResource(res, R.drawable.logo_rewind, optionsAlpha)
            } catch (e: Exception) {
                null
            }
            hourHandBitmap = try {
                BitmapFactory.decodeResource(res, R.drawable.hand_hour, optionsAlpha)
            } catch (e: Exception) {
                null
            }
            minuteHandBitmap = try {
                BitmapFactory.decodeResource(res, R.drawable.hand_min, optionsAlpha)
            } catch (e: Exception) {
                null
            }
            centerUnderBitmap = try {
                BitmapFactory.decodeResource(res, R.drawable.center_under, optionsAlpha)
            } catch (e: Exception) {
                null
            }
            centerOverBitmap = try {
                BitmapFactory.decodeResource(res, R.drawable.center_over, optionsAlpha)
            } catch (e: Exception) {
                null
            }
            fun loadFrameBitmap(targetName: String, fallbackName: String): Bitmap? {
                val resId =
                    res.getIdentifier(targetName, "drawable", packageName).takeIf { it != 0 }

                        ?: res.getIdentifier(fallbackName, "drawable", packageName)
                return if (resId != 0) {
                    try {
                        BitmapFactory.decodeResource(res, resId, optionsAlpha)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }

            frameCenter = loadFrameBitmap("frame_center", "frame_left")
                    }

        /**
         * Updates the background image based on the time of day.
         * Runs on IO thread to prevent UI stutter.
         */
        private fun updateBackgroundForTime() {
            val now = Instant.now().epochSecond
            val buffer = 1800L

            val newState = when {
                now in (solarSchedule.sunrise - buffer)..(solarSchedule.sunrise + buffer) -> SkyState.DAWN
                now in (solarSchedule.sunrise + buffer + 1)..(solarSchedule.sunset - buffer) -> SkyState.DAY
                now in (solarSchedule.sunset - buffer)..(solarSchedule.sunset + buffer) -> SkyState.SUNSET
                else -> SkyState.NIGHT
            }

            if (newState != currentSkyState) {
                val resId = when (newState) {
                    SkyState.DAWN -> R.drawable.bg_sky_dawn
                    SkyState.DAY -> R.drawable.bg_sky_blue
                    SkyState.SUNSET -> R.drawable.bg_sky_sunset
                    SkyState.NIGHT -> R.drawable.bg_sky_night
                    else -> R.drawable.bg_sky_night
                }

                // CRITICAL: Downsample background to 1/2 size
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565;
                    inSampleSize = 2
                }

                var newBitmap: Bitmap? = try {
                    BitmapFactory.decodeResource(res, resId, options)
                } catch (e: Exception) { null }

                // 스케일링 필요 시 처리
                if (newBitmap != null && currentWidth > 0 && currentHeight > 0) {
                    val scaled = getScaledBackground(newBitmap, currentWidth, currentHeight)
                    if (scaled != newBitmap) {
                        newBitmap.recycle()
                        newBitmap = scaled
                    }
                }

                synchronized(bitmapLock) {
                    val oldBitmap = currentBackgroundBitmap

                    currentSkyState = newState
                    currentBackgroundBitmap = newBitmap

                    if (oldBitmap != null && oldBitmap != newBitmap) {
                        oldBitmap.recycle()
                    }
                }
            }
        }

        private fun getScaledBackground(src: Bitmap, width: Int, height: Int): Bitmap {
            val scaleFactor = 1.4f
            val targetSize = (Math.max(width, height) * scaleFactor).toInt()
            if (src.width == targetSize && src.height == targetSize) return src

            val scale = Math.max(targetSize.toFloat() / src.width, targetSize.toFloat() / src.height)
            val newW = (src.width * scale).toInt()
            val newH = (src.height * scale).toInt()
            return Bitmap.createScaledBitmap(src, newW, newH, true)
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
            if (width == currentWidth && height == currentHeight) {
                updateBackgroundForTime()
                return
            }

            currentWidth = width
            currentHeight = height
            screenCenterX = width / 2f
            screenCenterY = height / 2f

            updateBackgroundForTime()

            currentBackgroundBitmap?.let {
                val targetSize = (Math.max(width, height) * 1.4f).toInt()
                if (Math.abs(it.width - targetSize) > 50) {
                    val old = it
                    currentBackgroundBitmap = getScaledBackground(old, width, height)
                    if (old != currentBackgroundBitmap) old.recycle()
                }
            }

            logoBitmap?.let {
                val targetWidth = (width * LOGO_SCALE_FACTOR).toInt()
                val targetHeight = (it.height * (targetWidth.toFloat() / it.width)).toInt()
                logoBitmap = ensureScaled(it, targetWidth, targetHeight)
            }
            hourHandBitmap?.let { hourHandBitmap = ensureScaled(it, width, height) }
            minuteHandBitmap?.let { minuteHandBitmap = ensureScaled(it, width, height) }
            centerUnderBitmap?.let { centerUnderBitmap = ensureScaled(it, width, height)}
            centerOverBitmap?.let { centerOverBitmap = ensureScaled(it, width, height)}
            frameCenter?.let { frameCenter = ensureScaled(it, width, height) }

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
            val myId = System.identityHashCode(this)
            updateLayoutAndScale(bounds.width(), bounds.height())
            canvas.drawColor(Color.BLACK)

            val now = ZonedDateTime.now(java.time.ZoneId.systemDefault())
            try {
                if (renderParameters.drawMode == DrawMode.AMBIENT) {
                    drawAmbient(canvas, now)
                } else {
                    drawInteractive(canvas, now)
                }
            } catch (e: Exception) { e.printStackTrace() }
            finally { if (renderParameters.drawMode == DrawMode.INTERACTIVE) invalidate() }
        }

        private fun drawInteractive(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            val TILT_LIMIT = 2.5f
            val DEPTH_SKY = 20f
            val DEPTH_LOGO = 3f
            val DEPTH_SHADOW = 5f
            val HAND_SHADOW_OFFSET = 6f
            val diffX = (gyroX - baseX).coerceIn(-TILT_LIMIT, TILT_LIMIT)
            val diffY = (gyroY - baseY).coerceIn(-TILT_LIMIT, TILT_LIMIT)

            // 1. Draw Background (Parallax Effect)
            synchronized(bitmapLock) {
                val bg = currentBackgroundBitmap

                if (bg != null && !bg.isRecycled) {
                    val extraWidth = bg.width - currentWidth
                    val extraHeight = bg.height - currentHeight
                    val baseXPos = -extraWidth / 2f
                    val baseYPos = -extraHeight / 2f
                    val drawX = baseXPos + (diffX * DEPTH_SKY)
                    val drawY = baseYPos + (diffY * DEPTH_SKY)

                    canvas.drawBitmap(bg, drawX, drawY, null)
                }
            }

            // 2. Draw Weather Animation
            // Optimization: Scale the small bitmap to fit screen size at draw time
            val destRect = Rect(0, 0, currentWidth, currentHeight)
            if (showWeatherAnimation) {
                val currentTime = System.currentTimeMillis()

                if (currentWeather == WeatherType.RAIN) {
                    synchronized(rainFrames) {
                        if (rainFrames.isNotEmpty()) {
                            val frameIndex = ((currentTime / FRAME_DURATION_MS) % rainFrames.size).toInt()
                            if (frameIndex < rainFrames.size) {
                                canvas.drawBitmap(rainFrames[frameIndex], null, destRect, rainPaint)
                            }
                        }
                    }
                } else if (currentWeather == WeatherType.SNOW) {
                    synchronized(snowFrames) {
                        if (snowFrames.isNotEmpty()) {
                            val frameIndex = ((currentTime / FRAME_DURATION_MS) % snowFrames.size).toInt()
                            if (frameIndex < snowFrames.size) {
                                canvas.drawBitmap(snowFrames[frameIndex], null, destRect, snowPaint)
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
                drawBitmapAt(canvas, it, 4f, 4f, shadowPaint)
                drawBitmapAt(canvas, it, 0f, 0f, handPaint)
            }

            centerOverBitmap?.let {
                drawBitmapAt(canvas, it, 4f, 4f, shadowPaint)
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
                    setShadowLayer(18f, 0f, 0f, Color.parseColor("#FFFFFF"))
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            if (showTime) {
                digitalTextPaint.typeface = getTypefaceFor(timeWeightId)
                digitalTextPaint.textSize = currentWidth * 0.079f
                val formatter = if (showSeconds) timeFormatterSeconds else timeFormatterNoSeconds
                val timeText = zonedDateTime.format(formatter)
                canvas.drawText(
                    timeText,
                    screenCenterX,
                    screenCenterY - (currentWidth * 0.05f),
                    digitalTextPaint
                )
            }

            if (showBattery) {
                digitalTextPaint.typeface = getTypefaceFor(batteryWeightId)

                val batteryWidth = currentWidth * 0.04f
                val batteryY = if (showDate) {
                    screenCenterY + (currentWidth * 0.13f)
                } else {
                    screenCenterY + (currentWidth * 0.11f)
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
                        setShadowLayer(18f, 0f, 0f, Color.parseColor("#FFFFFF"))
                        alpha = 225
                    } else {
                        clearShadowLayer()
                        alpha = 180
                    }
                }
                typeface = getTypefaceFor(dateWeightId)
                textSize = currentWidth * 0.05f
            }
            val dateText = zonedDateTime.format(dateFormatter)
            val dateY = if (isAmbient) {
                screenCenterY + (currentWidth * 0.11f)
            } else if (!showBattery) {
                screenCenterY + (currentWidth * 0.11f)
            } else {
                screenCenterY + (currentWidth * 0.08f)
            }
            canvas.drawText(dateText, screenCenterX, dateY, dateTextPaint)
        }

        private fun drawBatteryBar(canvas: Canvas, cx: Float, cy: Float, width: Float) {
            val bWidth = width * 0.9f
            val bHeight = width * 1.3f
            val batteryText = "$batteryLevel%"

            batteryFillPaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.parseColor("#FFFFFF"))
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            batteryStrokePaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.parseColor("#FFFFFF"))
                    alpha = 225
                } else {
                    clearShadowLayer()
                    alpha = 180
                }
            }
            digitalTextPaint.apply {
                if (currentSkyState == SkyState.NIGHT) {
                    setShadowLayer(18f, 0f, 0f, Color.parseColor("#FFFFFF"))
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
            updateSensorState(renderParameters.drawMode)
        }

        private fun updateSensorState(drawMode: DrawMode) {
            if (drawMode == DrawMode.INTERACTIVE) {
                if (!isSensorRegistered) {
                    needsReset = true
                    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                    isSensorRegistered = true
                }
            } else {
                if (isSensorRegistered) {
                    sensorManager.unregisterListener(this)
                    isSensorRegistered = false
                }
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                if (needsReset) {
                    baseX = x
                    baseY = y
                    needsReset = false
                }
                gyroX = gyroX * 0.9f + x * 0.1f
                gyroY = gyroY * 0.9f + y * 0.1f
                invalidate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onDestroy() {
            super.onDestroy()
            sensorManager.unregisterListener(this)
            try { context.unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
            scope.cancel()

            // Resource Cleanup to prevent memory leaks
            currentBackgroundBitmap?.recycle()
            synchronized(rainFrames) { rainFrames.forEach { it.recycle() } }
            synchronized(snowFrames) { snowFrames.forEach { it.recycle() } }
            logoBitmap?.recycle()
            frameCenter?.recycle()
            hourHandBitmap?.recycle()
            minuteHandBitmap?.recycle()
        }
    }
}