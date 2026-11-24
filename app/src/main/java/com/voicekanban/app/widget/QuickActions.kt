package com.voicekanban.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.ai.Assistant
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.Workspace
import com.voicekanban.app.reminders.Reminders

/** Silent, persistent status-bar entry: progress, next task, Talk / Open board. */
object QuickActions {
    private const val ID = 7001

    private fun link(context: Context, uri: String, code: Int) = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun show(context: Context, ws: Workspace) {
        if (!Reminders.canPost(context)) return
        val board = ws.activeBoard
        val next = Assistant.nextUp(board)
        val body = next?.let { (c, _) -> "Next: ${c.text}" + (c.dueAt?.let { " · ${DateParser.relative(it)}" } ?: "") } ?: "All clear - tap Talk to plan what’s next"
        val n = NotificationCompat.Builder(context, VoiceKanbanApp.CHANNEL_QUICK)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle("${board.title} · ${board.doneCount}/${board.totalCards} done")
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(link(context, "voicekanban://board", 1))
            .addAction(0, "🎙 Talk", link(context, "voicekanban://assistant?listen=1", 2))
            .addAction(0, "Open board", link(context, "voicekanban://board", 3))
            .build()
        NotificationManagerCompat.from(context).notify(ID, n)
    }

    fun hide(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}
