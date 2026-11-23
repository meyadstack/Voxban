package com.voicekanban.app.ai

import com.voicekanban.app.data.Board
import com.voicekanban.app.data.Priority

/**
 * Built-in, offline natural-language parser for board commands.
 * Handles add / move / delete / rename / priority / deadline / clear,
 * shortcuts ("finish X", "start X") and chained commands ("A and B").
 */
object CommandParser {

    private val IGNORE = RegexOption.IGNORE_CASE

    fun resolveStage(phrase: String, board: Board): String? {
        val p = phrase.lowercase().replace(Regex("""\b(the|column|list|lane|stage)\b"""), "").trim()
        if (p.isEmpty()) return null
        val stages = board.stages
        stages.firstOrNull { it.id.equals(p, true) || it.title.equals(p, true) }?.let { return it.id }
        stages.firstOrNull { it.title.lowercase().contains(p) || p.contains(it.title.lowercase()) }?.let { return it.id }
        fun find(id: String, titleRe: Regex, fallback: () -> String?): String? =
            stages.firstOrNull { it.id == id }?.id ?: stages.firstOrNull { titleRe.containsMatchIn(it.title.lowercase()) }?.id ?: fallback()
        return when {
            Regex("progress|doing|working|started|active|wip").containsMatchIn(p) ->
                find("progress", Regex("progress|doing|active|wip|work")) { stages.getOrNull(1)?.takeIf { stages.size > 2 }?.id }
            Regex("review|qa|check|testing").containsMatchIn(p) ->
                find("review", Regex("review|qa|test|check")) { stages.getOrNull(stages.size - 2)?.takeIf { stages.size > 3 }?.id }
            Regex("done|complete|finish|shipped|closed").containsMatchIn(p) ->
                find("done", Regex("done|complete|finish|shipped|closed")) { stages.lastOrNull()?.id }
            Regex("to ?do|backlog|later|next|queue").containsMatchIn(p) -> stages.firstOrNull()?.id
            else -> null
        }
    }

    private fun clean(text: String): String = text
        .replace(Regex("""["“”‘’']"""), "")
        .replace(Regex("""^(?:the|a|an)\s+""", IGNORE), "")
        .replace(Regex("""\s+(?:card|task|item|ticket)$""", IGNORE), "")
        .replace(Regex("""^(?:card|task|item|ticket)\s+""", IGNORE), "")
        .replace(Regex("""[.!?]+$"""), "")
        .trim()

    fun detectPriority(lower: String): Priority? = when {
        Regex("""\b(high|urgent|critical|p0|p1|important|asap)\b""").containsMatchIn(lower) -> Priority.HIGH
        Regex("""\b(low|minor|trivial|p3|someday)\b""").containsMatchIn(lower) -> Priority.LOW
        Regex("""\b(medium|normal|p2)\b""").containsMatchIn(lower) -> Priority.MEDIUM
        else -> null
    }

    private fun Regex.m(s: String) = find(s)?.groupValues

    private fun parseSingle(raw: String, board: Board): Action? {
        val text = raw.trim().replace(Regex("""[.!?]+$"""), "")
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        // DEADLINE: "set deadline for X to friday", "X is due tomorrow", "remove deadline from X"
        Regex("""^(?:remove|clear|delete)\s+(?:the\s+)?(?:deadline|due date)\s+(?:for|from|on)\s+(.+)$""", IGNORE).m(text)?.let {
            return Action.SetDeadline(clean(it[1]), null)
        }
        Regex("""^(?:set|change|move)\s+(?:the\s+)?(?:deadline|due date)\s+(?:for|of|on)\s+(.+?)\s+(?:to|for|as)\s+(.+)$""", IGNORE).m(text)?.let {
            DateParser.parse(it[2])?.let { due -> return Action.SetDeadline(clean(it[1]), due) }
        }
        Regex("""^(.+?)\s+(?:is\s+)?due\s+(.+)$""", IGNORE).m(text)?.let {
            if (!Regex("""^(add|create|new|make|remind)""", IGNORE).containsMatchIn(it[1])) {
                DateParser.parse(it[2])?.let { due -> return Action.SetDeadline(clean(it[1].replace(Regex("""^(?:make|set)\s+""", IGNORE), "")), due) }
            }
        }

        // CLEAR
        Regex("""^(?:clear|empty|archive)\s+(?:all\s+)?(?:cards\s+in\s+|tasks\s+in\s+)?(.+)$""", IGNORE).m(text)?.let {
            resolveStage(it[1], board)?.let { s -> return Action.Clear(s) }
        }

        // DELETE
        Regex("""^(?:delete|remove|trash|drop|discard)\s+(.+)$""", IGNORE).m(text)?.let { return Action.Delete(clean(it[1])) }

        // RENAME
        Regex("""^(?:rename|retitle)\s+(.+?)\s+(?:to|as)\s+(.+)$""", IGNORE).m(text)?.let { return Action.Rename(clean(it[1]), clean(it[2])) }

        // PRIORITY
        Regex("""^(?:set|change|mark|make|flag)\s+(.+?)\s+(?:to|as)?\s*(high|medium|low|urgent|critical|normal|minor)(?:\s+priority)?$""", IGNORE).m(text)?.let {
            return Action.SetPriority(clean(it[1]), detectPriority(it[2].lowercase()) ?: Priority.MEDIUM)
        }
        Regex("""^(?:prioriti[sz]e|escalate)\s+(.+)$""", IGNORE).m(text)?.let { return Action.SetPriority(clean(it[1]), Priority.HIGH) }
        Regex("""^(?:deprioriti[sz]e|de-prioriti[sz]e)\s+(.+)$""", IGNORE).m(text)?.let { return Action.SetPriority(clean(it[1]), Priority.LOW) }

        // MOVE shortcuts
        val done = Regex("""^(?:finish|finished|complete|completed|close|done with|ship)\s+(.+)$""", IGNORE).m(text)
            ?: Regex("""^(?:mark|set)\s+(.+?)\s+(?:as\s+)?(?:done|complete|completed|finished)$""", IGNORE).m(text)
            ?: Regex("""^(.+?)\s+is\s+(?:done|complete|finished)$""", IGNORE).m(text)
        if (done != null) resolveStage("done", board)?.let { return Action.Move(clean(done[1]), it) }
        val start = Regex("""^(?:start|begin|start working on|work on|pick up)\s+(.+)$""", IGNORE).m(text)
            ?: Regex("""^(?:mark|set)\s+(.+?)\s+(?:as\s+)?in\s+progress$""", IGNORE).m(text)
        if (start != null) resolveStage("progress", board)?.let { return Action.Move(clean(start[1]), it) }
        Regex("""^(?:review|send)\s+(.+?)\s+(?:for|to)\s+review$""", IGNORE).m(text)?.let { m ->
            resolveStage("review", board)?.let { return Action.Move(clean(m[1]), it) }
        }

        // MOVE
        Regex("""^(?:move|put|shift|send|drag|push)\s+(.+?)\s+(?:to|into|in|over to)\s+(.+)$""", IGNORE).m(text)?.let { m ->
            resolveStage(m[2], board)?.let { return Action.Move(clean(m[1]), it) }
        }

        // ADD
        val add = Regex("""^(?:add|create|new|make|insert|log)\s+(?:a\s+|an\s+)?(?:new\s+)?(?:card|task|item|ticket|todo)?\s*(?:called|named|titled|for|:)?\s*(.+)$""", IGNORE).m(text)
            ?: Regex("""^(?:remind me to|i need to|we need to|todo:?)\s+(.+)$""", IGNORE).m(text)
        if (add != null) {
            var body = add[1].trim()
            val priority = detectPriority(lower) ?: Priority.MEDIUM
            var due: Long? = null
            DateParser.TRAILING_DUE.find(body)?.let { m ->
                DateParser.parse(m.groupValues[1])?.let { d -> due = d; body = body.substring(0, m.range.first).trim() }
            }
            body = body
                .replace(Regex("""\s*,?\s*(?:with\s+|as\s+|at\s+)?(?:a\s+)?(?:high|medium|low|urgent|critical|normal|minor)(?:\s+priority)?\s*$""", IGNORE), "")
                .replace(Regex("""^(?:high|medium|low|urgent)\s+priority\s+""", IGNORE), "")
                .replace(Regex("""^(?:urgent|critical|asap|important)\s*[:,-]?\s+""", IGNORE), "")
                .trim()
            // deadline may sit before the priority: "add X due friday with high priority"
            if (due == null) DateParser.TRAILING_DUE.find(body)?.let { m ->
                DateParser.parse(m.groupValues[1])?.let { d -> due = d; body = body.substring(0, m.range.first).trim() }
            }
            var stage: String? = null
            Regex("""^(.+?)\s+(?:to|in|into|under)\s+(?:the\s+)?([\w\s]+?)(?:\s+column|\s+stage)?$""", IGNORE).m(body)?.let { m ->
                resolveStage(m[2], board)?.let { stage = it; body = m[1].trim() }
            }
            val title = clean(body)
            if (title.isNotEmpty()) return Action.Add(title.replaceFirstChar { it.uppercase() }, stage, priority, dueAt = due)
        }
        return null
    }

    private val CHAIN_SPLIT = Regex(
        """\s*(?:;|,?\s+and then\s+|,?\s+then\s+|,\s+and\s+|\s+and\s+(?=(?:add|create|move|delete|remove|set|mark|rename|finish|complete|start|clear|prioriti[sz]e)\b))\s*""",
        IGNORE,
    )

    fun parse(transcript: String, board: Board): List<Action> {
        val raw = transcript.trim()
        if (raw.isEmpty()) return emptyList()
        val whole = parseSingle(raw, board)

        val parts = raw.split(CHAIN_SPLIT).filter { it.isNotBlank() }
        if (parts.size > 1) {
            val actions = mutableListOf<Action>()
            var lastVerb = ""
            for (part in parts) {
                val a = parseSingle(part, board) ?: if (lastVerb.isNotEmpty()) parseSingle("$lastVerb $part", board) else null
                if (a != null) { actions += a; lastVerb = part.split(Regex("""\s+""")).first() }
            }
            if (actions.size > 1) return actions
        }

        // "add milk, eggs and bread"
        Regex("""^(add|create)\s+(.+\s+and\s+.+)$""", IGNORE).find(raw)?.let { m ->
            if (whole is Action.Add && whole.dueAt == null) {
                val items = m.groupValues[2].split(Regex("""\s*,\s*|\s+and\s+""", IGNORE)).filter { it.isNotBlank() }
                if (items.size > 1 && items.all { it.split(" ").size <= 6 }) return items.mapNotNull { parseSingle("add $it", board) }
            }
        }

        return listOf(whole ?: Action.Unknown("Not sure what to do. Try “Add design review due Friday”, “Move login bug to done” or “Delete old task”."))
    }
}
