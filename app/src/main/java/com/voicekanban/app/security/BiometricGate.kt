package com.voicekanban.app.security

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Fingerprint / face unlock, with the phone's PIN, pattern or password as a
 * fallback so a failed sensor never locks someone out of their own data.
 *
 * Android 11+ accepts "biometric OR screen lock" in one prompt. Android 9–10
 * reject that combination, so there we ask for biometrics with a Cancel
 * button, or for the screen lock alone when no biometric is enrolled.
 */
object BiometricGate {
    private const val TAG = "BiometricGate"

    enum class Availability { READY, NOT_ENROLLED, UNAVAILABLE }

    private fun authenticators(context: Context): Int {
        val bm = BiometricManager.from(context)
        return when {
            Build.VERSION.SDK_INT >= 30 -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            bm.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_WEAK
            else -> DEVICE_CREDENTIAL
        }
    }

    fun availability(context: Context): Availability {
        val bm = BiometricManager.from(context)
        val result = bm.canAuthenticate(authenticators(context))
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> {
                // On Android 9–10, DEVICE_CREDENTIAL alone can't be queried; treat a secure keyguard as ready.
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                if (Build.VERSION.SDK_INT < 30 && km?.isDeviceSecure == true) Availability.READY else Availability.UNAVAILABLE
            }
        }
    }

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        val auth = authenticators(activity)
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.i(TAG, "Auth error $errorCode: $errString")
                onFail(errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setConfirmationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 30 || auth == DEVICE_CREDENTIAL) {
                    if (auth == DEVICE_CREDENTIAL && Build.VERSION.SDK_INT < 30) @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
                    else setAllowedAuthenticators(auth)
                } else {
                    setAllowedAuthenticators(auth)
                    setNegativeButtonText("Cancel")
                }
            }
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't show biometric prompt", e)
            onFail(e.message ?: "Biometric prompt unavailable")
        }
    }
}

/** Finds the hosting activity even from inside dialogs / bottom sheets. */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
