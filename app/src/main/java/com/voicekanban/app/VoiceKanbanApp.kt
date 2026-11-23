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
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Deadline reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts before a card’s deadline"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while an on-device AI model downloads"
            setShowBadge(false)
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
    }
}
