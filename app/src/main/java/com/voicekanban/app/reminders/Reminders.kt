package com.voicekanban.app.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.CalendarReminder
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.ReminderRepeat
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.Workspace
import java.time.Instant
import java.time.ZoneId

/**
 * Deadline reminders & calendar-only reminders.
 * One alarm per card with a deadline + reminder offset, and one alarm per active [CalendarReminder].
 * [sync] is idempotent: call it whenever the workspace or calendar reminders change and it
 * (re)schedules everything in the future and cancels what's no longer needed.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY_IDS = "scheduled"
    private const val KEY_CAL_IDS = "scheduled_cal"

    const val ACTION_FIRE = "com.voicekanban.app.REMINDER"
    const val ACTION_DONE = "com.voicekanban.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.voicekanban.app.REMINDER_SNOOZE"
    const val EXTRA_CARD = "cardId"
    const val EXTRA_BOARD = "boardId"

    const val ACTION_CAL_FIRE = "com.voicekanban.app.CAL_REMINDER"
    const val ACTION_CAL_DONE = "com.voicekanban.app.CAL_REMINDER_DONE"
    const val ACTION_CAL_SNOOZE = "com.voicekanban.app.CAL_REMINDER_SNOOZE"
    const val EXTRA_CAL_REMINDER = "calReminderId"

    private fun requestCode(key: String) = key.hashCode()

    private fun pending(context: Context, cardId: String, boardId: String, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, requestCode(cardId + action),
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_CARD, cardId).putExtra(EXTRA_BOARD, boardId)
                .setData(Uri.parse("voicekanban://reminder/$cardId")),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pendingCal(context: Context, remId: String, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, requestCode("cal:$remId:$action"),
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_CAL_REMINDER, remId)
                .setData(Uri.parse("voicekanban://calreminder/$remId")),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun triggerAt(card: Card): Long? {
        val due = card.dueAt ?: return null
        val before = card.remindBeforeMin ?: return null
        return due - before * 60_000L
    }

    fun triggerAt(rem: CalendarReminder, nowMs: Long = System.currentTimeMillis()): Long? {
        val next = rem.nextOccurrenceAt(nowMs) ?: return null
        return next - rem.remindBeforeMin * 60_000L
    }

    fun sync(context: Context, ws: Workspace, enabled: Boolean, calendarReminders: List<CalendarReminder> = emptyList()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        val prevCal = prefs.getStringSet(KEY_CAL_IDS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val wanted = mutableSetOf<String>()
        val wantedCal = mutableSetOf<String>()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        fun schedule(atMs: Long, pi: PendingIntent) {
            val fire = atMs.coerceAtLeast(now + 1000)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
        }

        if (enabled) {
            for (board in ws.boards) for (stage in board.stages) {
                if (board.isDone(stage.id)) continue
                for (card in stage.cards) {
                    val at = triggerAt(card) ?: continue
                    if (at <= now - 60_000) continue
                    wanted += card.id
                    val pi = pending(context, card.id, board.id, ACTION_FIRE) ?: continue
                    schedule(at, pi)
                }
            }
            for (rem in calendarReminders) {
                val at = triggerAt(rem, now) ?: continue
                if (at <= now - 60_000) continue
                wantedCal += rem.id
                val pi = pendingCal(context, rem.id, ACTION_CAL_FIRE) ?: continue
                schedule(at, pi)
            }
        }
        // Cancel alarms for cards that were deleted, finished or had their deadline removed.
        for (id in previous - wanted) {
            pending(context, id, "", ACTION_FIRE, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        // Cancel alarms for calendar reminders that were deleted or completed.
        for (id in prevCal - wantedCal) {
            pendingCal(context, id, ACTION_CAL_FIRE, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        prefs.edit().putStringSet(KEY_IDS, wanted).putStringSet(KEY_CAL_IDS, wantedCal).apply()
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(context: Context, boardId: String, boardTitle: String, card: Card) {
        if (!canPost(context)) return
        val now = System.currentTimeMillis()
        val due = card.dueAt
        val title = if (due != null && due < now) "Overdue: ${card.text}" else "Due ${due?.let { DateParser.relative(it) } ?: "soon"}: ${card.text}"
        val open = PendingIntent.getActivity(
            context, requestCode(card.id + "open"),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("voicekanban://card/${card.id}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, VoiceKanbanApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle(title)
            .setContentText(listOfNotNull(boardTitle, due?.let { DateParser.label(it) }, card.priority.label + " priority").joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(card.notes, "$boardTitle · ${due?.let { DateParser.label(it) }}").joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Mark done", pending(context, card.id, boardId, ACTION_DONE))
            .addAction(0, "Snooze 1 h", pending(context, card.id, boardId, ACTION_SNOOZE))
            .build()
        NotificationManagerCompat.from(context).notify(requestCode(card.id), n)
    }

    @SuppressLint("MissingPermission")
    fun showCalendarReminder(context: Context, rem: CalendarReminder) {
        if (!canPost(context)) return
        val dayStr = Instant.ofEpochMilli(rem.at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val open = PendingIntent.getActivity(
            context, requestCode("cal:${rem.id}:open"),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("voicekanban://calendar?date=$dayStr")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val timeText = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(rem.at))
        val meta = listOfNotNull(
            "Calendar reminder",
            timeText,
            rem.repeat.takeIf { it != ReminderRepeat.NONE }?.label,
        ).joinToString(" · ")
        val bigText = listOfNotNull(rem.notes.ifBlank { null }, meta).joinToString("\n")
        val n = NotificationCompat.Builder(context, VoiceKanbanApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle("🔔 ${rem.title}")
            .setContentText(if (rem.notes.isNotBlank()) "${rem.notes} · $timeText" else meta)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Done", pendingCal(context, rem.id, ACTION_CAL_DONE))
            .addAction(0, "Snooze 15 m", pendingCal(context, rem.id, ACTION_CAL_SNOOZE))
            .build()
        NotificationManagerCompat.from(context).notify(requestCode("cal:${rem.id}"), n)
    }

    fun dismiss(context: Context, cardId: String) = NotificationManagerCompat.from(context).cancel(requestCode(cardId))
    fun dismissCalendar(context: Context, remId: String) = NotificationManagerCompat.from(context).cancel(requestCode("cal:$remId"))
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = Repository.get(context)
        val action = intent.action ?: return

        // Handle standalone calendar reminders
        if (action == Reminders.ACTION_CAL_FIRE || action == Reminders.ACTION_CAL_DONE || action == Reminders.ACTION_CAL_SNOOZE) {
            val remId = intent.getStringExtra(Reminders.EXTRA_CAL_REMINDER) ?: return
            val rem = repo.state.value.calendarReminders.firstOrNull { it.id == remId } ?: return
            when (action) {
                Reminders.ACTION_CAL_FIRE -> Reminders.showCalendarReminder(context, rem)
                Reminders.ACTION_CAL_DONE -> {
                    repo.update { st ->
                        st.copy(calendarReminders = st.calendarReminders.map {
                            if (it.id == remId) it.advanceAfter() else it
                        })
                    }
                    repo.flush()
                    Reminders.dismissCalendar(context, remId)
                }
                Reminders.ACTION_CAL_SNOOZE -> {
                    val snoozeAt = System.currentTimeMillis() + 15 * 60_000L
                    repo.update { st ->
                        st.copy(calendarReminders = st.calendarReminders.map {
                            if (it.id == remId) it.copy(at = snoozeAt, remindBeforeMin = 0, done = false) else it
                        })
                    }
                    repo.flush()
                    Reminders.dismissCalendar(context, remId)
                }
            }
            val s = repo.state.value
            Reminders.sync(context, s.workspace, s.settings.remindersEnabled, s.calendarReminders)
            return
        }

        // Handle Kanban card deadline reminders
        val cardId = intent.getStringExtra(Reminders.EXTRA_CARD) ?: return
        val ws = repo.state.value.workspace
        val board = ws.boards.firstOrNull { b -> b.stages.any { s -> s.cards.any { it.id == cardId } } } ?: return
        val card = board.stages.flatMap { it.cards }.first { it.id == cardId }

        when (action) {
            Reminders.ACTION_FIRE -> Reminders.show(context, board.id, board.title, card)
            Reminders.ACTION_DONE -> {
                val done = board.doneStage ?: return
                repo.updateBoard(board.id) { b ->
                    b.copy(stages = b.stages.map { s ->
                        when (s.id) {
                            done.id -> if (s.cards.any { it.id == cardId }) s else s.copy(cards = listOf(card) + s.cards)
                            else -> s.copy(cards = s.cards.filterNot { it.id == cardId })
                        }
                    })
                }
                repo.flush()
                Reminders.dismiss(context, cardId)
            }
            Reminders.ACTION_SNOOZE -> {
                val snoozeUntil = System.currentTimeMillis() + 60 * 60_000L
                // Move the reminder (not the deadline) by expressing it as an offset from the deadline.
                val due = card.dueAt ?: snoozeUntil
                val offsetMin = ((due - snoozeUntil) / 60_000L).toInt()
                repo.updateBoard(board.id) { b ->
                    b.copy(stages = b.stages.map { s -> s.copy(cards = s.cards.map { if (it.id == cardId) it.copy(remindBeforeMin = offsetMin, dueAt = due) else it }) })
                }
                repo.flush()
                Reminders.dismiss(context, cardId)
            }
        }
        val s = repo.state.value
        Reminders.sync(context, s.workspace, s.settings.remindersEnabled, s.calendarReminders)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Repository.get(context).state.value
        Reminders.sync(context, s.workspace, s.settings.remindersEnabled, s.calendarReminders)
        com.voicekanban.app.fitness.WorkoutReminders.sync(context, s.workoutReminder)
    }
}
