package com.voicekanban.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Drafts
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.SupportCategory
import com.voicekanban.app.data.SupportPriority
import com.voicekanban.app.data.SupportTicket
import com.voicekanban.app.data.SupportTicketStatus
import com.voicekanban.app.support.SupportDeliveryResult
import com.voicekanban.app.support.SupportTicketManager
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.ToastKind
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.components.SectionLabel
import com.voicekanban.app.ui.theme.VK
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class SupportTab { NEW, HISTORY }

private val TICKET_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

/**
 * Compact panel inside Settings for launching the Help & Support ticket system.
 */
@Composable
fun SupportSettingsPanel(vm: MainViewModel, onOpenSupport: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val count = app.supportTickets.size

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.limeTile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SupportAgent, null, tint = c.onLime, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Support Tickets", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(
                    SupportTicketManager.SUPPORT_EMAIL + if (count > 0) " · $count ticket${if (count == 1) "" else "s"}" else "",
                    fontSize = 11.sp,
                    color = c.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "Report a bug, request a feature, or ask for help — tickets are mailed straight to ${SupportTicketManager.SUPPORT_EMAIL} with a tracking ID.",
            fontSize = 12.sp,
            color = c.inkSecondary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenSupport,
                modifier = Modifier.weight(1f),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
            ) {
                Icon(Icons.Rounded.ConfirmationNumber, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Support", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    SupportTicketManager.copyEmailAddress(ctx)
                    vm.showToast(ToastKind.INFO, "Copied support email", SupportTicketManager.SUPPORT_EMAIL)
                },
                shape = CircleShape,
            ) {
                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", maxLines = 1)
            }
        }
    }
}

/**
 * Full Help & Support ticket bottom sheet:
 * - New Ticket tab: compose structured support tickets emailed to abdullahmeyad7@gmail.com
 * - My Tickets tab: track submitted/draft/resolved tickets and send email follow-ups
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val tickets = app.supportTickets

    var tab by rememberSaveable { mutableStateOf(SupportTab.NEW) }
    var editingTicketId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf(SupportCategory.BUG) }
    var priority by rememberSaveable { mutableStateOf(SupportPriority.NORMAL) }
    var subject by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var contactEmail by rememberSaveable { mutableStateOf("") }
    var includeDiagnostics by rememberSaveable { mutableStateOf(true) }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    val diagnosticsPreview = remember(ctx) { SupportTicketManager.buildDiagnostics(ctx) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachmentUri = uri
        attachmentName = uri?.let { u ->
            runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }.getOrNull() ?: u.lastPathSegment ?: "screenshot.png"
        }
    }

    fun resetComposer() {
        editingTicketId = null
        category = SupportCategory.BUG
        priority = SupportPriority.NORMAL
        subject = ""
        description = ""
        attachmentUri = null
        attachmentName = null
        validationError = null
    }

    fun dispatchTicket(ticket: SupportTicket, uri: Uri?, isFollowUp: Boolean) {
        val result = SupportTicketManager.sendTicketEmail(
            context = ctx,
            ticket = ticket,
            userName = app.settings.userName,
            attachmentUri = uri,
            isFollowUp = isFollowUp,
        )
        when (result) {
            SupportDeliveryResult.OPENED_EMAIL_APP -> {
                statusNotice = "Ticket #${ticket.id} opened in your email app (to ${SupportTicketManager.SUPPORT_EMAIL}). Tap Send to deliver."
                vm.showToast(
                    ToastKind.SUCCESS,
                    "Ticket #${ticket.id} ready in email",
                    "Tap Send in your mail app to deliver to ${SupportTicketManager.SUPPORT_EMAIL}",
                )
            }
            SupportDeliveryResult.OPENED_WEB_COMPOSE -> {
                statusNotice = "Opened Gmail compose for #${ticket.id} addressed to ${SupportTicketManager.SUPPORT_EMAIL}."
                vm.showToast(
                    ToastKind.SUCCESS,
                    "Opened Gmail compose (#${ticket.id})",
                    "Pre-addressed to ${SupportTicketManager.SUPPORT_EMAIL}",
                )
            }
            SupportDeliveryResult.COPIED_TO_CLIPBOARD -> {
                statusNotice = "Ticket #${ticket.id} copied to clipboard. Paste into your mail app and send to ${SupportTicketManager.SUPPORT_EMAIL}."
                vm.showToast(
                    ToastKind.INFO,
                    "Ticket #${ticket.id} copied to clipboard",
                    "No email app found — paste and send to ${SupportTicketManager.SUPPORT_EMAIL}",
                )
            }
        }
    }

    fun submitTicket(asDraft: Boolean) {
        val cleanSubject = subject.trim()
        val cleanDesc = description.trim()
        if (cleanSubject.isEmpty()) {
            validationError = "Please enter a short subject for your ticket."
            return
        }
        if (cleanDesc.isEmpty()) {
            validationError = "Please describe the issue, question, or request."
            return
        }
        validationError = null
        val existing = editingTicketId?.let { id -> tickets.firstOrNull { it.id == id } }
        val now = System.currentTimeMillis()
        val ticket = SupportTicket(
            id = existing?.id ?: SupportTicketManager.generateTicketId(),
            category = category,
            priority = priority,
            subject = cleanSubject,
            description = cleanDesc,
            contactEmail = contactEmail.trim(),
            includeDiagnostics = includeDiagnostics,
            status = if (asDraft) SupportTicketStatus.DRAFT else SupportTicketStatus.SENT,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val pendingUri = attachmentUri
        vm.saveSupportTicket(ticket)
        resetComposer()
        tab = SupportTab.HISTORY
        if (asDraft) {
            statusNotice = "Saved draft #${ticket.id}. Open My Tickets anytime to edit or mail it."
            vm.showToast(ToastKind.INFO, "Saved draft #${ticket.id}", "You can edit or mail it anytime.")
        } else {
            dispatchTicket(ticket, pendingUri, isFollowUp = false)
        }
    }

    AppSheet(
        title = "Help & Support",
        subtitle = "Direct mail to ${SupportTicketManager.SUPPORT_EMAIL}",
        onDismiss = onDismiss,
    ) {
        // Recipient banner with one-tap copy
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceWell)
                .border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.limeTile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Email, null, tint = c.onLime, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Support inbox", fontSize = 11.sp, color = c.inkTertiary)
                Text(
                    SupportTicketManager.SUPPORT_EMAIL,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.clip(CircleShape).background(c.surface).border(1.dp, c.border, CircleShape)
                    .clickable {
                        SupportTicketManager.copyEmailAddress(ctx)
                        statusNotice = "Copied ${SupportTicketManager.SUPPORT_EMAIL} to clipboard."
                        vm.showToast(ToastKind.INFO, "Email copied", SupportTicketManager.SUPPORT_EMAIL)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.ContentCopy, "Copy email", tint = c.ink, modifier = Modifier.size(14.dp))
                    Text("Copy", fontSize = 11.sp, color = c.ink, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Tabs: New Ticket | My Tickets
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                label = if (editingTicketId != null) "Edit Draft" else "New Ticket",
                active = tab == SupportTab.NEW,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ConfirmationNumber,
            ) { tab = SupportTab.NEW }
            Pill(
                label = "My Tickets (${tickets.size})",
                active = tab == SupportTab.HISTORY,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.MarkEmailRead,
            ) { tab = SupportTab.HISTORY }
        }

        statusNotice?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(c.lowSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.low, modifier = Modifier.size(18.dp))
                Text(msg, fontSize = 12.sp, color = c.ink, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.Close,
                    "Dismiss notice",
                    tint = c.inkSecondary,
                    modifier = Modifier.size(16.dp).clickable { statusNotice = null },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when (tab) {
            SupportTab.NEW -> {
                if (editingTicketId != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceWell)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Editing draft #$editingTicketId", fontSize = 12.sp, color = c.inkSecondary, modifier = Modifier.weight(1f))
                        Text(
                            "New",
                            fontSize = 12.sp,
                            color = c.accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { resetComposer() },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                SectionLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupportCategory.entries.forEach { cat ->
                        Chip("${cat.emoji} ${cat.label}", selected = category == cat) { category = cat }
                    }
                }

                SectionLabel("Priority")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportPriority.entries.forEach { pri ->
                        val tint = when (pri) {
                            SupportPriority.URGENT -> c.danger
                            SupportPriority.NORMAL -> c.accent
                            SupportPriority.LOW -> c.inkSecondary
                        }
                        Chip(pri.label, Modifier.weight(1f), selected = priority == pri, tint = if (priority == pri) tint else null) {
                            priority = pri
                        }
                    }
                }

                SectionLabel("Subject")
                InputField(
                    value = subject,
                    onValue = { subject = it; validationError = null },
                    placeholder = "Short summary (e.g. Crash when exporting backup)",
                )

                SectionLabel("Message")
                InputField(
                    value = description,
                    onValue = { description = it; validationError = null },
                    placeholder = "Tell us what happened, steps to reproduce, or your idea…",
                    singleLine = false,
                )

                SectionLabel("Your contact email (optional)")
                InputField(
                    value = contactEmail,
                    onValue = { contactEmail = it },
                    placeholder = "you@example.com (for reply reference)",
                )

                // Optional screenshot attachment
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.AttachFile, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (attachmentUri == null) "Attach screenshot" else "Change image", fontSize = 12.sp)
                    }
                    attachmentName?.let { name ->
                        Row(
                            Modifier.weight(1f).clip(CircleShape).background(c.surfaceWell)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = 11.sp, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Rounded.Close,
                                "Remove attachment",
                                tint = c.inkSecondary,
                                modifier = Modifier.size(15.dp).clickable { attachmentUri = null; attachmentName = null },
                            )
                        }
                    }
                }

                // Device diagnostics toggle
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { includeDiagnostics = !includeDiagnostics },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Include device diagnostics", style = MaterialTheme.typography.labelLarge, color = c.ink)
                            Text("App version, Android OS & device model (no personal data).", fontSize = 11.sp, color = c.inkSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = includeDiagnostics, onCheckedChange = { includeDiagnostics = it })
                    }
                    if (includeDiagnostics) {
                        Text(
                            diagnosticsPreview,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = c.inkTertiary,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                validationError?.let { err ->
                    Text(err, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }

                Spacer(Modifier.height(14.dp))

                // Primary CTA: Send Ticket via Email
                Button(
                    onClick = { submitTicket(asDraft = false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send Ticket to Support", fontWeight = FontWeight.SemiBold)
                }

                // Secondary actions: Save Draft | Copy Ticket
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { submitTicket(asDraft = true) },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Drafts, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Draft", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val cleanSubject = subject.trim()
                            val cleanDesc = description.trim()
                            if (cleanSubject.isEmpty() || cleanDesc.isEmpty()) {
                                validationError = "Fill in subject and message first to copy the ticket."
                            } else {
                                val preview = SupportTicket(
                                    id = editingTicketId ?: SupportTicketManager.generateTicketId(),
                                    category = category,
                                    priority = priority,
                                    subject = cleanSubject,
                                    description = cleanDesc,
                                    contactEmail = contactEmail.trim(),
                                    includeDiagnostics = includeDiagnostics,
                                )
                                SupportTicketManager.copyTicketToClipboard(ctx, preview, app.settings.userName)
                                vm.showToast(ToastKind.INFO, "Formatted ticket copied", "Ready to mail to ${SupportTicketManager.SUPPORT_EMAIL}")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Text", maxLines = 1)
                    }
                }
            }

            SupportTab.HISTORY -> {
                if (tickets.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.SupportAgent, null, tint = c.inkTertiary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No support tickets yet", style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text(
                            "Create a ticket to mail ${SupportTicketManager.SUPPORT_EMAIL} with a reference ID and automatic diagnostics.",
                            fontSize = 12.sp,
                            color = c.inkSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { tab = SupportTab.NEW },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                        ) {
                            Text("Create Ticket")
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tickets.forEach { t ->
                            TicketHistoryCard(
                                ticket = t,
                                onEditDraft = {
                                    editingTicketId = t.id
                                    category = t.category
                                    priority = t.priority
                                    subject = t.subject
                                    description = t.description
                                    contactEmail = t.contactEmail
                                    includeDiagnostics = t.includeDiagnostics
                                    tab = SupportTab.NEW
                                },
                                onEmail = {
                                    val followUp = t.status != SupportTicketStatus.DRAFT
                                    if (t.status == SupportTicketStatus.DRAFT) {
                                        vm.setSupportTicketStatus(t.id, SupportTicketStatus.SENT)
                                    }
                                    dispatchTicket(t, uri = null, isFollowUp = followUp)
                                },
                                onToggleResolved = {
                                    val next = if (t.status == SupportTicketStatus.RESOLVED) {
                                        SupportTicketStatus.SENT
                                    } else {
                                        SupportTicketStatus.RESOLVED
                                    }
                                    vm.setSupportTicketStatus(t.id, next)
                                },
                                onCopy = {
                                    SupportTicketManager.copyTicketToClipboard(ctx, t, app.settings.userName)
                                    vm.showToast(ToastKind.INFO, "Copied ticket #${t.id}", SupportTicketManager.SUPPORT_EMAIL)
                                },
                                onDelete = { vm.deleteSupportTicket(t.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketHistoryCard(
    ticket: SupportTicket,
    onEditDraft: () -> Unit,
    onEmail: () -> Unit,
    onToggleResolved: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = VK.colors
    var expanded by remember { mutableStateOf(false) }
    val statusBg = when (ticket.status) {
        SupportTicketStatus.SENT -> c.accent.copy(alpha = 0.14f)
        SupportTicketStatus.DRAFT -> c.mediumSoft
        SupportTicketStatus.RESOLVED -> c.lowSoft
    }
    val statusFg = when (ticket.status) {
        SupportTicketStatus.SENT -> c.accent
        SupportTicketStatus.DRAFT -> c.medium
        SupportTicketStatus.RESOLVED -> c.low
    }
    val timeLabel = remember(ticket.createdAt) {
        Instant.ofEpochMilli(ticket.createdAt).atZone(ZoneId.systemDefault()).format(TICKET_TIME_FMT)
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(18.dp))
            .clickable { expanded = !expanded }.padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${ticket.id}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = c.inkSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(CircleShape).background(statusBg).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(ticket.status.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = statusFg)
            }
            Spacer(Modifier.weight(1f))
            Text(timeLabel, fontSize = 11.sp, color = c.inkTertiary)
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                tint = c.inkTertiary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${ticket.category.emoji} ${ticket.subject}",
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            maxLines = if (expanded) 4 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ticket.description,
            fontSize = 12.sp,
            color = c.inkSecondary,
            maxLines = if (expanded) 12 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (expanded) {
            Text(
                "Priority: ${ticket.priority.label} · Diagnostics: ${if (ticket.includeDiagnostics) "Included" else "Off"}" +
                    if (ticket.contactEmail.isNotBlank()) " · Reply: ${ticket.contactEmail}" else "",
                fontSize = 11.sp,
                color = c.inkTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onEmail,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.limeTile, contentColor = c.onLime),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (ticket.status == SupportTicketStatus.DRAFT) "Send Mail" else "Follow up", fontSize = 12.sp)
                }
                if (ticket.status == SupportTicketStatus.DRAFT) {
                    OutlinedButton(onClick = onEditDraft, shape = CircleShape) {
                        Text("Edit", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onToggleResolved, shape = CircleShape) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (ticket.status == SupportTicketStatus.RESOLVED) "Reopen" else "Resolved", fontSize = 12.sp)
                }
                TextButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", fontSize = 12.sp)
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Delete", fontSize = 12.sp, color = c.danger)
                }
            }
        }
    }
}
