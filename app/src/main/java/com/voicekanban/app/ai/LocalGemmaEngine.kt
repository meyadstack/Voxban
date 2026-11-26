package com.voicekanban.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.voicekanban.app.data.NOTE_COLORS
import com.voicekanban.app.data.Note
import com.voicekanban.app.data.ReminderRepeat
import com.voicekanban.app.data.Settings
import com.voicekanban.app.data.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Native LiteRT-LM ToolSet exposing every Voxban capability (Kanban boards/stages/cards,
 * notes, encrypted password vault, money manager, budgets, and calendar-only reminders)
 * directly to on-device Gemma 4 models.
 */
class VoxbanToolSet : ToolSet {
    val collected = mutableListOf<Action>()

    private fun record(action: Action, summary: String): String {
        collected += action
        return "OK: $summary"
    }

    private fun parseDue(raw: String): Long? =
        raw.trim().takeIf { it.isNotEmpty() && it.lowercase() !in setOf("none", "null", "clear") }?.let { DateParser.parse(it) }

    // ------------------------------------------------------------ Kanban cards & boards

    @Tool(description = "Add a new task card to a Kanban board stage.")
    fun add_card(
        @ToolParam(description = "Title of the task card.") text: String,
        @ToolParam(description = "Stage name such as To Do, In Progress, Review, or Done. Empty string for first stage.") column: String = "",
        @ToolParam(description = "Priority: low, medium, or high.") priority: String = "medium",
        @ToolParam(description = "Optional ISO date-time deadline like 2026-11-28T17:00, or empty string.") due: String = "",
        @ToolParam(description = "Optional card notes or details.") notes: String = "",
        @ToolParam(description = "Optional board title if not the open board.") board: String = "",
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return "Error: card title is empty"
        return record(
            Action.Add(
                text = clean,
                stage = column.trim().ifEmpty { null },
                priority = parsePriority(priority),
                dueAt = parseDue(due),
                notes = notes.trim().ifEmpty { null },
                board = board.trim().ifEmpty { null },
            ),
            "Added card \"$clean\"",
        )
    }

    @Tool(description = "Move an existing task card to another stage.")
    fun move_card(
        @ToolParam(description = "Existing card title to match.") card: String,
        @ToolParam(description = "Destination stage title such as In Progress or Done.") to: String,
    ): String = record(Action.Move(card.trim(), to.trim()), "Moved \"$card\" to $to")

    @Tool(description = "Update an existing card's title, notes, priority, deadline, or stage.")
    fun update_card(
        @ToolParam(description = "Existing card title to match.") card: String,
        @ToolParam(description = "New title or empty string to keep unchanged.") text: String = "",
        @ToolParam(description = "New notes or empty string to keep unchanged.") notes: String = "",
        @ToolParam(description = "New priority (low, medium, high) or empty string to keep unchanged.") priority: String = "",
        @ToolParam(description = "New ISO deadline, 'none' to clear, or empty string to keep unchanged.") due: String = "",
        @ToolParam(description = "Destination stage or empty string to keep unchanged.") to: String = "",
    ): String {
        val dueTrim = due.trim()
        val clearDue = dueTrim.lowercase() in setOf("none", "null", "clear", "remove")
        return record(
            Action.UpdateCard(
                card = card.trim(),
                text = text.trim().ifEmpty { null },
                notes = notes.trim().ifEmpty { null },
                priority = priority.trim().takeIf { it.isNotEmpty() }?.let { parsePriority(it) },
                dueAt = if (clearDue) null else parseDue(dueTrim),
                clearDue = clearDue,
                to = to.trim().ifEmpty { null },
            ),
            "Updated card \"$card\"",
        )
    }

    @Tool(description = "Delete a task card from the board.")
    fun delete_card(
        @ToolParam(description = "Existing card title to delete.") card: String,
    ): String = record(Action.Delete(card.trim()), "Deleted card \"$card\"")

    @Tool(description = "Clear all cards from a stage column.")
    fun clear_stage(
        @ToolParam(description = "Stage title to clear.") column: String,
    ): String = record(Action.Clear(column.trim()), "Cleared stage \"$column\"")

    @Tool(description = "Create a new Kanban board with optional comma-separated stage names.")
    fun create_board(
        @ToolParam(description = "Board title.") title: String,
        @ToolParam(description = "Optional comma-separated stage titles, or empty string for defaults.") stages: String = "",
    ): String {
        val list = stages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return record(Action.CreateBoard(title.trim(), list), "Created board \"$title\"")
    }

    @Tool(description = "Switch to another Kanban board.")
    fun switch_board(
        @ToolParam(description = "Board title to open.") board: String,
    ): String = record(Action.SwitchBoard(board.trim()), "Switched to board \"$board\"")

    @Tool(description = "Rename a Kanban board.")
    fun rename_board(
        @ToolParam(description = "New board title.") title: String,
        @ToolParam(description = "Existing board title, or empty string for current board.") board: String = "",
    ): String = record(Action.RenameBoard(board.trim().ifEmpty { null }, title.trim()), "Renamed board to \"$title\"")

    @Tool(description = "Delete a Kanban board when explicitly asked.")
    fun delete_board(
        @ToolParam(description = "Board title to delete.") board: String,
    ): String = record(Action.DeleteBoard(board.trim()), "Deleted board \"$board\"")

    @Tool(description = "Add a new stage column to a board.")
    fun add_stage(
        @ToolParam(description = "Stage title.") title: String,
        @ToolParam(description = "Optional board title, or empty string for open board.") board: String = "",
    ): String = record(Action.AddStage(title.trim(), board.trim().ifEmpty { null }), "Added stage \"$title\"")

    @Tool(description = "Rename a stage column on the active board.")
    fun rename_stage(
        @ToolParam(description = "Existing stage title.") stage: String,
        @ToolParam(description = "New stage title.") title: String,
    ): String = record(Action.RenameStage(stage.trim(), title.trim()), "Renamed stage \"$stage\" to \"$title\"")

    @Tool(description = "Delete a stage column on the active board.")
    fun delete_stage(
        @ToolParam(description = "Stage title to delete.") stage: String,
    ): String = record(Action.DeleteStage(stage.trim()), "Deleted stage \"$stage\"")

    // ------------------------------------------------------------ Notes

    @Tool(description = "Create a new note with light markdown (# heading, - bullet, - [ ] todo, **bold**).")
    fun create_note(
        @ToolParam(description = "Short note title.") title: String,
        @ToolParam(description = "Well-structured note body using light markdown.") body: String,
        @ToolParam(description = "Color: lime, mint, sky, lavender, peach, rose, sand, or cloud.") color: String = "",
    ): String = record(
        Action.CreateNote(title.trim().ifBlank { "Untitled note" }, body.trim(), color.trim().ifEmpty { null }),
        "Created note \"$title\"",
    )

    @Tool(description = "Update, append to, recolor, or pin/unpin an existing note.")
    fun update_note(
        @ToolParam(description = "Existing note title to match.") note: String,
        @ToolParam(description = "Optional new title or empty string.") title: String = "",
        @ToolParam(description = "Optional replacement body or empty string.") body: String = "",
        @ToolParam(description = "Optional text to append to the note or empty string.") append: String = "",
        @ToolParam(description = "Optional color name or empty string.") color: String = "",
        @ToolParam(description = "Optional pin state: 'true', 'false', or empty string to keep unchanged.") pinned: String = "",
    ): String = record(
        Action.UpdateNote(
            note = note.trim(),
            title = title.trim().ifEmpty { null },
            body = body.trim().ifEmpty { null },
            append = append.trim().ifEmpty { null },
            color = color.trim().ifEmpty { null },
            pinned = pinned.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null },
        ),
        "Updated note \"$note\"",
    )

    @Tool(description = "Delete an existing note.")
    fun delete_note(
        @ToolParam(description = "Existing note title to delete.") note: String,
    ): String = record(Action.DeleteNote(note.trim()), "Deleted note \"$note\"")

    // ------------------------------------------------------------ Password Vault

    @Tool(description = "Save a login in the encrypted Password Vault. Set generate=true to auto-generate a strong password.")
    fun add_password(
        @ToolParam(description = "Site or app name, e.g. Gmail or Netflix.") title: String,
        @ToolParam(description = "Username or email.") username: String = "",
        @ToolParam(description = "Password only if user provided one; otherwise empty string.") password: String = "",
        @ToolParam(description = "Website URL or empty string.") url: String = "",
        @ToolParam(description = "Optional notes.") notes: String = "",
        @ToolParam(description = "True to generate a strong random password.") generate: Boolean = false,
    ): String = record(
        Action.AddPassword(
            title = title.trim(),
            username = username.trim(),
            password = password.ifBlank { null },
            url = url.trim(),
            notes = notes.trim(),
            generate = generate || password.isBlank(),
        ),
        "Saved login \"$title\"",
    )

    @Tool(description = "Update an existing saved login in the Password Vault.")
    fun update_password(
        @ToolParam(description = "Existing login name to match.") entry: String,
        @ToolParam(description = "New username or empty string.") username: String = "",
        @ToolParam(description = "New password or empty string.") password: String = "",
        @ToolParam(description = "New URL or empty string.") url: String = "",
        @ToolParam(description = "New notes or empty string.") notes: String = "",
        @ToolParam(description = "True to generate a new strong password.") generate: Boolean = false,
    ): String = record(
        Action.UpdatePassword(
            entry = entry.trim(),
            username = username.trim().ifEmpty { null },
            password = password.ifBlank { null },
            url = url.trim().ifEmpty { null },
            notes = notes.trim().ifEmpty { null },
            generate = generate,
        ),
        "Updated login \"$entry\"",
    )

    @Tool(description = "Delete a saved login from the Password Vault.")
    fun delete_password(
        @ToolParam(description = "Existing login name to delete.") entry: String,
    ): String = record(Action.DeletePassword(entry.trim()), "Deleted login \"$entry\"")

    @Tool(description = "Privately open a saved login in the Password Vault so the user can view or copy it.")
    fun show_password(
        @ToolParam(description = "Existing login name to reveal privately.") entry: String,
    ): String = record(Action.ShowPassword(entry.trim()), "Opened login \"$entry\" in Vault")

    // ------------------------------------------------------------ Money Manager

    @Tool(description = "Log an expense or income transaction in the Money Manager.")
    fun add_transaction(
        @ToolParam(description = "Transaction type: 'expense' or 'income'.") type: String,
        @ToolParam(description = "Positive numeric amount.") amount: Double,
        @ToolParam(description = "Category like Food, Groceries, Transport, Bills, Rent, Salary, Business, Freelance, or Other.") category: String = "Other",
        @ToolParam(description = "Short note or description.") note: String = "",
        @ToolParam(description = "Optional ISO date like 2026-11-26, or empty string for now.") date: String = "",
    ): String {
        val txType = if (type.trim().equals("income", true)) TxType.INCOME else TxType.EXPENSE
        return record(
            Action.AddTransaction(amount = amount, type = txType, category = category.trim().ifEmpty { null }, note = note.trim(), at = parseDue(date)),
            "Logged ${txType.name.lowercase()} $amount ($category)",
        )
    }

    @Tool(description = "Update an existing transaction in the Money Manager.")
    fun update_transaction(
        @ToolParam(description = "Transaction ID or description to match.") transaction: String,
        @ToolParam(description = "New amount (0 to keep unchanged).") amount: Double = 0.0,
        @ToolParam(description = "New category or empty string.") category: String = "",
        @ToolParam(description = "New note or empty string.") note: String = "",
        @ToolParam(description = "New type ('expense', 'income', or empty string).") type: String = "",
        @ToolParam(description = "New ISO date or empty string.") date: String = "",
    ): String {
        val txType = when (type.trim().lowercase()) {
            "income" -> TxType.INCOME
            "expense" -> TxType.EXPENSE
            else -> null
        }
        return record(
            Action.UpdateTransaction(
                tx = transaction.trim(),
                amount = amount.takeIf { it > 0.0 },
                type = txType,
                category = category.trim().ifEmpty { null },
                note = note.trim().ifEmpty { null },
                at = parseDue(date),
            ),
            "Updated transaction \"$transaction\"",
        )
    }

    @Tool(description = "Delete a transaction from the Money Manager.")
    fun delete_transaction(
        @ToolParam(description = "Transaction ID or description to delete.") transaction: String,
    ): String = record(Action.DeleteTransaction(transaction.trim()), "Deleted transaction \"$transaction\"")

    @Tool(description = "Set or remove a monthly spending budget for a category. Pass limit=0 to remove.")
    fun set_budget(
        @ToolParam(description = "Expense category name such as Food or Groceries.") category: String,
        @ToolParam(description = "Monthly spending limit; 0 removes the budget.") limit: Double,
    ): String = record(
        Action.SetBudget(category.trim(), limit.takeIf { it > 0.0 }),
        if (limit > 0.0) "Set $category budget to $limit" else "Removed $category budget",
    )

    // ------------------------------------------------------------ Calendar-only reminders

    @Tool(description = "Create a standalone calendar reminder (shown in Calendar only, not added to Kanban boards).")
    fun add_reminder(
        @ToolParam(description = "Reminder title.") title: String,
        @ToolParam(description = "ISO local date-time like 2026-11-27T09:00.") when_iso: String,
        @ToolParam(description = "Optional details.") notes: String = "",
        @ToolParam(description = "Repeat interval: none, daily, weekly, or monthly.") repeat: String = "none",
    ): String {
        val at = parseDue(when_iso) ?: (System.currentTimeMillis() + 60 * 60_000L)
        val rep = when (repeat.trim().lowercase()) {
            "daily" -> ReminderRepeat.DAILY
            "weekly" -> ReminderRepeat.WEEKLY
            "monthly" -> ReminderRepeat.MONTHLY
            else -> ReminderRepeat.NONE
        }
        return record(Action.AddReminder(title.trim(), at, notes.trim(), rep, 0), "Added reminder \"$title\"")
    }

    @Tool(description = "Delete a standalone calendar reminder.")
    fun delete_reminder(
        @ToolParam(description = "Existing reminder title to delete.") reminder: String,
    ): String = record(Action.DeleteReminder(reminder.trim()), "Deleted reminder \"$reminder\"")
}

/**
 * Manages the on-device LiteRT-LM Gemma 4 engine lifecycle and executes conversations
 * with native tool calling across all Voxban modules.
 */
object LocalGemmaEngine {
    private const val TAG = "LocalGemmaEngine"

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelId: String? = null
    private var loadedPath: String? = null

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private fun ensureEngine(context: Context, modelId: String, path: String): Engine {
        val current = engine
        if (current != null && current.isInitialized() && loadedModelId == modelId && loadedPath == path) {
            return current
        }
        runCatching { current?.close() }
        engine = null
        loadedModelId = null
        loadedPath = null

        val cacheDir = context.cacheDir.absolutePath
        // Prefer GPU acceleration; fall back automatically to CPU on devices without compatible OpenCL/GPU support.
        val backends = listOf(Backend.GPU(), Backend.CPU())
        var lastError: Exception? = null
        for (backend in backends) {
            try {
                val cfg = EngineConfig(
                    modelPath = path,
                    backend = backend,
                    maxNumTokens = 4096,
                    cacheDir = cacheDir,
                )
                val next = Engine(cfg)
                next.initialize()
                engine = next
                loadedModelId = modelId
                loadedPath = path
                Log.i(TAG, "Initialized Gemma 4 ($modelId) on ${backend.name}")
                return next
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Gemma 4 init on ${backend.name} failed, trying fallback", e)
            }
        }
        throw IllegalStateException("Could not initialize on-device Gemma 4: ${lastError?.message ?: "unknown error"}")
    }

    fun unload() {
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null
        loadedPath = null
    }

    fun unloadIfModel(modelId: String) {
        if (loadedModelId == modelId) unload()
    }

    suspend fun runChat(
        context: Context,
        modelId: String,
        world: World,
        settings: Settings,
        history: List<Turn>,
        input: String,
    ): Pair<String, List<Action>> = withContext(Dispatchers.IO) {
        val path = Gemma4DownloadManager.readyModelPath(context, modelId)
            ?: throw IllegalStateException("Gemma 4 is not downloaded yet. Download it in Settings -> AI engine.")

        mutex.withLock {
            _loading.value = engine == null || loadedModelId != modelId
            try {
                val eng = ensureEngine(context, modelId, path)
                _loading.value = false

                val toolSet = VoxbanToolSet()
                val priorTurns = history.dropLast(1).takeLast(8).map { t ->
                    if (t.fromUser) Message.user(t.text)
                    else Message.model(Assistant.sanitizeChatReply(t.text))
                }
                val convConfig = ConversationConfig(
                    systemInstruction = Contents.of(buildOnDevicePrompt(world, settings)),
                    initialMessages = priorTurns,
                    tools = listOf(tool(toolSet)),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2),
                    automaticToolCalling = true,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                eng.createConversation(convConfig).use { conv ->
                    val response = conv.sendMessage(input)
                    val rawText = response.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                        .trim()

                    // Combine native tool calls with any JSON actions if the model emitted JSON directly.
                    val (parsedReply, jsonActions) = if (rawText.contains("{") && (rawText.contains("\"action\"") || rawText.contains("\"reply\""))) {
                        Assistant.parseLlmResponse(rawText)
                    } else {
                        Assistant.sanitizeChatReply(rawText) to emptyList()
                    }
                    val allActions = if (toolSet.collected.isNotEmpty()) toolSet.collected.toList() else jsonActions
                    parsedReply to allActions
                }
            } finally {
                _loading.value = false
            }
        }
    }

    suspend fun composeNote(
        context: Context,
        modelId: String,
        raw: String,
        existing: Note?,
        settings: Settings,
    ): Assistant.NoteDraft? = withContext(Dispatchers.IO) {
        val path = Gemma4DownloadManager.readyModelPath(context, modelId) ?: return@withContext null
        mutex.withLock {
            runCatching {
                val eng = ensureEngine(context, modelId, path)
                val lang = settings.appLanguage
                val colors = NOTE_COLORS.joinToString("|") { it.first.lowercase() }
                val sys = """
You turn scattered words into a clean note in ${lang.label} (${lang.nativeName}).
Return ONLY JSON: {"title":"short title","body":"light markdown body","color":"$colors"}
Use "# " headings, "- " bullets, "- [ ] " to-dos, and **bold**. Keep facts exact.
                """.trimIndent()
                val prompt = if (existing != null) {
                    "Rewrite and improve this note keeping all facts.\nTitle: ${existing.title}\n${existing.body}\n\nAlso include: $raw"
                } else raw
                eng.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(sys),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.25),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    ),
                ).use { conv ->
                    val msg = conv.sendMessage(prompt)
                    val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    Assistant.parseNoteDraftJson(text, raw)
                }
            }.getOrNull()
        }
    }

    private fun buildOnDevicePrompt(world: World, settings: Settings): String {
        val who = settings.userName.trim().let { if (it.isNotEmpty()) " The user's name is \"$it\"." else "" }
        val lang = settings.appLanguage
        val nowIso = DateParser.iso(System.currentTimeMillis())
        return """
You are Voxban's helpful, warm, concise on-device assistant (Gemma 4).$who
Always answer in natural plain text in ${lang.label} (${lang.nativeName}). Never output raw JSON, schemas, or code fences to the user.
Use the provided tools whenever the user asks to add, edit, move, update, pin, show, or delete Kanban tasks, boards, stages, notes, passwords, expenses, income, budgets, or calendar reminders.
You may call multiple tools in one turn. Never claim a change unless you called the matching tool. Never invent or read passwords aloud (use show_password).
Current date-time: $nowIso (${LocalDate.now().dayOfWeek}).

${Assistant.compactWorldSummary(world)}
        """.trimIndent()
    }
}
