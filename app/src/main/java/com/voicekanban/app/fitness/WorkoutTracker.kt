package com.voicekanban.app.fitness

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.WorkoutSession
import com.voicekanban.app.data.WorkoutType
import com.voicekanban.app.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything shown on the live workout screen. */
data class LiveWorkout(
    val type: WorkoutType,
    val startedAt: Long,
    /** Active time before the current run segment (pauses excluded). */
    val accumulatedMs: Long = 0,
    /** elapsedRealtime when the current segment started; null while paused. */
    val resumedAt: Long? = SystemClock.elapsedRealtime(),
    val distanceM: Double = 0.0,
    /** Current speed in m/s from GPS. */
    val speedMps: Float = 0f,
    val steps: Int = 0,
    val gpsFix: Boolean = false,
    val gpsAvailable: Boolean = false,
    val stepsAvailable: Boolean = false,
    /** Recent speeds (km/h) for the live sparkline. */
    val speedHistory: List<Float> = emptyList(),
) {
    val running: Boolean get() = resumedAt != null
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long = accumulatedMs + (resumedAt?.let { now - it } ?: 0)
    fun calories(weightKg: Double, now: Long = SystemClock.elapsedRealtime()): Double = type.met * weightKg * (elapsedMs(now) / 3_600_000.0)
    val avgSpeedKmh: Double get() { val h = elapsedMs() / 3_600_000.0; return if (h > 0) distanceM / 1000.0 / h else 0.0 }
}

/**
 * Runs the active workout: a pause-aware timer, GPS distance and speed,
 * and the step counter. Keeps working with the screen off thanks to
 * [WorkoutService] (a foreground service with a live notification).
 */
object WorkoutTracker {
    private const val TAG = "WorkoutTracker"
    private val _live = MutableStateFlow<LiveWorkout?>(null)
    val live: StateFlow<LiveWorkout?> = _live.asStateFlow()

    private var app: Context? = null
    private var lastLocation: Location? = null
    private var stepBase = -1f
    private var stepsBeforePause = 0
    private var tickJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val locationListener = LocationListener { loc -> onLocation(loc) }
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val total = e.values.firstOrNull() ?: return
            val w = _live.value ?: return
            if (!w.running) { stepBase = -1f; return }
            if (stepBase < 0) stepBase = total
            _live.value = w.copy(steps = stepsBeforePause + (total - stepBase).toInt())
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun hasLocation(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasActivity(c: Context) = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context, type: WorkoutType) {
        if (_live.value != null) return
        val c = context.applicationContext
        app = c
        lastLocation = null; stepBase = -1f; stepsBeforePause = 0
        val gps = type.gps && hasLocation(c)
        val stepSensor = if (type.steps && hasActivity(c)) c.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) else null
        _live.value = LiveWorkout(type, System.currentTimeMillis(), gpsAvailable = gps, stepsAvailable = stepSensor != null)
        if (gps) requestLocation(c)
        stepSensor?.let { c.getSystemService(SensorManager::class.java)?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_UI) }
        WorkoutService.start(c, gps, stepSensor != null)
        tickJob = scope.launch { while (isActive) { WorkoutService.update(c); delay(1000) } }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation(c: Context) {
        runCatching {
            val lm = c.getSystemService(LocationManager::class.java) ?: return
            val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.requestLocationUpdates(provider, 2000L, 2f, locationListener, Looper.getMainLooper())
        }.onFailure { Log.w(TAG, "No location updates", it) }
    }

    private fun onLocation(loc: Location) {
        val w = _live.value ?: return
        if (loc.hasAccuracy() && loc.accuracy > 30f) { _live.value = w.copy(gpsFix = false); return }
        var dist = w.distanceM
        val prev = lastLocation
        if (w.running && prev != null) {
            val d = prev.distanceTo(loc)
            // Ignore GPS jitter while standing still and impossible jumps.
            if (d > 2.5f && d < 200f) dist += d
        }
        lastLocation = loc
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        _live.value = w.copy(
            distanceM = dist, speedMps = if (w.running) speed else 0f, gpsFix = true,
            speedHistory = (w.speedHistory + speed * 3.6f).takeLast(60),
        )
    }

    fun pause() {
        val w = _live.value ?: return
        if (!w.running) return
        stepsBeforePause = w.steps; stepBase = -1f; lastLocation = null
        _live.value = w.copy(accumulatedMs = w.elapsedMs(), resumedAt = null, speedMps = 0f)
        app?.let { WorkoutService.update(it) }
    }

    fun resume() {
        val w = _live.value ?: return
        if (w.running) return
        _live.value = w.copy(resumedAt = SystemClock.elapsedRealtime())
        app?.let { WorkoutService.update(it) }
    }

    /** Ends the workout; saves it unless it was shorter than 10 seconds or [discard] is true. */
    fun stop(discard: Boolean = false): WorkoutSession? {
        val w = _live.value ?: return null
        val c = app
        tickJob?.cancel()
        _live.value = null
        if (c != null) {
            runCatching { c.getSystemService(LocationManager::class.java)?.removeUpdates(locationListener) }
            runCatching { c.getSystemService(SensorManager::class.java)?.unregisterListener(stepListener) }
            WorkoutService.stop(c)
        }
        val secs = w.elapsedMs() / 1000
        if (discard || secs < 10 || c == null) return null
        val repo = Repository.get(c)
        val weight = repo.state.value.settings.weightKg
        val session = WorkoutSession(newId("w"), w.type.id, w.type.name, w.type.emoji, w.startedAt, secs, w.distanceM, w.steps, w.calories(weight))
        repo.update { it.copy(workouts = listOf(session) + it.workouts) }
        repo.flush()
        return session
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }
}

/** Keeps the workout running (GPS + steps) with the screen off and shows a live notification. */
class WorkoutService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gps = intent?.getBooleanExtra(EXTRA_GPS, false) == true
        val steps = intent?.getBooleanExtra(EXTRA_STEPS, false) == true
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (gps) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 34 && (steps || type == 0)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(this) ?: return START_NOT_STICKY, type)
        } catch (e: Exception) {
            // Missing permission for the service type: the workout keeps running while the app is open.
            Log.w("WorkoutService", "Couldn't run in the foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIF_ID = 7301
        private const val EXTRA_GPS = "gps"
        private const val EXTRA_STEPS = "steps"

        fun start(c: Context, gps: Boolean, steps: Boolean) {
            val canHealth = Build.VERSION.SDK_INT < 34 || WorkoutTracker.hasActivity(c)
            if (!gps && !canHealth) { update(c); return }
            runCatching {
                ContextCompat.startForegroundService(c, Intent(c, WorkoutService::class.java).putExtra(EXTRA_GPS, gps).putExtra(EXTRA_STEPS, steps))
            }.onFailure { update(c) }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WorkoutService::class.java))
            NotificationManagerCompat.from(c).cancel(NOTIF_ID)
        }

        @SuppressLint("MissingPermission")
        fun update(c: Context) {
            if (!com.voicekanban.app.reminders.Reminders.canPost(c)) return
            notification(c)?.let { NotificationManagerCompat.from(c).notify(NOTIF_ID, it) }
        }

        fun notification(c: Context): Notification? {
            val w = WorkoutTracker.live.value ?: return null
            val weight = Repository.get(c).state.value.settings.weightKg
            val open = PendingIntent.getActivity(
                c, 7302, Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("voicekanban://workout")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val parts = buildList {
                if (w.gpsAvailable) add("%.2f km".format(w.distanceM / 1000))
                if (w.stepsAvailable) add("${w.steps} steps")
                add("%.0f kcal".format(w.calories(weight)))
            }
            return NotificationCompat.Builder(c, VoiceKanbanApp.CHANNEL_WORKOUT_LIVE)
                .setSmallIcon(R.drawable.ic_stat_voicekanban)
                .setContentTitle("${w.type.emoji} ${w.type.name} · ${WorkoutTracker.formatDuration(w.elapsedMs())}${if (w.running) "" else " · paused"}")
                .setContentText(parts.joinToString(" · "))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setContentIntent(open)
                .build()
        }
    }
}
