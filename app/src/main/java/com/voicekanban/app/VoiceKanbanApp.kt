package com.voicekanban.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.voicekanban.app.data.Repository
import com.voicekanban.app.reminders.Reminders
import com.voicekanban.app.widget.QuickActions
import com.voicekanban.app.widget.BoardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class VoiceKanbanApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createChannels()
        val repo = Repository.get(this)
        // Keep reminders, the widget and the status-bar shortcut in sync with the data.
        repo.state
            .map { Triple(it.workspace, it.settings.remindersEnabled, it.settings.quickActionsEnabled) }
            .distinctUntilChanged()
            .debounce(600)
            .onEach { (ws, reminders, quick) ->
                Reminders.sync(this, ws, reminders)
                BoardWidget.refresh(this)
                if (quick) QuickActions.show(this, ws) else QuickActions.hide(this)
            }
            .launchIn(appScope)
        repo.state.map { it.workoutReminder }.distinctUntilChanged()
            .onEach { com.voicekanban.app.fitness.WorkoutReminders.sync(this, it) }
            .launchIn(appScope)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Deadline reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts before a card’s deadline"
            enableVibration(true)
        })
        nm.deleteNotificationChannel(CHANNEL_DOWNLOADS)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORKOUT, "Workout reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your daily reminder to move"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORKOUT_LIVE, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Timer, distance and steps while a workout runs"
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FOCUS, "Focus mode", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a focus session ends"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_QUICK, "Quick actions", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent shortcut to talk to your board"
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_QUICK = "quick-actions"
        const val CHANNEL_WORKOUT = "workout"
        const val CHANNEL_WORKOUT_LIVE = "workout-live"
        const val CHANNEL_FOCUS = "focus"
    }
}
