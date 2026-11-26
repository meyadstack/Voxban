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
        com.voicekanban.app.ai.Gemma4DownloadManager.refresh(this)
        val repo = Repository.get(this)
        // Keep reminders, the widget and the status-bar shortcut in sync with the data.
        repo.state
            .map { listOf(it.workspace, it.calendarReminders, it.settings.remindersEnabled, it.settings.quickActionsEnabled) }
            .distinctUntilChanged()
            .debounce(600)
            .onEach {
                val s = repo.state.value
                Reminders.sync(this, s.workspace, s.settings.remindersEnabled, s.calendarReminders)
                BoardWidget.refresh(this)
                if (s.settings.quickActionsEnabled) QuickActions.show(this, s.workspace) else QuickActions.hide(this)
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "AI model downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Background download progress for on-device Gemma 4 models"
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MUSIC, "Workout music", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playback controls for workout music"
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
        const val CHANNEL_MUSIC = "workout-music"
    }
}
