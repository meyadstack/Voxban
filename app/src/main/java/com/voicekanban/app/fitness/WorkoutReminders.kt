package com.voicekanban.app.fitness

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.WorkoutReminder
import com.voicekanban.app.reminders.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Daily workout reminder: one alarm for the next chosen day/time, re-armed after it fires. */
object WorkoutReminders {
    private const val CODE = 7401

    private fun pending(c: Context, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        c, CODE, Intent(c, WorkoutReminderReceiver::class.java).setAction("com.voicekanban.app.WORKOUT_REMINDER"),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    fun next(r: WorkoutReminder, from: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!r.enabled || r.days.isEmpty()) return null
        for (i in 0..7) {
            val day: LocalDate = from.toLocalDate().plusDays(i.toLong())
            val at = day.atTime(r.hour, r.minute)
            if (day.dayOfWeek.value in r.days && at.isAfter(from.plusSeconds(30))) return at
        }
        return null
    }

    fun sync(c: Context, r: WorkoutReminder) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = next(r)
        if (at == null) { pending(c, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }; return }
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pending(c) ?: return
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    @SuppressLint("MissingPermission")
    fun show(c: Context) {
        if (!Reminders.canPost(c)) return
        val s = Repository.get(c).state.value
        val type = s.types.firstOrNull { it.id == s.workoutReminder.typeId } ?: s.types.first()
        val today = s.workouts.filter { java.time.Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }
        if (today.sumOf { it.durationSec } >= type.goalMin * 60L) return // goal already met today
        val open = PendingIntent.getActivity(
            c, CODE + 1, Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("voicekanban://workout?start=${type.id}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, VoiceKanbanApp.CHANNEL_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle("Time to move ${type.emoji}")
            .setContentText("Your ${type.goalMin}-minute ${type.name.lowercase()} is waiting. Tap to start the timer.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(c).notify(CODE, n)
    }
}

class WorkoutReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkoutReminders.show(context)
        WorkoutReminders.sync(context, Repository.get(context).state.value.workoutReminder)
    }
}
