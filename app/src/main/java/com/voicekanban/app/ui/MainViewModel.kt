package com.voicekanban.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicekanban.app.ai.Assistant
import com.voicekanban.app.ai.CloudProviders
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.ai.Turn
import com.voicekanban.app.ai.World
import com.voicekanban.app.data.AppLanguage
import com.voicekanban.app.data.ChatSession
import com.voicekanban.app.data.NOTE_COLORS
import com.voicekanban.app.data.Note
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.VoiceChoice
import com.voicekanban.app.data.noteColorByName
import com.voicekanban.app.voice.VoiceOutput
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import com.voicekanban.app.data.AppState
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.ChatMessage
import com.voicekanban.app.data.Engine
import com.voicekanban.app.data.Presets
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.Settings
import com.voicekanban.app.data.Stage
import com.voicekanban.app.data.Workspace
import com.voicekanban.app.data.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Section { HOME, BOARD, ASSISTANT, WORKOUT, NOTES, MONEY, VAULT, FOCUS }

/** Undo point for an assistant turn. */
data class Snapshot(
    val workspace: Workspace, val notes: List<Note>, val vault: List<PasswordEntry>,
    val transactions: List<com.voicekanban.app.data.Transaction> = emptyList(), val budgets: List<com.voicekanban.app.data.Budget> = emptyList(),
)
enum class ToastKind { SUCCESS, ERROR, INFO }

data class Toast(val id: Long, val kind: ToastKind, val title: String, val detail: String? = null, val undo: Workspace? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository.get(app)
    val state: StateFlow<AppState> = repo.state
    val keys: StateFlow<Map<String, String>> = repo.keys
    val vault: StateFlow<List<PasswordEntry>> = repo.vault

    private val _section = MutableStateFlow(Section.HOME)
    val section = _section.asStateFlow()
    private val _autoListen = MutableStateFlow(false)
    val autoListen = _autoListen.asStateFlow()
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast = _toast.asStateFlow()
    private val _highlight = MutableStateFlow<Set<String>>(emptySet())
    val highlight = _highlight.asStateFlow()
    private val _focusStage = MutableStateFlow<String?>(null)
    val focusStage = _focusStage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _thinking = MutableStateFlow(false)
    val thinking = _thinking.asStateFlow()
    /** Undo snapshots for assistant replies (memory only). */
    private val _undoable = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val undoable = _undoable.asStateFlow()
    /** Vault unlock lasts until the app goes to the background. */
    val vaultUnlocked = MutableStateFlow(false)
    private val _focusVaultId = MutableStateFlow<String?>(null)
    val focusVaultId = _focusVaultId.asStateFlow()
    private val _focusNoteId = MutableStateFlow<String?>(null)
    val focusNoteId = _focusNoteId.asStateFlow()
    private val _composing = MutableStateFlow(false)
    /** A note is being written by the AI. */
    val composing = _composing.asStateFlow()

    /** App-lock state lives here so rotation / theme changes don't re-lock. */
    val locked = MutableStateFlow(state.value.settings.biometricLock)
    private var backgroundedAt = 0L

    // Re-lock after 30 s in the background. Registered once per ViewModel (not per activity instance).
    private val lockObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { backgroundedAt = android.os.SystemClock.elapsedRealtime(); vaultUnlocked.value = false; repo.flush() }
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            if (state.value.settings.biometricLock && backgroundedAt > 0 && android.os.SystemClock.elapsedRealtime() - backgroundedAt > 30_000) locked.value = true
        }
    }
    init { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(lockObserver) }

    private var toastJob: Job? = null
    private var highlightJob: Job? = null

    private val ws: Workspace get() = state.value.workspace
    val board: Board get() = ws.activeBoard

    // ------------------------------------------------------------ navigation

    fun setSection(s: Section) { _section.value = s }
    fun openAssistant(listen: Boolean) { _autoListen.value = listen; _section.value = Section.ASSISTANT }
    fun consumeAutoListen() { _autoListen.value = false }
    fun clearFocusStage() { _focusStage.value = null }
    fun clearFocusVault() { _focusVaultId.value = null }
    fun clearFocusNote() { _focusNoteId.value = null }

    fun handleLink(uri: android.net.Uri?) {
        uri ?: return
        val host = uri.host ?: return
        when {
            host.startsWith("assistant") -> openAssistant(uri.getQueryParameter("listen") != null)
            host.startsWith("board") -> _section.value = Section.BOARD
            host.startsWith("notes") -> _section.value = Section.NOTES
            host.startsWith("vault") -> _section.value = Section.VAULT
            host.startsWith("money") -> _section.value = Section.MONEY
            host.startsWith("home") -> _section.value = Section.HOME
            host.startsWith("focus") -> _section.value = Section.FOCUS
            host.startsWith("workout") -> {
                _section.value = Section.WORKOUT
                uri.getQueryParameter("start")?.let { id -> state.value.types.firstOrNull { it.id == id }?.let { _pendingWorkout.value = it } }
            }
            host.startsWith("card") -> {
                _section.value = Section.BOARD
                uri.lastPathSegment?.let { id ->
                    ws.boards.firstOrNull { b -> b.stages.any { s -> s.cards.any { it.id == id } } }?.let { b ->
                        repo.updateWorkspace { it.copy(activeBoardId = b.id) }
                        b.stages.firstOrNull { s -> s.cards.any { it.id == id } }?.let { _focusStage.value = it.id }
                        flash(listOf(id))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ feedback

    fun showToast(kind: ToastKind, title: String, detail: String? = null, undo: Workspace? = null) {
        toastJob?.cancel()
        _toast.value = Toast(System.nanoTime(), kind, title, detail, undo)
        toastJob = viewModelScope.launch { delay(if (undo != null) 6000 else 4200); _toast.value = null }
    }
    fun dismissToast() { _toast.value = null }
    fun undo(snapshot: Workspace) { repo.updateWorkspace { snapshot }; _toast.value = null }
    fun setFocusNote(id: String?) { _focusNoteId.value = id }

    private fun flash(ids: List<String>) {
        highlightJob?.cancel()
        _highlight.value = ids.toSet()
        highlightJob = viewModelScope.launch { delay(2200); _highlight.value = emptySet() }
    }

    private fun commit(title: String, kind: ToastKind = ToastKind.SUCCESS, undo: Boolean = true, transform: (Workspace) -> Workspace) {
        val before = ws
        repo.updateWorkspace(transform)
        showToast(kind, title, undo = if (undo) before else null)
    }

    private fun mapBoard(transform: (Board) -> Board): (Workspace) -> Workspace = { w ->
        w.copy(boards = w.boards.map { if (it.id == w.activeBoardId) transform(it) else it })
    }

    // ------------------------------------------------------------ cards

    fun quickAdd(stageId: String, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        // Allow "Pay rent due friday" in the quick-add field too.
        var title = t; var due: Long? = null
        DateParser.TRAILING_DUE.find(t)?.let { m -> DateParser.parse(m.groupValues[1])?.let { d -> due = d; title = t.substring(0, m.range.first).trim() } }
        val card = Card(newId("c"), title, dueAt = due)
        repo.updateWorkspace(mapBoard { b -> b.copy(stages = b.stages.map { if (it.id == stageId) it.copy(cards = listOf(card) + it.cards) else it }) })
        flash(listOf(card.id))
    }

    fun cyclePriority(cardId: String) = repo.updateWorkspace(mapBoard { b -> b.mapCard(cardId) { it.copy(priority = it.priority.next()) } })

    fun setPriority(cardId: String, p: Priority) {
        val name = findCard(cardId)?.text ?: return
        commit("“$name” set to ${p.label.lowercase()} priority", transform = mapBoard { b -> b.mapCard(cardId) { it.copy(priority = p) } })
    }

    fun moveCard(cardId: String, toStageId: String) {
        val b = board
        val from = b.stages.firstOrNull { s -> s.cards.any { it.id == cardId } } ?: return
        if (from.id == toStageId) return
        val card = from.cards.first { it.id == cardId }
        val dest = b.stages.firstOrNull { it.id == toStageId } ?: return
        val title = if (b.isDone(dest.id)) "Nice! “${card.text}” is done" else "Moved “${card.text}” to ${dest.title}"
        commit(title, transform = mapBoard { bd ->
            bd.copy(stages = bd.stages.map { s ->
                when (s.id) {
                    from.id -> s.copy(cards = s.cards.filterNot { it.id == cardId })
                    dest.id -> s.copy(cards = listOf(card) + s.cards)
                    else -> s
                }
            })
        })
        flash(listOf(cardId))
    }

    fun moveCardBy(cardId: String, delta: Int) {
        val b = board
        val idx = b.stages.indexOfFirst { s -> s.cards.any { it.id == cardId } }
        b.stages.getOrNull(idx + delta)?.let { moveCard(cardId, it.id) }
    }

    fun saveCard(card: Card, toStageId: String) {
        val b = board
        val from = b.stages.firstOrNull { s -> s.cards.any { it.id == card.id } } ?: return
        commit("Changes saved", transform = mapBoard { bd ->
            bd.copy(stages = bd.stages.map { s ->
                when {
                    from.id == toStageId && s.id == from.id -> s.copy(cards = s.cards.map { if (it.id == card.id) card else it })
                    s.id == from.id -> s.copy(cards = s.cards.filterNot { it.id == card.id })
                    s.id == toStageId -> s.copy(cards = listOf(card) + s.cards)
                    else -> s
                }
            })
        })
        flash(listOf(card.id))
    }

    fun deleteCard(cardId: String) {
        val name = findCard(cardId)?.text ?: "task"
        commit("Deleted “$name”", ToastKind.INFO, transform = mapBoard { b -> b.copy(stages = b.stages.map { s -> s.copy(cards = s.cards.filterNot { it.id == cardId }) }) })
    }

    fun duplicateCard(cardId: String) {
        val copyId = newId("c")
        commit("Card duplicated", transform = mapBoard { b ->
            b.copy(stages = b.stages.map { s ->
                val i = s.cards.indexOfFirst { it.id == cardId }
                if (i < 0) s else s.copy(cards = s.cards.toMutableList().apply { add(i + 1, s.cards[i].copy(id = copyId, text = s.cards[i].text + " (copy)", createdAt = System.currentTimeMillis())) })
            })
        })
        flash(listOf(copyId))
    }

    fun findCard(id: String): Card? = board.stages.flatMap { it.cards }.firstOrNull { it.id == id }

    // ------------------------------------------------------------ stages

    fun updateStages(next: List<Stage>, message: String) = commit(message, transform = mapBoard { it.copy(stages = next) })

    // ------------------------------------------------------------ boards

    fun switchBoard(id: String) = repo.updateWorkspace { if (it.boards.any { b -> b.id == id }) it.copy(activeBoardId = id) else it }

    fun createBoard(title: String, template: String) {
        val board = (Presets.templates.firstOrNull { it.first == template }?.second ?: Presets.templates.first().second)(title.ifBlank { "Untitled board" })
        repo.updateWorkspace { Workspace(it.boards + board, board.id) }
        showToast(ToastKind.SUCCESS, "Created “${board.title}”")
    }

    fun renameBoard(id: String, title: String) {
        if (title.isBlank()) return
        repo.updateWorkspace { w -> w.copy(boards = w.boards.map { if (it.id == id) it.copy(title = title.trim()) else it }) }
    }

    fun duplicateBoard(id: String) {
        val src = ws.boards.firstOrNull { it.id == id } ?: return
        val copy = src.copy(id = newId("b"), title = "${src.title} copy", stages = src.stages.map { s -> s.copy(cards = s.cards.map { it.copy(id = newId("c")) }) })
        repo.updateWorkspace { w -> Workspace(w.boards.toMutableList().apply { add(indexOfFirst { it.id == id } + 1, copy) }, copy.id) }
        showToast(ToastKind.SUCCESS, "Board duplicated")
    }

    fun deleteBoard(id: String) {
        val b = ws.boards.firstOrNull { it.id == id } ?: return
        commit("Deleted “${b.title}”", ToastKind.INFO) { w ->
            var boards = w.boards.filterNot { it.id == id }
            if (boards.isEmpty()) boards = listOf(Presets.empty())
            Workspace(boards, if (w.activeBoardId == id) boards.first().id else w.activeBoardId)
        }
    }

    // ------------------------------------------------------------ AI

    private fun keyFor(engine: Engine) = repo.key(engine)

    val currency: String get() = com.voicekanban.app.data.currencySymbol(state.value.settings.currency)
    private fun world() = World(ws, state.value.notes, repo.vault.value, state.value.transactions, state.value.budgets, currency)
    private fun snapshot() = Snapshot(ws, state.value.notes, repo.vault.value, state.value.transactions, state.value.budgets)

    /** Applies an assistant outcome to boards, notes and vault. */
    private fun applyOutcome(out: com.voicekanban.app.ai.Outcome) {
        if (out.mutated) {
            repo.update { st -> st.copy(workspace = out.workspace, notes = out.notes ?: st.notes, transactions = out.transactions ?: st.transactions, budgets = out.budgets ?: st.budgets) }
            out.vault?.let { v -> repo.updateVault { v } }
        }
        out.showPasswordId?.let { id -> _focusVaultId.value = id; _section.value = Section.VAULT }
        if (out.touchedNoteIds.isNotEmpty()) _focusNoteId.value = out.touchedNoteIds.first()
    }

    /** Typed command on the board screen. */
    fun runCommand(text: String, onDone: (Boolean) -> Unit) {
        val input = text.trim(); if (input.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val before = ws
                val s = state.value.settings
                val r = Assistant.runCommand(getApplication(), input, world(), s, keyFor(s.engine))
                val out = r.outcome
                applyOutcome(out)
                if (out.mutated) {
                    flash(out.touchedCardIds)
                    _focusStage.value = out.focusStageId
                    val detail = (out.changes.drop(1) + out.failures.map { "⚠︎ $it" }).joinToString("\n")
                    showToast(
                        if (out.failures.isEmpty()) ToastKind.SUCCESS else ToastKind.INFO,
                        if (out.changes.size > 1) "${out.changes.size} changes · ${out.changes.first()}" else out.changes.first(),
                        (if (detail.isNotEmpty()) "$detail\n" else "") + "via ${r.engine}",
                        before.takeIf { out.notes == null && out.vault == null },
                    )
                    onDone(true)
                } else {
                    showToast(ToastKind.ERROR, r.reply.takeIf { it.isNotBlank() } ?: out.failures.firstOrNull() ?: "Nothing changed.", "via ${r.engine}")
                    onDone(false)
                }
            } catch (e: Exception) {
                showToast(ToastKind.ERROR, e.message ?: "Failed to run command.")
                onDone(false)
            } finally { _busy.value = false }
        }
    }

    /**
     * Conversation memory: earlier turns go to the model with assistant replies as the same JSON
     * the model writes, plus the changes that were really applied, so follow-ups like "move it to
     * done" or "make that high priority" resolve correctly.
     */
    private fun memory(messages: List<ChatMessage>): List<Turn> =
        messages.filterNot { it.isError || it.engine?.startsWith("Offline") == true }.takeLast(24).map { m ->
            if (m.fromUser) Turn(true, m.text)
            else Turn(false, buildJsonObject {
                put("reply", m.text)
                if (m.changes.isNotEmpty()) putJsonArray("applied") { m.changes.filterNot { it.startsWith("↩︎") }.forEach { add(it) } }
                if (m.changes.any { it.startsWith("↩︎") }) put("note", "the user undid these changes")
            }.toString())
        }

    /** Messages of the conversation a request belongs to. */
    private fun conversation(fromVoice: Boolean): List<ChatMessage> =
        if (fromVoice) state.value.voiceChat else state.value.activeSession?.messages.orEmpty()

    private fun appendMessage(fromVoice: Boolean, msg: ChatMessage) = repo.update { st ->
        if (fromVoice) st.copy(voiceChat = st.voiceChat + msg)
        else {
            val sid = st.activeSessionId?.takeIf { id -> st.sessions.any { it.id == id } }
            if (sid == null) {
                val sess = ChatSession(newId("sess"), title = if (msg.fromUser) com.voicekanban.app.data.Repository.sessionTitle(msg.text) else "New chat", messages = listOf(msg))
                st.copy(sessions = listOf(sess) + st.sessions, activeSessionId = sess.id)
            } else st.copy(sessions = st.sessions.map { ss ->
                if (ss.id != sid) ss else ss.copy(
                    messages = ss.messages + msg,
                    title = if (ss.messages.none { it.fromUser } && msg.fromUser) com.voicekanban.app.data.Repository.sessionTitle(msg.text) else ss.title,
                    updatedAt = System.currentTimeMillis(),
                )
            }.sortedByDescending { it.updatedAt })
        }
    }

    private fun mapMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) = repo.update { st ->
        st.copy(voiceChat = transform(st.voiceChat), sessions = st.sessions.map { it.copy(messages = transform(it.messages)) })
    }

    /** Sends a chat (typed) or voice request; [onReply] gets the assistant message (for speaking). */
    fun sendChat(text: String, fromVoice: Boolean = false, onReply: (ChatMessage) -> Unit = {}) {
        val input = text.trim(); if (input.isEmpty() || _thinking.value) return
        val user = ChatMessage(newId("m"), true, input, fromVoice = fromVoice)
        val history = memory(conversation(fromVoice))
        appendMessage(fromVoice, user)
        _thinking.value = true
        viewModelScope.launch {
            val reply = try {
                val before = snapshot()
                val s = state.value.settings
                val r = Assistant.run(getApplication(), input, history, world(), s, keyFor(s.engine))
                val out = r.outcome
                val shown = out.showPasswordId?.let { id -> repo.vault.value.firstOrNull { it.id == id }?.title }
                val msg = ChatMessage(
                    newId("m"), false, r.reply,
                    changes = out.changes + listOfNotNull(shown?.let { "Opened “$it” in your Vault" }),
                    failures = out.failures, engine = r.engine, fromVoice = fromVoice,
                )
                if (out.mutated) _undoable.value = _undoable.value + (msg.id to before)
                applyOutcome(out)
                // Never keep typed passwords in the transcript (or send them to the model again).
                if (out.secrets.isNotEmpty()) mapMessages { list -> list.map { m -> if (m.id == user.id) m.copy(text = out.secrets.fold(m.text) { t, sec -> t.replace(sec, "••••••") }) else m } }
                msg
            } catch (e: Exception) {
                ChatMessage(newId("m"), false, e.message ?: "Something went wrong. Please try again.", isError = true, fromVoice = fromVoice)
            }
            appendMessage(fromVoice, reply)
            _thinking.value = false
            onReply(reply)
        }
    }

    fun undoChat(messageId: String) {
        val snap = _undoable.value[messageId] ?: return
        repo.update { it.copy(workspace = snap.workspace, notes = snap.notes, transactions = snap.transactions, budgets = snap.budgets) }
        repo.updateVault { snap.vault }
        _undoable.value = _undoable.value - messageId
        val fromVoice = state.value.voiceChat.any { it.id == messageId }
        mapMessages { list -> list.map { if (it.id == messageId) it.copy(changes = it.changes.map { c -> "↩︎ $c" }) else it } }
        appendMessage(fromVoice, ChatMessage(newId("m"), false, "Undone — everything is back the way it was.", fromVoice = fromVoice))
    }

    // ------------------------------------------------------------ chat sessions

    fun newSession() {
        val cur = state.value.activeSession
        if (cur != null && cur.messages.isEmpty()) return
        val sess = ChatSession(newId("sess"))
        repo.update { it.copy(sessions = listOf(sess) + it.sessions, activeSessionId = sess.id) }
    }

    fun openSession(id: String) = repo.update { if (it.sessions.any { s -> s.id == id }) it.copy(activeSessionId = id) else it }

    fun renameSession(id: String, title: String) {
        if (title.isBlank()) return
        repo.update { st -> st.copy(sessions = st.sessions.map { if (it.id == id) it.copy(title = title.trim()) else it }) }
    }

    fun deleteSession(id: String) = repo.update { st ->
        val rest = st.sessions.filterNot { it.id == id }
        st.copy(sessions = rest, activeSessionId = if (st.activeSessionId == id) rest.firstOrNull()?.id else st.activeSessionId)
    }

    fun clearVoiceMemory() { repo.update { it.copy(voiceChat = emptyList()) } }

    fun addChatError(text: String, fromVoice: Boolean = false) = appendMessage(fromVoice, ChatMessage(newId("m"), false, text, isError = true, fromVoice = fromVoice))

    // ------------------------------------------------------------ notes

    /** AI turns scattered words into a note (new, or merged into [existing]). */
    fun composeNote(raw: String, existing: Note? = null, onDone: (Note?) -> Unit = {}) {
        val words = raw.trim(); if (words.isEmpty() && existing == null) return
        if (_composing.value) return
        _composing.value = true
        viewModelScope.launch {
            try {
                val s = state.value.settings
                val d = Assistant.composeNote(getApplication(), words, existing, s, keyFor(s.engine))
                val now = System.currentTimeMillis()
                val note = existing?.copy(title = d.title, body = d.body, color = noteColorByName(d.color) ?: existing.color, source = (existing.source + "\n" + words).trim(), updatedAt = now)
                    ?: Note(newId("n"), d.title, d.body, color = noteColorByName(d.color) ?: NOTE_COLORS[state.value.notes.size % NOTE_COLORS.size].second, source = words)
                repo.update { st -> st.copy(notes = if (existing != null) st.notes.map { if (it.id == note.id) note else it } else listOf(note) + st.notes) }
                showToast(ToastKind.SUCCESS, if (existing != null) "Note polished" else "Note created", note.title)
                onDone(note)
            } catch (e: Exception) {
                showToast(ToastKind.ERROR, e.message ?: "Couldn’t write the note.")
                onDone(null)
            } finally { _composing.value = false }
        }
    }

    fun saveNote(note: Note) = repo.update { st ->
        val n = note.copy(updatedAt = System.currentTimeMillis())
        st.copy(notes = if (st.notes.any { it.id == n.id }) st.notes.map { if (it.id == n.id) n else it } else listOf(n) + st.notes)
    }

    fun deleteNote(id: String) {
        val before = state.value.notes
        val n = before.firstOrNull { it.id == id } ?: return
        repo.update { st -> st.copy(notes = st.notes.filterNot { it.id == id }) }
        showToast(ToastKind.INFO, "Deleted “${n.title}”")
        lastDeletedNotes = before
    }
    private var lastDeletedNotes: List<Note>? = null
    fun undoDeleteNote() { lastDeletedNotes?.let { prev -> repo.update { it.copy(notes = prev) } }; lastDeletedNotes = null; _toast.value = null }

    fun toggleNotePin(id: String) = repo.update { st -> st.copy(notes = st.notes.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }) }

    // ------------------------------------------------------------ money

    fun saveTransaction(t: com.voicekanban.app.data.Transaction) = repo.update { st ->
        st.copy(transactions = if (st.transactions.any { it.id == t.id }) st.transactions.map { if (it.id == t.id) t else it } else listOf(t) + st.transactions)
    }

    fun deleteTransaction(id: String) {
        val before = state.value.transactions
        val t = before.firstOrNull { it.id == id } ?: return
        repo.update { st -> st.copy(transactions = st.transactions.filterNot { it.id == id }) }
        showToast(ToastKind.INFO, "Deleted ${t.category} ${com.voicekanban.app.data.formatMoney(t.amount, currency)}")
    }

    fun setBudget(category: String, limit: Double?) = repo.update { st ->
        st.copy(budgets = st.budgets.filterNot { it.category.equals(category, true) } + listOfNotNull(limit?.takeIf { it > 0 }?.let { com.voicekanban.app.data.Budget(category, it) }))
    }

    // ------------------------------------------------------------ workouts

    /** A workout the user asked to start from a notification (the screen asks for permissions first). */
    private val _pendingWorkout = MutableStateFlow<com.voicekanban.app.data.WorkoutType?>(null)
    val pendingWorkout = _pendingWorkout.asStateFlow()
    fun requestWorkout(t: com.voicekanban.app.data.WorkoutType?) { _pendingWorkout.value = t }

    fun startWorkout(t: com.voicekanban.app.data.WorkoutType) { _pendingWorkout.value = null; com.voicekanban.app.fitness.WorkoutTracker.start(getApplication(), t) }

    fun stopWorkout(discard: Boolean = false) {
        val s = com.voicekanban.app.fitness.WorkoutTracker.stop(discard) ?: return
        val km = s.distanceM / 1000
        showToast(ToastKind.SUCCESS, "${s.emoji} ${s.name} saved", listOfNotNull(
            com.voicekanban.app.fitness.WorkoutTracker.formatDuration(s.durationSec * 1000),
            if (km > 0.01) "%.2f km".format(km) else null,
            if (s.steps > 0) "${s.steps} steps" else null,
            "%.0f kcal".format(s.calories),
        ).joinToString(" · "))
    }

    fun saveWorkoutType(t: com.voicekanban.app.data.WorkoutType) = repo.update { st ->
        val list = st.types
        st.copy(workoutTypes = if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else list + t)
    }

    fun deleteWorkoutType(id: String) = repo.update { st -> st.copy(workoutTypes = st.types.filterNot { it.id == id && !it.builtIn }) }

    fun deleteWorkout(id: String) = repo.update { st -> st.copy(workouts = st.workouts.filterNot { it.id == id }) }

    fun setWorkoutReminder(r: com.voicekanban.app.data.WorkoutReminder) = repo.update { it.copy(workoutReminder = r) }

    // ------------------------------------------------------------ focus

    fun startFocus(minutes: Int, pin: Boolean, dnd: Boolean) = com.voicekanban.app.focus.Focus.start(getApplication(), minutes, pin, dnd)
    fun endFocus(completed: Boolean) = com.voicekanban.app.focus.Focus.end(getApplication(), completed)

    // ------------------------------------------------------------ calendar

    /** Adds a task with a deadline (from the board calendar); reminders follow [remindBeforeMin]. */
    fun addDatedCard(stageId: String, text: String, dueAt: Long, remindBeforeMin: Int?, priority: Priority = Priority.MEDIUM) {
        val t = text.trim(); if (t.isEmpty()) return
        val card = Card(newId("c"), t, priority = priority, dueAt = dueAt, remindBeforeMin = remindBeforeMin)
        commit("Added “$t” · due ${DateParser.label(dueAt)}", transform = mapBoard { b ->
            b.copy(stages = b.stages.map { if (it.id == stageId) it.copy(cards = listOf(card) + it.cards) else it })
        })
        flash(listOf(card.id))
    }

    /** Sets or clears a card's deadline and reminder (board calendar). */
    fun scheduleCard(cardId: String, dueAt: Long?, remindBeforeMin: Int?) {
        val name = findCard(cardId)?.text ?: return
        commit(if (dueAt == null) "Removed the date from “$name”" else "“$name” · due ${DateParser.label(dueAt)}", transform = mapBoard { b -> b.mapCard(cardId) { it.copy(dueAt = dueAt, remindBeforeMin = remindBeforeMin) } })
    }

    // ------------------------------------------------------------ backup

    /** Everything as one JSON file; passwords are included only when protected with [passphrase]. */
    fun exportJson(passphrase: String): String = com.voicekanban.app.data.Backup.export(state.value, repo.vault.value, passphrase.takeIf { it.isNotBlank() })

    /** Replaces all data with a backup. Returns a summary line. */
    fun restore(parsed: com.voicekanban.app.data.Backup.Parsed, vault: List<PasswordEntry>?): String {
        _undoable.value = emptyMap()
        repo.restore(parsed.state, vault)
        val st = state.value
        return "Restored ${st.workspace.boards.size} boards, ${st.notes.size} notes, ${st.transactions.size} transactions" +
            (vault?.let { ", ${it.size} logins" } ?: "") + "."
    }

    // ------------------------------------------------------------ vault

    fun saveEntry(e: PasswordEntry) = repo.updateVault { list ->
        val n = e.copy(updatedAt = System.currentTimeMillis())
        if (list.any { it.id == n.id }) list.map { if (it.id == n.id) n else it } else listOf(n) + list
    }

    fun deleteEntry(id: String) {
        val e = repo.vault.value.firstOrNull { it.id == id } ?: return
        repo.updateVault { it.filterNot { x -> x.id == id } }
        showToast(ToastKind.INFO, "Deleted “${e.title}”")
    }

    fun generatePassword(): String = com.voicekanban.app.ai.Dispatcher.generatePassword()

    // ------------------------------------------------------------ voice

    /**
     * The ElevenLabs voice for the current language. For a language without one yet, picks the
     * most-used native speaker from the ElevenLabs Voice Library, adds it to the account and
     * remembers it — so Bangla is spoken by a Bangla voice, not an English one.
     */
    suspend fun voiceForSpeech(lang: AppLanguage = state.value.settings.appLanguage): VoiceChoice? {
        state.value.settings.voiceFor(lang)?.let { return it }
        val key = repo.keys.value[com.voicekanban.app.data.Repository.ELEVENLABS].orEmpty()
        if (key.isBlank()) return null
        return runCatching {
            // A free-plan-friendly native speaker first; paid-only voices would just fail on free keys.
            val v = VoiceOutput.listLanguageVoices(key, lang.iso).firstOrNull { it.freeAllowed } ?: return null
            val id = VoiceOutput.addLibraryVoice(key, v)
            val choice = VoiceChoice(id, v.name, v.bestModel(lang.iso))
            repo.updateSettings { it.copy(voiceByLanguage = it.voiceByLanguage + (lang.code to choice)) }
            choice
        }.onFailure { android.util.Log.w("MainViewModel", "No native voice for ${lang.code}", it) }.getOrNull()
    }

    fun setVoice(lang: AppLanguage, choice: VoiceChoice) = repo.updateSettings {
        if (lang == AppLanguage.EN) it.copy(voiceId = choice.id, voiceName = choice.name, voiceByLanguage = it.voiceByLanguage + (lang.code to choice))
        else it.copy(voiceByLanguage = it.voiceByLanguage + (lang.code to choice))
    }

    // ------------------------------------------------------------ settings

    fun updateSettings(transform: (Settings) -> Settings) = repo.updateSettings(transform)
    fun setKey(name: String, value: String) = repo.setKey(name, value)

    suspend fun listModels(engine: Engine): List<String> = CloudProviders.listModels(engine, keyFor(engine))
    suspend fun testEngine(engine: Engine): String = CloudProviders.test(engine, keyFor(engine), state.value.settings.cloudModels[engine.name])


    fun completeOnboarding(userName: String, language: String, engine: Engine, key: String) {
        if (engine.needsKey && key.isNotBlank()) repo.setKey(engine.name, key)
        repo.updateSettings {
            it.copy(
                onboarded = true,
                userName = userName.trim(),
                language = language,
                engine = engine,
            )
        }
    }

    override fun onCleared() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(lockObserver)
        repo.flush()
    }

    private fun Board.mapCard(id: String, f: (Card) -> Card) =
        copy(stages = stages.map { s -> s.copy(cards = s.cards.map { if (it.id == id) f(it) else it }) })
}
