package com.voicekanban.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleLink(intent.data)
    }
}
