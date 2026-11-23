package com.voicekanban.app.focus

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.data.ActiveFocus
import com.voicekanban.app.data.FocusSession
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.newId
import com.voicekanban.app.reminders.Reminders

/**
 * Focus mode: a timed session during which Voxban pins itself to the screen
 * (Android app pinning — other apps and the home screen can't be opened) and
 * can switch on Do Not Disturb. It ends on its own, even if the app is closed.
 */
object Focus {
    private const val CODE = 7501

    fun canUseDnd(c: Context): Boolean = c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun alarm(c: Context, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        c, CODE, Intent(c, FocusEndReceiver::class.java).setAction("com.voicekanban.app.FOCUS_END"), flags or PendingIntent.FLAG_IMMUTABLE,
    )

    fun start(c: Context, minutes: Int, pin: Boolean, dnd: Boolean) {
        val repo = Repository.get(c)
        if (repo.state.value.activeFocus != null) return
        val nm = c.getSystemService(NotificationManager::class.java)
        val useDnd = dnd && canUseDnd(c)
        val previous = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        if (useDnd) runCatching { nm?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        repo.update { it.copy(activeFocus = ActiveFocus(now, until, minutes, pin, useDnd, previous)) }
        repo.flush()
        val am = c.getSystemService(AlarmManager::class.java)
        alarm(c)?.let { pi ->
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi)
        }
    }

    /** Ends the running session (when time is up, or early if the user gives up). */
    fun end(c: Context, completed: Boolean) {
        val repo = Repository.get(c)
        val f = repo.state.value.activeFocus ?: return
        if (f.dnd && canUseDnd(c)) runCatching { c.getSystemService(NotificationManager::class.java)?.setInterruptionFilter(f.previousFilter) }
        alarm(c, PendingIntent.FLAG_NO_CREATE)?.let { c.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel() }
        val actual = ((minOf(System.currentTimeMillis(), f.until) - f.startedAt) / 1000).coerceAtLeast(0)
        val session = FocusSession(newId("f"), f.startedAt, f.plannedMin, actual, completed)
        repo.update { it.copy(activeFocus = null, focusSessions = listOf(session) + it.focusSessions) }
        repo.flush()
        if (completed) notifyDone(c, f.plannedMin)
    }

    @SuppressLint("MissingPermission")
    private fun notifyDone(c: Context, minutes: Int) {
        if (!Reminders.canPost(c)) return
        val n = NotificationCompat.Builder(c, VoiceKanbanApp.CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle("Focus complete 🎉")
            .setContentText("You stayed focused for $minutes minutes. Your phone is unlocked.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(c).notify(CODE, n)
    }
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val f = Repository.get(context).state.value.activeFocus ?: return
        if (System.currentTimeMillis() >= f.until - 2_000) Focus.end(context, completed = true)
    }
}
