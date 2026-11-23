package com.voicekanban.app.ai

import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.NOTE_COLORS
import com.voicekanban.app.data.Note
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.Presets
import com.voicekanban.app.data.STAGE_COLORS
import com.voicekanban.app.data.Stage
import com.voicekanban.app.data.Workspace
import com.voicekanban.app.data.newId
import com.voicekanban.app.data.Budget
import com.voicekanban.app.data.Transaction
import com.voicekanban.app.data.TxType
import com.voicekanban.app.data.normalizeCategory
import com.voicekanban.app.data.noteColorByName
import java.security.SecureRandom

data class Outcome(
    val workspace: Workspace,
    val changes: List<String>,
    val failures: List<String>,
    val touchedCardIds: List<String> = emptyList(),
    val focusStageId: String? = null,
    /** Non-null when notes changed. */
    val notes: List<Note>? = null,
    /** Non-null when the vault changed. */
    val vault: List<PasswordEntry>? = null,
    /** Vault entry the user asked to see (opened in the Vault, never spoken or sent to the model). */
    val showPasswordId: String? = null,
    /** Passwords typed by the user that should be masked in the chat transcript. */
    val secrets: List<String> = emptyList(),
    val touchedNoteIds: List<String> = emptyList(),
    /** Non-null when money data changed. */
    val transactions: List<Transaction>? = null,
    val budgets: List<Budget>? = null,
) {
    val mutated: Boolean get() = changes.isNotEmpty()
}

/** Applies [Action]s to the workspace, notes and vault immutably and describes what happened. */
object Dispatcher {

    private const val MAX_STAGES = 10
    private val STOP = setOf("the", "a", "an", "to", "of", "for", "and", "on", "in", "my", "card", "task", "note", "password")

    /** Fuzzy match score of [query] against [text]; 0 = no match. */
    fun score(text: String, query: String): Int {
        val t = text.lowercase().trim(); val q = query.lowercase().trim()
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 100
        var score = 0
        if (t.contains(q) || q.contains(t)) score += 6
        val qWords = q.split(Regex("""\s+""")).filter { it.isNotEmpty() && it !in STOP }
        val tWords = t.split(Regex("""\W+""")).filter { it.isNotEmpty() }
        for (w in qWords) {
            if (w in tWords) score += 3
            else if (w.length >= 4 && tWords.any { it.startsWith(w.take(4)) }) score += 1
        }
        return score
    }

    private fun <T> best(items: List<T>, query: String, text: (T) -> String): T? =
        items.map { it to score(text(it), query) }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first

    fun findCard(board: Board, query: String): Pair<Card, Stage>? =
        best(board.stages.flatMap { s -> s.cards.map { it to s } }, query) { it.first.text }

    /** Card on the open board first, then on any other board. */
    fun findCardAnywhere(ws: Workspace, query: String): Triple<Board, Card, Stage>? {
        findCard(ws.activeBoard, query)?.let { return Triple(ws.activeBoard, it.first, it.second) }
        val all = ws.boards.filter { it.id != ws.activeBoardId }.flatMap { b -> b.stages.flatMap { s -> s.cards.map { Triple(b, it, s) } } }
        return best(all, query) { it.second.text }
    }

    fun findStage(board: Board, idOrTitle: String?): Stage? {
        if (idOrTitle.isNullOrBlank()) return null
        return board.stages.firstOrNull { it.id == idOrTitle }
            ?: board.stages.firstOrNull { it.title.equals(idOrTitle, true) }
            ?: CommandParser.resolveStage(idOrTitle, board)?.let { id -> board.stages.firstOrNull { it.id == id } }
    }

    fun findBoard(ws: Workspace, name: String?): Board? {
        if (name.isNullOrBlank()) return ws.activeBoard
        val q = name.lowercase().replace(Regex("""\b(the|board)\b"""), "").trim()
        if (q.isEmpty()) return ws.activeBoard
        return ws.boards.firstOrNull { it.title.lowercase() == q }
            ?: ws.boards.firstOrNull { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) }
            ?: ws.boards.firstOrNull { b -> q.split(" ").any { it.length > 2 && b.title.lowercase().contains(it) } }
    }

    fun findNote(notes: List<Note>, query: String): Note? = notes.firstOrNull { it.id == query } ?: best(notes, query) { it.title }
        ?: best(notes, query) { it.title + " " + it.body.take(200) }

    fun findEntry(vault: List<PasswordEntry>, query: String): PasswordEntry? = vault.firstOrNull { it.id == query }
        ?: best(vault, query) { it.title + " " + it.url.removePrefix("https://").removePrefix("www.") }

    private fun capitalize(s: String) = s.trim().replace(Regex("""["“”]"""), "").replace(Regex("""\s+"""), " ").replaceFirstChar { it.uppercase() }

    private val rng = SecureRandom()
    fun generatePassword(length: Int = 20): String {
        val sets = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#$%^&*-_=+?")
        val all = sets.joinToString("")
        val chars = sets.map { it[rng.nextInt(it.length)] } + List(length - sets.size) { all[rng.nextInt(all.length)] }
        return chars.shuffled(rng).joinToString("")
    }

    fun findTransaction(list: List<Transaction>, query: String): Transaction? {
        list.firstOrNull { it.id == query }?.let { return it }
        val q = query.lowercase()
        val amount = Regex("""\d+(\.\d+)?""").find(q)?.value?.toDoubleOrNull()
        return list.map { t ->
            var sc = score(t.note + " " + t.category, query)
            if (amount != null && t.amount == amount) sc += 4
            t to sc
        }.filter { it.second >= 2 }.maxWithOrNull(compareBy<Pair<Transaction, Int>> { it.second }.thenBy { it.first.at })?.first
    }

    fun apply(
        actions: List<Action>, start: Workspace, startNotes: List<Note> = emptyList(), startVault: List<PasswordEntry> = emptyList(),
        startTx: List<Transaction> = emptyList(), startBudgets: List<Budget> = emptyList(), currency: String = "",
    ): Outcome {
        var txs = startTx; var txChanged = false
        var budgets = startBudgets; var budgetsChanged = false
        fun money(a: Double) = com.voicekanban.app.data.formatMoney(a, currency)
        var ws = start
        var notes = startNotes; var notesChanged = false
        var vault = startVault; var vaultChanged = false
        var showId: String? = null
        val secrets = mutableListOf<String>()
        val changes = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val touched = mutableListOf<String>()
        val touchedNotes = mutableListOf<String>()
        var focus: String? = null

        fun updateBoard(id: String, transform: (Board) -> Board) {
            ws = ws.copy(boards = ws.boards.map { if (it.id == id) transform(it) else it })
        }
        fun updateActive(transform: (Board) -> Board) = updateBoard(ws.activeBoard.id, transform)

        /** Finds the card on any board; [block] gets the board it lives on. */
        fun withCard(query: String, block: (Board, Card, Stage) -> Unit) {
            val found = findCardAnywhere(ws, query)
            if (found == null) failures += "No card matches “$query”" else block(found.first, found.second, found.third)
        }
        fun onBoard(b: Board) = if (b.id == ws.activeBoardId) "" else " on “${b.title}”"

        for (a in actions) when (a) {
            is Action.Add -> {
                val board = a.board?.let { findBoard(ws, it) } ?: ws.activeBoard
                val stage = findStage(board, a.stage) ?: board.stages.firstOrNull()
                if (stage == null || a.text.isBlank()) { failures += "Could not add an empty card."; continue }
                val card = Card(newId("c"), a.text.trim(), notes = a.notes?.takeIf { it.isNotBlank() }, priority = a.priority, tags = a.tags, dueAt = a.dueAt)
                updateBoard(board.id) { b -> b.copy(stages = b.stages.map { if (it.id == stage.id) it.copy(cards = listOf(card) + it.cards) else it }) }
                touched += card.id; focus = stage.id
                changes += "Added “${card.text}” to ${stage.title}${onBoard(board)}" + (a.dueAt?.let { " · due ${DateParser.label(it)}" } ?: "")
            }
            is Action.Move -> withCard(a.card) { board, card, from ->
                val dest = findStage(board, a.to)
                if (dest == null) { failures += "No stage called “${a.to}”"; return@withCard }
                updateBoard(board.id) { b ->
                    b.copy(stages = b.stages.map { s ->
                        when (s.id) {
                            dest.id -> if (s.id == from.id) s else s.copy(cards = listOf(card) + s.cards)
                            from.id -> s.copy(cards = s.cards.filterNot { it.id == card.id })
                            else -> s
                        }
                    })
                }
                touched += card.id; focus = dest.id
                changes += "Moved “${card.text}” to ${dest.title}${onBoard(board)}"
            }
            is Action.Delete -> withCard(a.card) { board, card, _ ->
                updateBoard(board.id) { b -> b.copy(stages = b.stages.map { s -> s.copy(cards = s.cards.filterNot { it.id == card.id }) }) }
                changes += "Deleted “${card.text}”${onBoard(board)}"
            }
            is Action.SetPriority -> withCard(a.card) { board, card, stage ->
                updateBoard(board.id) { b -> b.mapCard(card.id) { it.copy(priority = a.priority) } }
                touched += card.id; focus = stage.id
                changes += "“${card.text}” is now ${a.priority.label.lowercase()} priority"
            }
            is Action.Rename -> withCard(a.card) { board, card, stage ->
                val t = a.text.trim()
                if (t.isEmpty()) return@withCard
                updateBoard(board.id) { b -> b.mapCard(card.id) { it.copy(text = t) } }
                touched += card.id; focus = stage.id
                changes += "Renamed “${card.text}” → “$t”"
            }
            is Action.SetDeadline -> withCard(a.card) { board, card, stage ->
                updateBoard(board.id) { b -> b.mapCard(card.id) { it.copy(dueAt = a.dueAt, remindBeforeMin = it.remindBeforeMin ?: 60) } }
                touched += card.id; focus = stage.id
                changes += if (a.dueAt == null) "Removed the deadline from “${card.text}”" else "“${card.text}” is due ${DateParser.label(a.dueAt)}"
            }
            is Action.UpdateCard -> withCard(a.card) { board, card, from ->
                val dest = a.to?.let { findStage(board, it) }
                if (a.to != null && dest == null) failures += "No stage called “${a.to}”"
                val updated = card.copy(
                    text = a.text?.trim()?.ifEmpty { null } ?: card.text,
                    notes = a.notes ?: card.notes,
                    priority = a.priority ?: card.priority,
                    dueAt = if (a.clearDue) null else a.dueAt ?: card.dueAt,
                    tags = a.tags ?: card.tags,
                )
                updateBoard(board.id) { b ->
                    b.copy(stages = b.stages.map { s ->
                        when {
                            dest == null || dest.id == from.id -> if (s.id == from.id) s.copy(cards = s.cards.map { if (it.id == card.id) updated else it }) else s
                            s.id == from.id -> s.copy(cards = s.cards.filterNot { it.id == card.id })
                            s.id == dest.id -> s.copy(cards = listOf(updated) + s.cards)
                            else -> s
                        }
                    })
                }
                touched += card.id; focus = dest?.id ?: from.id
                val what = buildList {
                    if (a.text != null && a.text != card.text) add("title")
                    if (a.notes != null) add("notes")
                    if (a.priority != null) add("priority")
                    if (a.dueAt != null || a.clearDue) add("deadline")
                    if (a.tags != null) add("tags")
                    if (dest != null && dest.id != from.id) add("stage → ${dest.title}")
                }
                changes += "Updated “${updated.text}”" + if (what.isNotEmpty()) " (${what.joinToString(", ")})" else ""
            }
            is Action.Clear -> {
                val stage = findStage(ws.activeBoard, a.stage)
                if (stage == null) { failures += "No stage called “${a.stage}”"; continue }
                val n = stage.cards.size
                updateActive { b -> b.copy(stages = b.stages.map { if (it.id == stage.id) it.copy(cards = emptyList()) else it }) }
                focus = stage.id
                changes += "Cleared $n card${if (n == 1) "" else "s"} from ${stage.title}"
            }
            is Action.CreateBoard -> {
                val title = capitalize(a.title.ifBlank { "New board" })
                val board = if (a.stages.isNotEmpty()) {
                    val used = mutableSetOf<Long>()
                    Board(newId("b"), title, a.stages.take(MAX_STAGES).mapIndexed { i, st ->
                        val color = STAGE_COLORS.firstOrNull { it !in used } ?: STAGE_COLORS[i % STAGE_COLORS.size]
                        used += color
                        val t = capitalize(st)
                        val id = when {
                            Regex("""^to ?do$""", RegexOption.IGNORE_CASE).matches(t) -> "todo"
                            t.contains("progress", true) -> "progress"
                            t.startsWith("review", true) -> "review"
                            t.equals("done", true) -> "done"
                            else -> newId("s")
                        }
                        Stage(id, t, color)
                    })
                } else Presets.templateByName(a.template)(title)
                ws = Workspace(ws.boards + board, board.id)
                changes += "Created board “${board.title}” with ${board.stages.size} stages"
            }
            is Action.SwitchBoard -> {
                val b = findBoard(ws, a.board)
                if (b == null) failures += "No board called “${a.board}”"
                else if (b.id != ws.activeBoardId) { ws = ws.copy(activeBoardId = b.id); changes += "Switched to “${b.title}”" }
            }
            is Action.RenameBoard -> {
                val b = findBoard(ws, a.board)
                val t = capitalize(a.title)
                if (b == null || t.isEmpty()) failures += "No board called “${a.board}”"
                else { ws = ws.copy(boards = ws.boards.map { if (it.id == b.id) it.copy(title = t) else it }); changes += "Renamed “${b.title}” to “$t”" }
            }
            is Action.DeleteBoard -> {
                val b = findBoard(ws, a.board)
                if (b == null) { failures += "No board called “${a.board}”"; continue }
                var boards = ws.boards.filterNot { it.id == b.id }
                if (boards.isEmpty()) boards = listOf(Presets.empty())
                ws = Workspace(boards, if (ws.activeBoardId == b.id) boards.first().id else ws.activeBoardId)
                changes += "Deleted board “${b.title}”"
            }
            is Action.AddStage -> {
                val b = findBoard(ws, a.board)
                val t = capitalize(a.title)
                if (b == null || t.isEmpty()) { failures += "Could not add that stage."; continue }
                if (b.stages.size >= MAX_STAGES) { failures += "“${b.title}” already has $MAX_STAGES stages."; continue }
                val color = STAGE_COLORS.firstOrNull { c -> b.stages.none { it.color == c } } ?: STAGE_COLORS.first()
                val stages = b.stages.toMutableList()
                val doneIdx = stages.indexOfFirst { it.id == "done" }
                stages.add(if (doneIdx >= 0) doneIdx else stages.size, Stage(newId("s"), t, color))
                ws = ws.copy(boards = ws.boards.map { if (it.id == b.id) it.copy(stages = stages) else it })
                changes += "Added stage “$t” to “${b.title}”"
            }
            is Action.RenameStage -> {
                val st = findStage(ws.activeBoard, a.stage)
                val t = capitalize(a.title)
                if (st == null || t.isEmpty()) { failures += "No stage called “${a.stage}”"; continue }
                updateActive { b -> b.copy(stages = b.stages.map { if (it.id == st.id) it.copy(title = t) else it }) }
                changes += "Renamed stage “${st.title}” to “$t”"
            }
            is Action.DeleteStage -> {
                val b = ws.activeBoard
                val st = findStage(b, a.stage)
                if (st == null) { failures += "No stage called “${a.stage}”"; continue }
                if (b.stages.size <= 1) { failures += "A board needs at least one stage."; continue }
                // Cards move to the first remaining stage so nothing is lost.
                val target = b.stages.first { it.id != st.id }
                updateActive { bd ->
                    bd.copy(stages = bd.stages.filterNot { it.id == st.id }.map { if (it.id == target.id) it.copy(cards = it.cards + st.cards) else it })
                }
                changes += "Deleted stage “${st.title}”" + if (st.cards.isNotEmpty()) " (moved ${st.cards.size} card${if (st.cards.size == 1) "" else "s"} to ${target.title})" else ""
            }

            // ---------------------------------------------------------------- notes
            is Action.CreateNote -> {
                val title = a.title.trim().ifBlank { "Untitled note" }
                val note = Note(newId("n"), title, a.body.trim(), color = noteColorByName(a.color) ?: NOTE_COLORS[notes.size % NOTE_COLORS.size].second)
                notes = listOf(note) + notes; notesChanged = true
                touchedNotes += note.id
                changes += "Created note “$title”"
            }
            is Action.UpdateNote -> {
                val n = findNote(notes, a.note)
                if (n == null) { failures += "No note matches “${a.note}”"; continue }
                val body = when {
                    a.body != null -> a.body.trim()
                    a.append != null -> (n.body.trimEnd() + "\n" + a.append.trim()).trim()
                    else -> n.body
                }
                val updated = n.copy(
                    title = a.title?.trim()?.ifEmpty { null } ?: n.title,
                    body = body,
                    color = noteColorByName(a.color) ?: n.color,
                    pinned = a.pinned ?: n.pinned,
                    updatedAt = System.currentTimeMillis(),
                )
                notes = notes.map { if (it.id == n.id) updated else it }; notesChanged = true
                touchedNotes += n.id
                changes += "Updated note “${updated.title}”"
            }
            is Action.DeleteNote -> {
                val n = findNote(notes, a.note)
                if (n == null) { failures += "No note matches “${a.note}”"; continue }
                notes = notes.filterNot { it.id == n.id }; notesChanged = true
                changes += "Deleted note “${n.title}”"
            }

            // ---------------------------------------------------------------- vault
            is Action.AddPassword -> {
                val title = capitalize(a.title)
                if (title.isEmpty()) { failures += "A saved login needs a name."; continue }
                val typed = a.password?.takeIf { it.isNotBlank() && !a.generate }
                val pw = typed ?: generatePassword()
                typed?.let { secrets += it }
                val e = PasswordEntry(newId("p"), title, a.username.trim(), pw, a.url.trim(), a.notes.trim())
                vault = listOf(e) + vault; vaultChanged = true
                changes += "Saved login “$title”" + if (typed == null) " with a new strong password" else ""
            }
            is Action.UpdatePassword -> {
                val e = findEntry(vault, a.entry)
                if (e == null) { failures += "No saved login matches “${a.entry}”"; continue }
                val typed = a.password?.takeIf { it.isNotBlank() && !a.generate }
                typed?.let { secrets += it }
                val pw = when { a.generate -> generatePassword(); typed != null -> typed; else -> e.password }
                val updated = e.copy(
                    title = a.title?.let { capitalize(it) }?.ifEmpty { null } ?: e.title,
                    username = a.username?.trim() ?: e.username,
                    password = pw,
                    url = a.url?.trim() ?: e.url,
                    notes = a.notes?.trim() ?: e.notes,
                    updatedAt = System.currentTimeMillis(),
                )
                vault = vault.map { if (it.id == e.id) updated else it }; vaultChanged = true
                changes += "Updated login “${updated.title}”" + when { a.generate -> " with a new strong password"; typed != null -> " (new password)"; else -> "" }
            }
            is Action.DeletePassword -> {
                val e = findEntry(vault, a.entry)
                if (e == null) { failures += "No saved login matches “${a.entry}”"; continue }
                vault = vault.filterNot { it.id == e.id }; vaultChanged = true
                changes += "Deleted login “${e.title}”"
            }
            is Action.ShowPassword -> {
                val e = findEntry(vault, a.entry)
                if (e == null) { failures += "No saved login matches “${a.entry}”"; continue }
                showId = e.id
            }
            // ---------------------------------------------------------------- money
            is Action.AddTransaction -> {
                if (a.amount <= 0.0) { failures += "The amount must be more than zero."; continue }
                val t = Transaction(newId("t"), a.amount, a.type, normalizeCategory(a.type, a.category), a.note.trim(), a.at ?: System.currentTimeMillis())
                txs = listOf(t) + txs; txChanged = true
                changes += (if (a.type == TxType.INCOME) "Logged income " else "Logged expense ") + "${money(t.amount)} · ${t.category}" + (t.note.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "")
            }
            is Action.UpdateTransaction -> {
                val t = findTransaction(txs, a.tx)
                if (t == null) { failures += "No transaction matches “${a.tx}”"; continue }
                val type = a.type ?: t.type
                val u = t.copy(
                    amount = a.amount?.takeIf { it > 0 } ?: t.amount, type = type,
                    category = a.category?.let { normalizeCategory(type, it) } ?: t.category,
                    note = a.note?.trim() ?: t.note, at = a.at ?: t.at,
                )
                txs = txs.map { if (it.id == t.id) u else it }; txChanged = true
                changes += "Updated ${u.category} ${money(u.amount)}" + (u.note.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "")
            }
            is Action.DeleteTransaction -> {
                val t = findTransaction(txs, a.tx)
                if (t == null) { failures += "No transaction matches “${a.tx}”"; continue }
                txs = txs.filterNot { it.id == t.id }; txChanged = true
                changes += "Deleted ${t.category} ${money(t.amount)}" + (t.note.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "")
            }
            is Action.SetBudget -> {
                val cat = normalizeCategory(TxType.EXPENSE, a.category)
                budgets = budgets.filterNot { it.category.equals(cat, true) } + listOfNotNull(a.limit?.takeIf { it > 0 }?.let { Budget(cat, it) })
                budgetsChanged = true
                changes += if (a.limit == null || a.limit <= 0) "Removed the $cat budget" else "Set the $cat budget to ${money(a.limit)} a month"
            }
            is Action.Unknown -> if (a.message.isNotBlank()) failures += a.message
        }
        return Outcome(
            ws, changes, failures, touched, focus,
            notes = if (notesChanged) notes else null,
            vault = if (vaultChanged) vault else null,
            showPasswordId = showId,
            secrets = secrets,
            touchedNoteIds = touchedNotes,
            transactions = if (txChanged) txs else null,
            budgets = if (budgetsChanged) budgets else null,
        )
    }

    private fun Board.mapCard(id: String, f: (Card) -> Card) =
        copy(stages = stages.map { s -> s.copy(cards = s.cards.map { if (it.id == id) f(it) else it }) })
}
