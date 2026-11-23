package com.voicekanban.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.Toast
import com.voicekanban.app.R
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.newId

/**
 * Voxban as the phone's password autofill service:
 * - sign-in screens in apps and browsers get a "Voxban · Netflix · sam@…"
 *   suggestion for matching saved logins (unlocked with fingerprint / screen lock);
 * - after creating an account or signing in with new details, Android asks
 *   "Save to Voxban?" and the login is stored in the encrypted Vault.
 */
class VoxbanAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
            if (structure.activityComponent.packageName == packageName) return callback.onSuccess(null)
            val form = LoginForm.parse(structure)
            if (form.passwordIds.isEmpty() && form.usernameIds.isEmpty()) return callback.onSuccess(null)

            val response = FillResponse.Builder()
            val fillIds = form.usernameIds + form.passwordIds
            // Suggest saved logins for this site / app. Values are released only after unlock.
            if (fillIds.isNotEmpty()) {
                matches(this, Repository.get(this).vault.value, form).take(4).forEachIndexed { i, e ->
                    val presentation = presentation(e.title, e.username.ifBlank { "Saved login" })
                    @Suppress("DEPRECATION")
                    val ds = Dataset.Builder(presentation)
                    fillIds.forEach { id -> @Suppress("DEPRECATION") ds.setValue(id, null, presentation) }
                    ds.setAuthentication(AutofillAuthActivity.intentSender(this, e.id, form.usernameIds, form.passwordIds, i))
                    response.addDataset(ds.build())
                }
            }
            // Ask to save when a new account is created or new details are used.
            if (form.passwordIds.isNotEmpty()) {
                val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (form.usernameIds.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
                val save = SaveInfo.Builder(type, form.passwordIds.toTypedArray())
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                    .setDescription(if (form.isSignUp) "Save your new account to the Voxban vault?" else "Save this login to the Voxban vault?")
                if (form.usernameIds.isNotEmpty()) save.setOptionalIds(form.usernameIds.toTypedArray())
                response.setSaveInfo(save.build())
            }
            callback.onSuccess(response.build())
        } catch (e: Exception) {
            Log.w(TAG, "Fill failed", e)
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            var username = ""; var password = ""; var domain: String? = null; var pkg = ""
            for (ctx in request.fillContexts) {
                val form = LoginForm.parse(ctx.structure)
                form.passwordValue?.takeIf { it.isNotEmpty() }?.let { password = it }
                form.usernameValue?.takeIf { it.isNotBlank() }?.let { username = it.trim() }
                domain = form.webDomain ?: domain
                pkg = ctx.structure.activityComponent.packageName
            }
            if (password.isEmpty()) { callback.onSuccess(); return }
            val title = domain?.let { siteName(it) } ?: appLabel(pkg)
            val url = domain?.let { "https://$it" } ?: "android://$pkg"
            val repo = Repository.get(this)
            val existing = repo.vault.value.firstOrNull { e ->
                (sameSite(e, domain, pkg) || e.title.equals(title, true)) && (username.isBlank() || e.username.equals(username, true) || e.username.isBlank())
            }
            repo.updateVault { list ->
                if (existing != null) list.map { if (it.id == existing.id) it.copy(username = username.ifBlank { it.username }, password = password, url = it.url.ifBlank { url }, updatedAt = System.currentTimeMillis()) else it }
                else listOf(PasswordEntry(newId("p"), title, username, password, url, notes = "Saved by autofill")) + list
            }
            Toast.makeText(this, if (existing != null) "Voxban updated your $title login" else "Saved $title to your Voxban vault", Toast.LENGTH_SHORT).show()
            callback.onSuccess()
        } catch (e: Exception) {
            Log.w(TAG, "Save failed", e)
            callback.onFailure(e.message ?: "Couldn’t save to Voxban")
        }
    }

    private fun presentation(title: String, subtitle: String) = RemoteViews(packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.title, "Voxban · $title")
        setTextViewText(R.id.subtitle, subtitle)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    companion object {
        private const val TAG = "VoxbanAutofill"
        private val NOISE = setOf("com", "org", "net", "www", "app", "android", "mobile", "client", "the", "apps", "co", "io", "inc")

        /** "accounts.google.com" → "Google", "m.facebook.com" → "Facebook". */
        fun siteName(domain: String): String {
            val parts = domain.lowercase().removePrefix("www.").split('.').filter { it.isNotBlank() }
            val core = if (parts.size >= 3 && parts[parts.size - 2].length <= 3) parts[parts.size - 3] else parts.getOrNull(parts.size - 2) ?: parts.firstOrNull() ?: domain
            return core.replaceFirstChar { it.uppercase() }
        }

        private fun words(s: String): Set<String> = s.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.length >= 3 && it !in NOISE }.toSet()

        private fun sameSite(e: PasswordEntry, domain: String?, pkg: String): Boolean {
            val url = e.url.lowercase()
            if (domain != null && url.isNotBlank()) {
                val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.")
                val d = domain.lowercase().removePrefix("www.")
                if (host.isNotBlank() && (host == d || d.endsWith(".$host") || host.endsWith(".$d") || siteName(host).equals(siteName(d), true))) return true
            }
            return url == "android://$pkg"
        }

        /** Saved logins that belong to the site / app on screen. */
        fun matches(context: Context, vault: List<PasswordEntry>, form: LoginForm): List<PasswordEntry> {
            val pkg = form.packageName
            val keys = if (form.webDomain != null) setOf(siteName(form.webDomain).lowercase()) else words(pkg) + words(
                runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(""),
            )
            return vault.filter { e ->
                sameSite(e, form.webDomain, pkg) || words(e.title + " " + e.url).any { it in keys }
            }.sortedByDescending { it.updatedAt }
        }
    }
}

/** Username / password fields found on a screen. */
class LoginForm(
    val packageName: String,
    val webDomain: String?,
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
    val usernameValue: String?,
    val passwordValue: String?,
    val isSignUp: Boolean,
) {
    companion object {
        private val USER_WORDS = listOf("user", "email", "e-mail", "login", "account", "phone", "mobile", "identifier")
        private val PASS_WORDS = listOf("password", "passwd", "pass", "pwd", "pin")

        fun parse(structure: AssistStructure): LoginForm {
            val users = mutableListOf<Pair<AutofillId, String?>>()
            val passes = mutableListOf<Pair<AutofillId, String?>>()
            val texts = mutableListOf<Pair<AutofillId, String?>>() // every text field in order
            var domain: String? = null
            var signUp = false

            fun visit(n: ViewNode) {
                n.webDomain?.takeIf { it.isNotBlank() }?.let { domain = it }
                val id = n.autofillId
                if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
                    val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
                    val html = n.htmlInfo?.attributes?.associate { it.first.lowercase() to it.second.orEmpty().lowercase() }.orEmpty()
                    val label = listOfNotNull(n.idEntry, n.hint, html["name"], html["id"], html["autocomplete"], html["placeholder"]).joinToString(" ").lowercase()
                    val variation = n.inputType and InputType.TYPE_MASK_VARIATION
                    val cls = n.inputType and InputType.TYPE_MASK_CLASS
                    val isPass = hints.any { it.contains("password") } || html["type"] == "password" ||
                        (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
                        (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
                        (hints.isEmpty() && PASS_WORDS.any { label.contains(it) } && !label.contains("forgot"))
                    val isUser = !isPass && (hints.any { it.contains("username") || it.contains("email") || it.contains("phone") } ||
                        html["type"] == "email" || (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) ||
                        USER_WORDS.any { label.contains(it) })
                    val value = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                    if (hints.any { it.contains("new") } || html["autocomplete"] == "new-password" || label.contains("confirm") || label.contains("signup") || label.contains("register")) signUp = true
                    when { isPass -> passes += id to value; isUser -> users += id to value }
                    if (html["type"] != "hidden" && html["type"] != "submit") texts += id to value
                }
                for (i in 0 until n.childCount) visit(n.getChildAt(i))
            }
            for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)

            // No labelled username: use the text field right before the first password field.
            if (users.isEmpty() && passes.isNotEmpty()) {
                val idx = texts.indexOfFirst { it.first == passes.first().first }
                if (idx > 0) users += texts[idx - 1]
            }
            if (passes.size >= 2) signUp = true
            return LoginForm(
                packageName = structure.activityComponent.packageName,
                webDomain = domain,
                usernameIds = users.map { it.first }.distinct(),
                passwordIds = passes.map { it.first }.distinct(),
                usernameValue = users.firstNotNullOfOrNull { it.second?.takeIf { v -> v.isNotBlank() } },
                passwordValue = passes.lastOrNull { !it.second.isNullOrEmpty() }?.second,
                isSignUp = signUp,
            )
        }
    }
}

internal fun pendingFlags() = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE

internal fun Intent.autofillIds(key: String): ArrayList<AutofillId> =
    @Suppress("DEPRECATION") (getParcelableArrayListExtra<AutofillId>(key) ?: arrayListOf())
