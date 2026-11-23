package com.voicekanban.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.fragment.app.FragmentActivity
import com.voicekanban.app.R
import com.voicekanban.app.data.Repository
import com.voicekanban.app.security.BiometricGate

/**
 * Shown when the user picks a Voxban suggestion: confirms it's them
 * (fingerprint / face / screen lock), then hands the username and password
 * back to Android to fill in.
 */
class AutofillAuthActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val entry = Repository.get(this).vault.value.firstOrNull { it.id == intent.getStringExtra(EXTRA_ENTRY) }
        if (entry == null) { setResult(RESULT_CANCELED); finish(); return }
        val done = {
            val users = intent.autofillIds(EXTRA_USER_IDS)
            val passes = intent.autofillIds(EXTRA_PASS_IDS)
            val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.title, "Voxban · ${entry.title}")
                setTextViewText(R.id.subtitle, entry.username)
            }
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(presentation)
            @Suppress("DEPRECATION")
            users.forEach { ds.setValue(it, AutofillValue.forText(entry.username), presentation) }
            @Suppress("DEPRECATION")
            passes.forEach { ds.setValue(it, AutofillValue.forText(entry.password), presentation) }
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds.build()))
            finish()
        }
        if (BiometricGate.availability(this) != BiometricGate.Availability.READY) { done(); return }
        BiometricGate.prompt(
            this, "Fill ${entry.title} login", "Confirm it’s you to use your saved password",
            onSuccess = { done() },
            onFail = { setResult(RESULT_CANCELED); finish() },
        )
    }

    companion object {
        private const val EXTRA_ENTRY = "entry"
        const val EXTRA_USER_IDS = "user_ids"
        const val EXTRA_PASS_IDS = "pass_ids"

        fun intentSender(context: Context, entryId: String, users: List<AutofillId>, passes: List<AutofillId>, index: Int): IntentSender {
            val intent = Intent(context, AutofillAuthActivity::class.java)
                .putExtra(EXTRA_ENTRY, entryId)
                .putParcelableArrayListExtra(EXTRA_USER_IDS, ArrayList(users))
                .putParcelableArrayListExtra(EXTRA_PASS_IDS, ArrayList(passes))
            return PendingIntent.getActivity(context, 4100 + index, intent, pendingFlags()).intentSender
        }
    }
}
