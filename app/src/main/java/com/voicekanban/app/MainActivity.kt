package com.voicekanban.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.voicekanban.app.security.BiometricGate
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.screens.AppRoot
import com.voicekanban.app.ui.theme.VoiceKanbanTheme

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.handleLink(intent?.data)


        // Focus mode: pin the app while a session runs, unpin when it ends.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                vm.state.map { it.activeFocus }.distinctUntilChanged().collect { f -> applyPinning(f?.pinned == true) }
            }
        }

        setContent {
            val app by vm.state.collectAsState()
            val isLocked by vm.locked.collectAsState()
            VoiceKanbanTheme(app.settings.themeMode) {
                AppRoot(vm, locked = isLocked && app.settings.biometricLock, onUnlock = ::unlock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.locked.value && vm.state.value.settings.biometricLock) unlock()
        com.voicekanban.app.update.AppUpdater.checkForUpdate(this, manual = false, forceCheck = true)
    }

    override fun onStop() {
        super.onStop()
        com.voicekanban.app.data.Repository.get(this).flush()
    }

    private var prompting = false
    private fun unlock() {
        if (prompting) return
        if (BiometricGate.availability(this) != BiometricGate.Availability.READY) { vm.locked.value = false; return }
        prompting = true
        BiometricGate.prompt(this, "Unlock Voxban", "Use your fingerprint, face or screen lock",
            onSuccess = { prompting = false; vm.locked.value = false },
            onFail = { prompting = false })
    }

    private fun applyPinning(pin: Boolean) {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val pinned = am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
        runCatching {
            if (pin && !pinned) startLockTask()
            if (!pin && pinned) stopLockTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleLink(intent.data)
    }
}
