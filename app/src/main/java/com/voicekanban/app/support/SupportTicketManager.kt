package com.voicekanban.app.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.voicekanban.app.BuildConfig
import com.voicekanban.app.data.SupportTicket
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

enum class SupportDeliveryResult {
    OPENED_EMAIL_APP,
    OPENED_WEB_COMPOSE,
    COPIED_TO_CLIPBOARD,
}

/**
 * Formats, validates, and dispatches Voxban support tickets directly to [SUPPORT_EMAIL].
 * Prefers the user's installed email client (with optional screenshot attachment),
 * falls back to Gmail web compose in a browser, and finally copies the full ticket
 * to the clipboard so a user can always reach support even without a mail client.
 */
object SupportTicketManager {
    const val SUPPORT_EMAIL = "abdullahmeyad7@gmail.com"
    private val ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** Human-readable ticket ID such as VB-2611-A7F3 for easy Gmail search & follow-ups. */
    fun generateTicketId(now: LocalDate = LocalDate.now()): String {
        val ym = "%02d%02d".format(Locale.US, now.year % 100, now.monthValue)
        val suffix = buildString(4) {
            repeat(4) { append(ID_CHARS[Random.nextInt(ID_CHARS.length)]) }
        }
        return "VB-$ym-$suffix"
    }

    /** Privacy-safe device and app metadata (never includes user data, keys, or passwords). */
    fun buildDiagnostics(context: Context): String {
        val dm = context.resources.displayMetrics
        val wDp = (dm.widthPixels / dm.density).toInt()
        val hDp = (dm.heightPixels / dm.density).toInt()
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.US) }
        return buildString {
            appendLine("- App: Voxban ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Device: $manufacturer ${Build.MODEL}")
            appendLine("- Locale: ${Locale.getDefault()} · ${TimeZone.getDefault().id}")
            append("- Screen: ${wDp}×${hDp} dp")
        }
    }

    fun formatEmailSubject(ticket: SupportTicket, isFollowUp: Boolean = false): String {
        val prefix = if (isFollowUp) "Re: " else ""
        return "$prefix[Voxban Support #${ticket.id}] [${ticket.category.label}] ${ticket.subject.trim()}"
    }

    fun formatEmailBody(
        ticket: SupportTicket,
        userName: String = "",
        diagnostics: String? = null,
        isFollowUp: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sender = listOf(userName.trim(), ticket.contactEmail.trim())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")
            .ifEmpty { "Voxban user" }
        val submitted = Instant.ofEpochMilli(ticket.createdAt).atZone(zone).format(DATE_FMT)
        return buildString {
            if (isFollowUp) {
                appendLine("Follow-up on Voxban Support Ticket #${ticket.id}")
                appendLine()
                appendLine("[Write your update here]")
                appendLine()
                appendLine("──────── Original Ticket ────────")
            } else {
                appendLine("Voxban Support Ticket")
                appendLine("────────────────────────────────")
            }
            appendLine("Ticket ID:  #${ticket.id}")
            appendLine("Category:   ${ticket.category.label}")
            appendLine("Priority:   ${ticket.priority.label}")
            appendLine("From:       $sender")
            appendLine("Submitted:  $submitted")
            appendLine()
            appendLine("Message:")
            appendLine(ticket.description.trim())
            if (ticket.includeDiagnostics && !diagnostics.isNullOrBlank()) {
                appendLine()
                appendLine("Device & App Diagnostics:")
                appendLine(diagnostics.trim())
            }
        }
    }

    fun encodeUrlParam(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun buildMailtoUriString(
        ticket: SupportTicket,
        userName: String = "",
        diagnostics: String? = null,
        isFollowUp: Boolean = false,
    ): String {
        val subject = encodeUrlParam(formatEmailSubject(ticket, isFollowUp))
        val body = encodeUrlParam(formatEmailBody(ticket, userName, diagnostics, isFollowUp))
        return "mailto:$SUPPORT_EMAIL?subject=$subject&body=$body"
    }

    fun buildWebGmailUrl(
        ticket: SupportTicket,
        userName: String = "",
        diagnostics: String? = null,
        isFollowUp: Boolean = false,
    ): String {
        val to = encodeUrlParam(SUPPORT_EMAIL)
        val subject = encodeUrlParam(formatEmailSubject(ticket, isFollowUp))
        val body = encodeUrlParam(formatEmailBody(ticket, userName, diagnostics, isFollowUp))
        return "https://mail.google.com/mail/?view=cm&fs=1&to=$to&su=$subject&body=$body"
    }

    /**
     * Launches the user's email app pre-addressed to [SUPPORT_EMAIL] with the formatted ticket
     * (and optional screenshot attachment). Falls back gracefully to Gmail web compose or clipboard.
     */
    fun sendTicketEmail(
        context: Context,
        ticket: SupportTicket,
        userName: String,
        attachmentUri: Uri? = null,
        isFollowUp: Boolean = false,
    ): SupportDeliveryResult {
        val diagnostics = if (ticket.includeDiagnostics) buildDiagnostics(context) else null
        val subject = formatEmailSubject(ticket, isFollowUp)
        val body = formatEmailBody(ticket, userName, diagnostics, isFollowUp)

        // 1. Prefer native email client (supports attachments via selector + ACTION_SEND)
        val emailIntent = if (attachmentUri != null) {
            Intent(Intent.ACTION_SEND).apply {
                selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, attachmentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val mailto = buildMailtoUriString(ticket, userName, diagnostics, isFollowUp)
            Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }

        if (tryStartActivity(context, emailIntent, "Send support ticket via email")) {
            return SupportDeliveryResult.OPENED_EMAIL_APP
        }

        // If attachment + selector failed on some OEM mail clients, retry plain mailto without attachment
        if (attachmentUri != null) {
            val plainMailto = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse(buildMailtoUriString(ticket, userName, diagnostics, isFollowUp)),
            ).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            if (tryStartActivity(context, plainMailto, "Send support ticket via email")) {
                return SupportDeliveryResult.OPENED_EMAIL_APP
            }
        }

        // 2. Fallback: Gmail Web Compose in browser
        val webUrl = buildWebGmailUrl(ticket, userName, diagnostics, isFollowUp)
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        if (tryStartActivity(context, webIntent, null)) {
            return SupportDeliveryResult.OPENED_WEB_COMPOSE
        }

        // 3. Final fallback: copy full formatted ticket + recipient email to clipboard
        copyTicketToClipboard(context, ticket, userName, isFollowUp)
        return SupportDeliveryResult.COPIED_TO_CLIPBOARD
    }

    private fun tryStartActivity(context: Context, intent: Intent, chooserTitle: String?): Boolean {
        val pm = context.packageManager
        if (intent.resolveActivity(pm) == null) return false
        return runCatching {
            val launch = if (chooserTitle != null) Intent.createChooser(intent, chooserTitle) else intent
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        }.getOrDefault(false)
    }

    fun copyTicketToClipboard(
        context: Context,
        ticket: SupportTicket,
        userName: String,
        isFollowUp: Boolean = false,
    ) {
        val diagnostics = if (ticket.includeDiagnostics) buildDiagnostics(context) else null
        val full = buildString {
            appendLine("To: $SUPPORT_EMAIL")
            appendLine("Subject: ${formatEmailSubject(ticket, isFollowUp)}")
            appendLine()
            append(formatEmailBody(ticket, userName, diagnostics, isFollowUp))
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Voxban Support Ticket #${ticket.id}", full))
    }

    fun copyEmailAddress(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Voxban Support Email", SUPPORT_EMAIL))
    }
}
