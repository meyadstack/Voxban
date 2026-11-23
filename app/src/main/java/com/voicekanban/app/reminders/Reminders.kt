package com.voicekanban.app.reminders

import android.Manifest
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
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.Workspace

/**
 * Deadline reminders. One alarm per card with a deadline + reminder offset.
 * [sync] is idempotent: call it whenever the workspace changes and it
 * (re)schedules everything in the future and cancels what's no longer needed.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY_IDS = "scheduled"

    const val ACTION_FIRE = "com.voicekanban.app.REMINDER"
    const val ACTION_DONE = "com.voicekanban.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.voicekanban.app.REMINDER_SNOOZE"
    const val EXTRA_CARD = "cardId"
    const val EXTRA_BOARD = "boardId"

    private fun requestCode(cardId: String) = cardId.hashCode()

    private fun pending(context: Context, cardId: String, boardId: String, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, requestCode(cardId + action),
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_CARD, cardId).putExtra(EXTRA_BOARD, boardId)
                .setData(Uri.parse("voicekanban://reminder/$cardId")),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun triggerAt(card: Card): Long? {
        val due = card.dueAt ?: return null
        val before = card.remindBeforeMin ?: return null
        return due - before * 60_000L
    }

    fun sync(context: Context, ws: Workspace, enabled: Boolean) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val wanted = mutableSetOf<String>()

        if (enabled) for (board in ws.boards) for (stage in board.stages) {
            if (board.isDone(stage.id)) continue
            for (card in stage.cards) {
                val at = triggerAt(card) ?: continue
                if (at <= now - 60_000) continue
                wanted += card.id
                val pi = pending(context, card.id, board.id, ACTION_FIRE) ?: continue
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.coerceAtLeast(now + 1000), pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.coerceAtLeast(now + 1000), pi)
            }
        }
        // Cancel alarms for cards that were deleted, finished or had their deadline removed.
        for (id in previous - wanted) {
            pending(context, id, "", ACTION_FIRE, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        prefs.edit().putStringSet(KEY_IDS, wanted).apply()
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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

    fun dismiss(context: Context, cardId: String) = NotificationManagerCompat.from(context).cancel(requestCode(cardId))
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(Reminders.EXTRA_CARD) ?: return
        val repo = Repository.get(context)
        val ws = repo.state.value.workspace
        val board = ws.boards.firstOrNull { b -> b.stages.any { s -> s.cards.any { it.id == cardId } } } ?: return
        val card = board.stages.flatMap { it.cards }.first { it.id == cardId }

        when (intent.action) {
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
        Reminders.sync(context, repo.state.value.workspace, repo.state.value.settings.remindersEnabled)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Repository.get(context).state.value
        Reminders.sync(context, s.workspace, s.settings.remindersEnabled)
    }
}
