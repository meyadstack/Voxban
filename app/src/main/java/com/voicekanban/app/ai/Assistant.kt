package com.voicekanban.app.ai

import android.content.Context
import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Card
import com.voicekanban.app.data.Engine
import com.voicekanban.app.data.NOTE_COLORS
import com.voicekanban.app.data.Note
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.Settings
import com.voicekanban.app.data.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the assistant can read and change. Passwords themselves are never put in prompts. */
data class World(
    val workspace: Workspace,
    val notes: List<Note> = emptyList(),
    val vault: List<PasswordEntry> = emptyList(),
    val transactions: List<com.voicekanban.app.data.Transaction> = emptyList(),
    val budgets: List<com.voicekanban.app.data.Budget> = emptyList(),
    val currency: String = "",
    val calendarReminders: List<com.voicekanban.app.data.CalendarReminder> = emptyList(),
)

data class AssistantResult(
    val reply: String,
    val outcome: Outcome,
    val engine: String,
)

/**
 * The conversational assistant: answers questions about the boards and turns
 * requests into [Action]s. Uses the chosen cloud / on-device model when
 * available and the built-in rules otherwise.
 */
object Assistant {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val COMMAND_VERB = Regex("""^(please\s+)?(add|create|make|new|move|put|send|delete|remove|set|mark|rename|finish|complete|start|clear|prioriti[sz]e|switch|open|go to|change|remind)\b""")

    // ------------------------------------------------------------ helpers

    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
    private fun listify(items: List<String>, max: Int = 5): String {
        val shown = items.take(max).joinToString("\n") { "• $it" }
        return if (items.size > max) "$shown\n…and ${items.size - max} more" else shown
    }

    fun openCards(board: Board): List<Pair<Card, String>> =
        board.stages.filterNot { board.isDone(it.id) }.flatMap { s -> s.cards.map { it to s.title } }

    fun nextUp(board: Board): Pair<Card, String>? {
        val now = System.currentTimeMillis()
        val idx = board.stages.associate { it.title to board.stages.indexOf(it) }
        return openCards(board).sortedWith(
            compareBy<Pair<Card, String>> { if (it.first.dueAt != null && it.first.dueAt!! < now) 0 else 1 } // overdue first
                .thenBy { it.first.priority.rank }
                .thenBy { it.first.dueAt ?: Long.MAX_VALUE }
                .thenByDescending { idx[it.second] ?: 0 }
                .thenBy { it.first.createdAt }
        ).firstOrNull()
    }

    private fun localDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun summary(board: Board): String {
        val perStage = board.stages.joinToString(" · ") { "${it.title} ${it.cards.size}" }
        val now = System.currentTimeMillis()
        val open = openCards(board)
        val overdue = open.filter { (c, _) -> c.dueAt != null && c.dueAt < now }
        val urgent = open.filter { it.first.priority == Priority.HIGH }
        var s = "“${board.title}” has ${plural(board.totalCards, "card")}, ${board.progressPct}% done.\n$perStage"
        if (overdue.isNotEmpty()) s += "\n\nOverdue:\n" + listify(overdue.map { "${it.first.text} (${DateParser.relative(it.first.dueAt!!)})" }, 4)
        if (urgent.isNotEmpty()) s += "\n\nHigh priority still open:\n" + listify(urgent.map { it.first.text }, 4)
        return s
    }

    private fun offlineAnswer(input: String, ws: Workspace, settings: Settings): String? {
        val q = input.lowercase().trim().trimEnd('?', '!', '.')
        if (COMMAND_VERB.containsMatchIn(q)) return null
        val board = ws.activeBoard
        val now = System.currentTimeMillis()
        val who = settings.userName.trim().let { if (it.isNotEmpty()) ", $it" else "" }

        if (Regex("""^(hi|hello|hey|yo|good (morning|afternoon|evening)|হ্যালো|নমস্কার|नमस्ते|hola|مرحبا|bonjour|hallo|olá|你好|こんにちは|merhaba|안녕하세요)\b""").containsMatchIn(q))
            return settings.appLanguage.greeting(settings.userName)
        if (Regex("""^(thanks|thank you|cheers|great|awesome|nice|cool|ok(ay)?)\b""").containsMatchIn(q)) return "Anytime$who! What’s next?"
        if (Regex("""what can you do|^help\b|how do (i|you)|commands""").containsMatchIn(q)) return listOf(
            "I can manage your boards by voice or text. Try:",
            "• “Create a board called Wedding with stages Ideas, Booked, Paid”",
            "• “Add call the venue due Friday with high priority”",
            "• “Move call the venue to booked”",
            "• “Set deadline for report to tomorrow at 5pm”",
            "• “What’s due this week?” or “What’s overdue?”",
            "• “What should I work on next?” or “Give me a summary”",
        ).joinToString("\n")
        if (Regex("""(list|show|what are|which).*(boards)|how many boards|my boards""").containsMatchIn(q))
            return "You have ${plural(ws.boards.size, "board")}:\n" + listify(ws.boards.map { "${it.title}${if (it.id == ws.activeBoardId) " (open)" else ""} - ${plural(it.totalCards, "card")}" }, 10)
        if (Regex("""summary|summari[sz]e|status|overview|how (am i|are we) doing|progress report""").containsMatchIn(q)) return summary(board)
        if (Regex("""overdue|late|missed|past due""").containsMatchIn(q)) {
            val items = openCards(board).filter { it.first.dueAt?.let { d -> d < now } == true }
            return if (items.isEmpty()) "Nothing is overdue on “${board.title}”. 🎉" else "Overdue:\n" + listify(items.map { "${it.first.text} - ${DateParser.relative(it.first.dueAt!!)}" }, 8)
        }
        Regex("""(due|deadlines?)\s*(today|tomorrow|this week|next week|soon)?""").find(q)?.takeIf { q.contains("due") || q.contains("deadline") }?.let { m ->
            val today = LocalDate.now()
            val range = when (m.groupValues[2]) {
                "today" -> today..today
                "tomorrow" -> today.plusDays(1)..today.plusDays(1)
                "next week" -> today.plusDays(7)..today.plusDays(14)
                else -> today..today.plusDays(7)
            }
            val items = openCards(board).filter { it.first.dueAt?.let { d -> localDate(d) in range || d < now } == true }
                .sortedBy { it.first.dueAt }
            val label = m.groupValues[2].ifEmpty { "this week" }
            return if (items.isEmpty()) "Nothing due $label on “${board.title}”." else "Due $label:\n" + listify(items.map { "${it.first.text} - ${DateParser.label(it.first.dueAt!!)}" }, 8)
        }
        if (Regex("""what should i (do|work on)|what('?s| is) next|next task|where (do|should) i start|focus on""").containsMatchIn(q)) {
            val n = nextUp(board) ?: return "Nothing open on “${board.title}”. Add something with “Add …”."
            val due = n.first.dueAt?.let { " It’s due ${DateParser.label(it)} (${DateParser.relative(it)})." } ?: ""
            return "I’d do “${n.first.text}” next - it’s ${n.first.priority.label.lowercase()} priority in ${n.second}.$due"
        }
        if (Regex("""(high|urgent|important|critical) (priority|tasks|cards|items)|what('?s| is) (urgent|important)""").containsMatchIn(q)) {
            val items = openCards(board).filter { it.first.priority == Priority.HIGH }
            return if (items.isEmpty()) "No open high-priority cards. 🎉" else "High priority on “${board.title}”:\n" + listify(items.map { "${it.first.text} (${it.second})" }, 8)
        }
        if (Regex("""how many (tasks|cards|items)|count""").containsMatchIn(q))
            return "“${board.title}” has ${plural(board.totalCards, "card")}: " + board.stages.joinToString(", ") { "${it.title} ${it.cards.size}" } + "."
        Regex("""(?:what(?:'s| is| are)?|show|list)(?: me)?(?: the)? (?:tasks |cards |items )?(?:in|under|on)? ?(.+?)(?: column| stage| list)?$""").find(q)?.let { m ->
            val id = CommandParser.resolveStage(m.groupValues[1], board)
            board.stages.firstOrNull { it.id == id }?.let { s ->
                return if (s.cards.isEmpty()) "${s.title} is empty." else "${s.title} (${s.cards.size}):\n" + listify(s.cards.map { it.text + if (it.priority == Priority.HIGH) " - high" else "" }, 8)
            }
        }
        return null
    }

    private fun offlineWorkspaceActions(input: String, ws: Workspace): List<Action>? {
        val raw = input.trim().trimEnd('.', '!', '?')
        val i = RegexOption.IGNORE_CASE
        Regex("""^(?:please\s+)?(?:create|make|start|set up|new|add)\s+(?:a\s+|an\s+)?(?:new\s+)?board(?:\s+(?:called|named|titled|for))?\s+(.+?)(?:\s+(?:with|using)\s+(?:the\s+)?(?:stages?|columns?|lists?)\s+(.+))?$""", i).find(raw)?.let { m ->
            var title = m.groupValues[1]
            var template: String? = null
            Regex("""^(.+?)\s+(?:from|using)\s+(?:the\s+)?(sprint|launch|product launch)\s*(?:template)?$""", i).find(title)?.let { t ->
                title = t.groupValues[1]; template = t.groupValues[2]
            }
            val stages = m.groupValues[2].takeIf { it.isNotBlank() }?.split(Regex("""\s*,\s*|\s+and\s+|\s+then\s+""", i))?.filter { it.isNotBlank() } ?: emptyList()
            return listOf(Action.CreateBoard(title, stages, template))
        }
        Regex("""^(?:switch|go|change|move)\s+(?:over\s+)?to\s+(?:the\s+)?(.+?)(?:\s+board)?$|^open\s+(?:the\s+)?(.+?)(?:\s+board)?$""", i).find(raw)?.let { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val b = Dispatcher.findBoard(ws, name)
            if (b != null && b.id != ws.activeBoardId) return listOf(Action.SwitchBoard(name))
        }
        Regex("""^rename\s+(?:this\s+|the\s+)?board(?:\s+(.+?))?\s+to\s+(.+)$""", i).find(raw)?.let { return listOf(Action.RenameBoard(it.groupValues[1].ifEmpty { null }, it.groupValues[2])) }
        Regex("""^delete\s+(?:the\s+)?(.+?)\s+board$|^delete\s+board\s+(.+)$""", i).find(raw)?.let { return listOf(Action.DeleteBoard(it.groupValues[1].ifEmpty { it.groupValues[2] })) }
        Regex("""^(?:add|create|new)\s+(?:a\s+)?(?:new\s+)?(?:stage|column|list)\s+(?:called\s+|named\s+)?(.+?)(?:\s+to\s+(?:the\s+)?(.+?)\s+board)?$""", i).find(raw)?.let {
            return listOf(Action.AddStage(it.groupValues[1], it.groupValues[2].ifEmpty { null }))
        }
        return null
    }

    // ------------------------------------------------------------ LLM prompt

    private fun context(ws: Workspace, compact: Boolean = false): String {
        var budget = if (compact) 40 else 150
        val boards = if (compact) listOf(ws.activeBoard) else ws.boards
        val others = if (compact && ws.boards.size > 1) "\nOther boards: " + ws.boards.filter { it.id != ws.activeBoardId }.joinToString(", ") { "\"${it.title}\"" } else ""
        return boards.joinToString("\n\n") { b ->
            val head = "Board \"${b.title}\"${if (b.id == ws.activeBoardId) " [OPEN]" else ""}"
            val stages = b.stages.joinToString("\n") { s ->
                val cards = s.cards.take(budget.coerceAtLeast(0)).joinToString("\n") { c ->
                    val extra = listOfNotNull(
                        c.priority.label.lowercase(),
                        c.dueAt?.let { "due ${DateParser.iso(it)}" },
                        c.tags.takeIf { it.isNotEmpty() }?.let { "tags ${it.joinToString("/")}" },
                        if (!compact) c.notes?.takeIf { it.isNotBlank() }?.let { "notes: ${it.take(80).replace("\n", " ")}" } else null,
                    ).joinToString(", ")
                    "    - \"${c.text}\" ($extra)"
                }
                budget -= s.cards.size
                "  Stage \"${s.title}\" (id=${s.id}, ${s.cards.size} cards)\n$cards"
            }
            "$head\n$stages"
        } + others
    }

    private fun notesContext(notes: List<Note>, compact: Boolean): String {
        if (notes.isEmpty()) return "(no notes yet)"
        return notes.take(if (compact) 8 else 30).joinToString("\n") { n ->
            val color = NOTE_COLORS.firstOrNull { it.second == n.color }?.first ?: "custom"
            "  - \"${n.title}\" [$color${if (n.pinned) ", pinned" else ""}]: ${n.body.replace("\n", " / ").take(if (compact) 60 else 160)}"
        }
    }

    private fun moneyContext(w: World): String {
        val zone = ZoneId.systemDefault()
        val month = java.time.YearMonth.now()
        val inMonth = w.transactions.filter { java.time.YearMonth.from(Instant.ofEpochMilli(it.at).atZone(zone)) == month }
        val income = inMonth.filter { it.type == com.voicekanban.app.data.TxType.INCOME }.sumOf { it.amount }
        val spent = inMonth.filter { it.type == com.voicekanban.app.data.TxType.EXPENSE }.sumOf { it.amount }
        fun m(a: Double) = com.voicekanban.app.data.formatMoney(a, w.currency)
        val byCat = inMonth.filter { it.type == com.voicekanban.app.data.TxType.EXPENSE }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
        val budgets = if (w.budgets.isEmpty()) "none" else w.budgets.joinToString("; ") { b -> "${b.category} ${m(byCat[b.category] ?: 0.0)} of ${m(b.limit)}" }
        val recent = w.transactions.sortedByDescending { it.at }.take(25).joinToString("\n") { t ->
            "  - [${t.id}] ${DateParser.iso(t.at).take(10)} ${t.type.name.lowercase()} ${m(t.amount)} ${t.category}${if (t.note.isNotBlank()) " \"${t.note}\"" else ""}"
        }.ifBlank { "  (no transactions yet)" }
        return "Currency ${w.currency}. This month ($month): income ${m(income)}, spent ${m(spent)}, balance ${m(income - spent)}.\n" +
            "Spent by category: ${byCat.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${m(it.value)}" }.ifBlank { "nothing yet" }}\n" +
            "Budgets: $budgets\nRecent transactions:\n$recent"
    }

    private fun remindersContext(reminders: List<com.voicekanban.app.data.CalendarReminder>): String =
        if (reminders.isEmpty()) "(no calendar reminders)"
        else reminders.filterNot { it.done }.sortedBy { it.at }.take(25).joinToString("\n") { r ->
            "  - \"${r.title}\" at ${DateParser.iso(r.at)}" + (if (r.repeat != com.voicekanban.app.data.ReminderRepeat.NONE) " (${r.repeat.label})" else "") + (if (r.notes.isNotBlank()) " · ${r.notes.take(60)}" else "")
        }.ifBlank { "(all calendar reminders done)" }

    private fun vaultContext(vault: List<PasswordEntry>): String =
        if (vault.isEmpty()) "(vault is empty)"
        else vault.take(60).joinToString("\n") { e -> "  - \"${e.title}\"" + listOfNotNull(e.username.ifBlank { null }?.let { "user $it" }, e.url.ifBlank { null }).joinToString(", ", " (", ")").takeIf { e.username.isNotBlank() || e.url.isNotBlank() }.orEmpty() }

    fun systemPrompt(world: World, settings: Settings = Settings(), compact: Boolean = false): String {
        val ws = world.workspace
        val who = settings.userName.trim().let { if (it.isNotEmpty()) " The user's name is \"$it\" - address them naturally by name when appropriate." else "" }
        val lang = settings.appLanguage.let { " Always write \"reply\" in ${it.label} (${it.nativeName}, locale ${it.code}); keep card, note and login names as the user said them." }
        val colors = NOTE_COLORS.joinToString("|") { it.first.lowercase() }
        return """
You are Voxban's assistant: a warm, witty, concise voice assistant inside an app with Kanban boards, notes, a money manager and a password vault. The user talks to you like a friend.$who$lang You remember the whole conversation below - use it to resolve "it", "that one", "the same", etc. You can also chat freely - jokes, advice, planning, general questions - answer those in "reply" with no actions.

Reply with ONLY a JSON object: {"reply": "...", "actions": [...]}. "reply" is what you say back: 1-4 short sentences, plain text, may use • bullets.
The app changes NOTHING unless you put an action in "actions". Whenever the user asks to create, add, change, update, edit, move, rename, complete, delete or remove anything, you MUST include the matching action(s) - several if needed. Never claim a change you didn't include. Use "actions": [] for questions and chat.
Earlier assistant turns may contain "applied": the changes that already happened. Don't repeat them unless asked again.

Board / card actions (cards are found on any board by their text):
{"action":"add","text":"card title","column":"<stage>","priority":"low|medium|high","due":"<ISO date-time>","notes":"<optional details>","board":"<optional board title>"}
{"action":"update","card":"existing card text","text":"<new title>","notes":"...","priority":"...","due":"<ISO or none>","tags":["..."],"to":"<stage>"}  (include only fields that change)
{"action":"move","card":"existing card text","to":"<stage id or title>"}
{"action":"delete","card":"existing card text"}
{"action":"clear","column":"<stage>"}
{"action":"create_board","title":"...","stages":[{"title":"..."}]}
{"action":"switch_board","board":"<board title>"}
{"action":"rename_board","board":"<board title>","title":"<new title>"}
{"action":"delete_board","board":"<board title>"}  (only when explicitly asked)
{"action":"add_stage","title":"...","board":"<optional board title>"}
{"action":"rename_stage","stage":"<stage>","title":"<new title>"}
{"action":"delete_stage","stage":"<stage>"}

Note actions - when asked to make a note from scattered words, write it yourself: a short title and a tidy, well-structured body using light markdown ("# " heading lines, "- " bullets, "- [ ] " to-dos, **bold**). Fix spelling, group related items, add nothing the user didn't imply:
{"action":"create_note","title":"...","body":"...","color":"$colors"}
{"action":"update_note","note":"<existing note title>","title":"<new title>","body":"<full new body>","append":"<text to add at the end>","color":"...","pinned":true}
{"action":"delete_note","note":"<existing note title>"}

Password vault actions - you can see login names but never the passwords. Use "generate": true to create a strong password. To show one, use show_password (the app opens it privately; never read passwords aloud):
{"action":"add_password","title":"<site or app>","username":"...","password":"<only if the user said it>","url":"...","generate":true}
{"action":"update_password","entry":"<existing login>","username":"...","password":"...","url":"...","notes":"...","generate":true}
{"action":"delete_password","entry":"<existing login>"}
{"action":"show_password","entry":"<existing login>"}

Money manager actions (amounts are plain numbers in the user's currency; "date" is optional ISO, default now):
{"action":"add_transaction","type":"expense|income","amount":250,"category":"${com.voicekanban.app.data.EXPENSE_CATEGORIES.joinToString("|") { it.first }} (or for income: ${com.voicekanban.app.data.INCOME_CATEGORIES.joinToString("|") { it.first }})","note":"lunch with Sam","date":"..."}
{"action":"update_transaction","transaction":"<id in [brackets] or description>","amount":300,"category":"...","note":"...","type":"...","date":"..."}
{"action":"delete_transaction","transaction":"<id in [brackets] or description>"}
{"action":"set_budget","category":"Food","limit":5000}  (limit 0 removes it)

Calendar reminder actions (shown ONLY in the Calendar - NOT added to Kanban boards; use when the user asks for a reminder rather than a board task):
{"action":"add_reminder","title":"...","when":"<ISO date-time>","notes":"<optional>","repeat":"none|daily|weekly|monthly"}
{"action":"delete_reminder","reminder":"<existing reminder title>"}

Dates: now is ${DateParser.iso(System.currentTimeMillis())} (${java.time.LocalDate.now().dayOfWeek}). Use ISO local date-times like 2026-11-28T17:00 for "due"; default time 17:00.
${if (compact) "" else "Keep replies short enough to read aloud."}

Boards:
${context(ws, compact)}

Calendar reminders (calendar-only):
${remindersContext(world.calendarReminders)}

Notes:
${notesContext(world.notes, compact)}

Vault logins:
${vaultContext(world.vault)}

Money:
${moneyContext(world)}
""".trimIndent()
    }

    private fun parseLlm(raw: String): Pair<String, List<Action>> = try {
        val el = json.parseToJsonElement(CloudProviders.stripFences(raw))
        if (el is JsonArray) "" to el.mapNotNull { actionFromJson(it) }
        else {
            val o = el.jsonObject
            val reply = (o["reply"] ?: o["message"] ?: o["response"])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
            val actions = when (val a = o["actions"] ?: o["action"]) {
                is JsonArray -> a.mapNotNull { actionFromJson(it) }
                is kotlinx.serialization.json.JsonObject -> listOfNotNull(actionFromJson(a))
                else -> if (o.containsKey("action")) listOfNotNull(actionFromJson(o)) else emptyList()
            }
            reply to actions
        }
    } catch (e: Exception) {
        raw.trim().take(600) to emptyList()
    }

    /** Providers want alternating user/assistant turns that start with the user: merge repeats. */
    private fun normalize(turns: List<Turn>): List<Turn> {
        val out = mutableListOf<Turn>()
        for (t in turns.dropWhile { !it.fromUser }) {
            if (t.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.fromUser == t.fromUser) out[out.size - 1] = Turn(t.fromUser, last.text + "\n" + t.text)
            else out += t
        }
        return out
    }

    // ------------------------------------------------------------ entry point

    suspend fun run(context: Context, input: String, history: List<Turn>, world: World, settings: Settings, apiKey: String): AssistantResult {
        val ws = world.workspace
        val turns = normalize(history.takeLast(24) + Turn(true, input))
        fun apply(actions: List<Action>) = Dispatcher.apply(actions, ws, world.notes, world.vault, world.transactions, world.budgets, world.currency, world.calendarReminders)
        val noChange = Outcome(ws, emptyList(), emptyList())

        when (settings.engine) {
            Engine.OPENAI, Engine.GEMINI, Engine.CLAUDE -> if (apiKey.isNotBlank()) {
                val raw = CloudProviders.complete(settings.engine, apiKey, settings.cloudModels[settings.engine.name], systemPrompt(world, settings), turns)
                val (reply, actions) = parseLlm(raw)
                val out = apply(actions)
                return AssistantResult(reply.ifBlank { if (out.mutated) "Done." else "Okay." }, out, settings.engine.label)
            }
            else -> Unit
        }

        // Offline rules: used only until a model is downloaded or a key is added.
        val engine = "Offline · add a ${settings.engine.label} key in Settings"
        offlineAnswer(input, ws, settings)?.let { return AssistantResult(it, noChange, engine) }
        offlineWorkspaceActions(input, ws)?.let { acts ->
            val out = apply(acts)
            val reply = when {
                out.changes.firstOrNull()?.startsWith("Created board") == true -> "${out.changes.first()}. It’s open now - tell me what to add."
                out.mutated -> out.changes.joinToString(". ") + "."
                else -> out.failures.firstOrNull() ?: "I couldn’t do that."
            }
            return AssistantResult(reply, out, engine)
        }
        val out = apply(CommandParser.parse(input, ws.activeBoard))
        if (out.mutated) return AssistantResult(if (out.changes.size == 1) "${out.changes.first()}." else "Done - ${plural(out.changes.size, "change")}.", out, engine)
        val fail = out.failures.firstOrNull()
        return AssistantResult(
            if (fail != null && !fail.startsWith("Not sure")) "$fail. Want me to try something else?"
            else "I can only do simple board commands offline. Add a Gemini, ChatGPT or Claude key in Settings so I can chat, take notes and manage your money and vault.",
            out, engine,
        )
    }

    /** Board-screen typed commands: same engines, but only actions matter. */
    suspend fun runCommand(context: Context, input: String, world: World, settings: Settings, apiKey: String): AssistantResult {
        val parsed = CommandParser.parse(input, world.workspace.activeBoard)
        val rulesOutcome = Dispatcher.apply(parsed, world.workspace, world.notes, world.vault, world.transactions, world.budgets, world.currency, world.calendarReminders)
        // Rules handle clear commands instantly; use AI for anything they can't parse.
        if (rulesOutcome.mutated) return AssistantResult(rulesOutcome.changes.joinToString("\n"), rulesOutcome, "Quick command")
        return run(context, input, emptyList(), world, settings, apiKey)
    }

    // ------------------------------------------------------------ note writer

    data class NoteDraft(val title: String, val body: String, val color: String?)

    /** Turns scattered words into a tidy note with the chosen AI; falls back to simple local formatting. */
    suspend fun composeNote(context: Context, raw: String, existing: Note?, settings: Settings, apiKey: String): NoteDraft {
        val lang = settings.appLanguage
        val system = """
You turn scattered words, fragments and voice dictation into a beautiful, well-organised note. Write in ${lang.label} (${lang.nativeName}) unless the words are clearly in another language.
Reply with ONLY JSON: {"title":"short title","body":"note body","color":"${NOTE_COLORS.joinToString("|") { it.first.lowercase() }}"}
Body rules: light markdown only - "# " section headings, "- " bullets, "- [ ] " to-dos, **bold** for key words. Fix spelling and grammar, group related items under headings, turn intentions into to-dos, keep dates and numbers exact. Add nothing the user didn't imply. Pick a colour that suits the mood/topic.
""".trimIndent()
        val prompt = if (existing != null) "Rewrite and improve this note, keeping all its facts.\nTitle: ${existing.title}\n${existing.body}\n\nAlso include: $raw" else raw
        val text: String? = runCatching {
            if (apiKey.isNotBlank() && settings.engine in Engine.selectable)
                CloudProviders.complete(settings.engine, apiKey, settings.cloudModels[settings.engine.name], system, listOf(Turn(true, prompt)))
            else null
        }.getOrNull()
        text?.let { t ->
            runCatching {
                val o = json.parseToJsonElement(CloudProviders.stripFences(t)).jsonObject
                val body = o["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (body.isNotEmpty()) return NoteDraft(o["title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: localTitle(raw), body, o["color"]?.jsonPrimitive?.contentOrNull)
            }
        }
        return localNote(if (existing != null) existing.body + "\n" + raw else raw, existing?.title)
    }

    private fun localTitle(raw: String): String =
        raw.trim().split(Regex("""[\n,.;]""")).firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("""\s+"""))?.take(5)?.joinToString(" ")
            ?.replaceFirstChar { it.uppercase() } ?: "Quick note"

    /** No AI available: split fragments into tidy bullets / to-dos. */
    private fun localNote(raw: String, title: String?): NoteDraft {
        val todo = Regex("""^(call|buy|pay|email|send|book|finish|fix|check|remember|remind|meet|get|pick up|schedule|write|submit)\b""", RegexOption.IGNORE_CASE)
        val parts = raw.split(Regex("""\n|[,;]|\.(\s|$)|\band then\b""")).map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotBlank() }
        val body = parts.joinToString("\n") { p ->
            val clean = p.replaceFirstChar { it.uppercase() }
            if (todo.containsMatchIn(p)) "- [ ] $clean" else "- $clean"
        }
        return NoteDraft(title ?: localTitle(raw), body, null)
    }
}
