package com.voicekanban.app.ai

import com.voicekanban.app.data.Priority
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Everything the parser / assistant / LLMs can ask the app to do. */
sealed interface Action {
    // Card actions (open board first, then any board)
    data class Add(val text: String, val stage: String? = null, val priority: Priority = Priority.MEDIUM, val tags: List<String> = emptyList(), val dueAt: Long? = null, val notes: String? = null, val board: String? = null) : Action
    data class Move(val card: String, val to: String) : Action
    data class Delete(val card: String) : Action
    data class SetPriority(val card: String, val priority: Priority) : Action
    data class Rename(val card: String, val text: String) : Action
    data class SetDeadline(val card: String, val dueAt: Long?) : Action
    /** Edit any card fields at once; null = leave unchanged. [clearDue] removes the deadline. */
    data class UpdateCard(
        val card: String, val text: String? = null, val notes: String? = null, val priority: Priority? = null,
        val dueAt: Long? = null, val clearDue: Boolean = false, val tags: List<String>? = null, val to: String? = null,
    ) : Action
    data class Clear(val stage: String) : Action

    // Workspace actions
    data class CreateBoard(val title: String, val stages: List<String> = emptyList(), val template: String? = null) : Action
    data class SwitchBoard(val board: String) : Action
    data class RenameBoard(val board: String?, val title: String) : Action
    data class DeleteBoard(val board: String) : Action
    data class AddStage(val title: String, val board: String? = null) : Action
    data class RenameStage(val stage: String, val title: String) : Action
    data class DeleteStage(val stage: String) : Action

    // Notes
    data class CreateNote(val title: String, val body: String, val color: String? = null) : Action
    data class UpdateNote(val note: String, val title: String? = null, val body: String? = null, val append: String? = null, val color: String? = null, val pinned: Boolean? = null) : Action
    data class DeleteNote(val note: String) : Action

    // Password vault
    data class AddPassword(val title: String, val username: String = "", val password: String? = null, val url: String = "", val notes: String = "", val generate: Boolean = false) : Action
    data class UpdatePassword(val entry: String, val title: String? = null, val username: String? = null, val password: String? = null, val url: String? = null, val notes: String? = null, val generate: Boolean = false) : Action
    data class DeletePassword(val entry: String) : Action
    data class ShowPassword(val entry: String) : Action

    // Money manager
    data class AddTransaction(val amount: Double, val type: com.voicekanban.app.data.TxType, val category: String?, val note: String = "", val at: Long? = null) : Action
    data class UpdateTransaction(val tx: String, val amount: Double? = null, val type: com.voicekanban.app.data.TxType? = null, val category: String? = null, val note: String? = null, val at: Long? = null) : Action
    data class DeleteTransaction(val tx: String) : Action
    data class SetBudget(val category: String, val limit: Double?) : Action

    data class Unknown(val message: String) : Action
}

private fun JsonObject.num(vararg keys: String): Double? {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        p.contentOrNull?.replace(Regex("""[^0-9.\-]"""), "")?.toDoubleOrNull()?.let { return it }
    }
    return null
}

private fun txType(s: String?): com.voicekanban.app.data.TxType? = when (s?.lowercase()?.trim()) {
    "income", "earning", "earned", "received", "credit", "in" -> com.voicekanban.app.data.TxType.INCOME
    "expense", "spend", "spent", "paid", "debit", "out", "cost" -> com.voicekanban.app.data.TxType.EXPENSE
    else -> null
}

fun parsePriority(s: String?): Priority = when (s?.lowercase()?.trim()) {
    "high", "urgent", "critical" -> Priority.HIGH
    "low", "minor" -> Priority.LOW
    else -> Priority.MEDIUM
}

private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

private fun JsonObject.list(k: String): List<String>? = when (val v = this[k]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    else -> null
}

/** Converts the JSON action objects returned by cloud / on-device models (tolerates common key aliases). */
fun actionFromJson(el: JsonElement): Action? {
    val o = el as? JsonObject ?: return null
    val dueRaw = o.str("due", "dueAt", "deadline", "date", "when")
    val clearDue = dueRaw != null && dueRaw.lowercase() in setOf("none", "null", "remove", "clear", "")
    val due = if (clearDue) null else dueRaw?.let { DateParser.parse(it) }
    val name = o.str("action", "type", "op", "name")?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
    return when (name) {
        "add", "add_card", "create", "create_card", "add_task", "create_task" -> Action.Add(
            text = o.str("text", "title", "card", "task") ?: return null,
            stage = o.str("column", "stage", "to", "list"),
            priority = parsePriority(o.str("priority")),
            tags = o.list("tags") ?: emptyList(),
            dueAt = due,
            notes = o.str("notes", "description", "details"),
            board = o.str("board"),
        )
        "move", "move_card", "move_task" -> Action.Move(o.str("card", "task", "text") ?: return null, o.str("to", "column", "stage") ?: return null)
        "delete", "delete_card", "remove", "remove_card", "delete_task" -> Action.Delete(o.str("card", "task", "text", "title") ?: return null)
        "priority", "set_priority" -> Action.SetPriority(o.str("card", "task", "text") ?: return null, parsePriority(o.str("priority")))
        "rename", "rename_card" -> Action.Rename(o.str("card", "task") ?: return null, o.str("text", "title", "new_text", "new_title") ?: return null)
        "deadline", "due", "set_deadline", "set_due" -> Action.SetDeadline(o.str("card", "task", "text") ?: return null, due)
        "update", "update_card", "edit", "edit_card", "update_task" -> Action.UpdateCard(
            card = o.str("card", "task") ?: return null,
            text = o.str("text", "title", "new_text", "new_title"),
            notes = o.str("notes", "description", "details"),
            priority = o.str("priority")?.let { parsePriority(it) },
            dueAt = due, clearDue = clearDue,
            tags = o.list("tags"),
            to = o.str("to", "column", "stage"),
        )
        "clear" -> Action.Clear(o.str("column", "stage") ?: return null)
        "create_board", "add_board", "new_board" -> Action.CreateBoard(
            title = o.str("title", "board", "name") ?: "New board",
            stages = (o["stages"] as? JsonArray)?.mapNotNull { s ->
                (s as? JsonObject)?.str("title", "name") ?: (s as? JsonPrimitive)?.contentOrNull
            } ?: emptyList(),
            template = o.str("template"),
        )
        "switch_board", "open_board" -> Action.SwitchBoard(o.str("board", "title") ?: return null)
        "rename_board" -> Action.RenameBoard(o.str("board"), o.str("title", "new_title") ?: return null)
        "delete_board", "remove_board" -> Action.DeleteBoard(o.str("board", "title") ?: return null)
        "add_stage", "create_stage", "add_column" -> Action.AddStage(o.str("title", "stage", "column") ?: return null, o.str("board"))
        "rename_stage", "rename_column" -> Action.RenameStage(o.str("stage", "column") ?: return null, o.str("title", "new_title") ?: return null)
        "delete_stage", "remove_stage", "delete_column" -> Action.DeleteStage(o.str("stage", "column", "title") ?: return null)

        "create_note", "add_note", "new_note" -> Action.CreateNote(
            title = o.str("title") ?: "Untitled note",
            body = o.str("body", "content", "text") ?: "",
            color = o.str("color"),
        )
        "update_note", "edit_note", "append_note" -> Action.UpdateNote(
            note = o.str("note", "title_old", "find") ?: o.str("title") ?: return null,
            title = if (o.containsKey("note")) o.str("title", "new_title") else o.str("new_title"),
            body = o.str("body", "content"),
            append = o.str("append", "add"),
            color = o.str("color"),
            pinned = o.bool("pinned"),
        )
        "delete_note", "remove_note" -> Action.DeleteNote(o.str("note", "title") ?: return null)

        "add_password", "save_password", "create_password", "new_password" -> Action.AddPassword(
            title = o.str("title", "site", "service", "entry") ?: return null,
            username = o.str("username", "user", "email", "login") ?: "",
            password = o.str("password"),
            url = o.str("url", "website") ?: "",
            notes = o.str("notes") ?: "",
            generate = o.bool("generate") == true,
        )
        "update_password", "edit_password", "change_password" -> Action.UpdatePassword(
            entry = o.str("entry", "site", "service") ?: o.str("title") ?: return null,
            title = if (o.containsKey("entry")) o.str("title", "new_title") else o.str("new_title"),
            username = o.str("username", "user", "email", "login"),
            password = o.str("password"),
            url = o.str("url", "website"),
            notes = o.str("notes"),
            generate = o.bool("generate") == true,
        )
        "delete_password", "remove_password" -> Action.DeletePassword(o.str("entry", "title", "site", "service") ?: return null)
        "show_password", "get_password", "reveal_password", "open_password" -> Action.ShowPassword(o.str("entry", "title", "site", "service") ?: return null)
        "add_transaction", "add_expense", "add_income", "log_expense", "log_income", "spend", "expense", "income" -> {
            val type = txType(o.str("type", "kind")) ?: if (name.contains("income")) com.voicekanban.app.data.TxType.INCOME else com.voicekanban.app.data.TxType.EXPENSE
            Action.AddTransaction(o.num("amount", "value") ?: return null, type, o.str("category"), o.str("note", "description", "text", "title") ?: "", due)
        }
        "update_transaction", "edit_transaction" -> Action.UpdateTransaction(
            tx = o.str("transaction", "tx", "id", "match") ?: return null,
            amount = o.num("amount", "value"), type = txType(o.str("type", "kind")), category = o.str("category"),
            note = o.str("note", "description"), at = due,
        )
        "delete_transaction", "remove_transaction" -> Action.DeleteTransaction(o.str("transaction", "tx", "id", "match") ?: return null)
        "set_budget", "budget" -> Action.SetBudget(o.str("category") ?: return null, o.num("limit", "amount", "budget"))
        "unknown" -> Action.Unknown(o.str("message") ?: "")
        else -> null
    }
}
