package com.voicekanban.app.data

/** Colours offered for stages (ARGB). */
val STAGE_COLORS: List<Long> = listOf(
    0xFF6366F1, // indigo
    0xFF3B82F6, // blue
    0xFF06B6D4, // cyan
    0xFF14B8A6, // teal
    0xFF10B981, // green
    0xFF84CC16, // lime
    0xFFF59E0B, // amber
    0xFFF97316, // orange
    0xFFEF4444, // red
    0xFFEC4899, // pink
    0xFFA855F7, // purple
    0xFF64748B, // slate
)

object Presets {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private fun stage(id: String, title: String, color: Long, vararg cards: Card) = Stage(id, title, color, cards.toList())

    fun sprint(now: Long = System.currentTimeMillis()) = Board(
        id = newId("b"),
        title = "Sprint Board",
        stages = listOf(
            stage(
                "todo", "To Do", 0xFF6366F1,
                Card(newId("c"), "Finalize offline storage schema", "Verify migration path on Android 15.", Priority.HIGH, listOf("mobile", "storage"), now - 6 * HOUR, dueAt = now + DAY),
                Card(newId("c"), "Record voice command demo clip", priority = Priority.MEDIUM, tags = listOf("demo"), createdAt = now - 5 * HOUR, dueAt = now + 3 * DAY),
                Card(newId("c"), "Audit touch targets at 130% font scale", priority = Priority.LOW, tags = listOf("a11y"), createdAt = now - 4 * HOUR),
            ),
            stage(
                "progress", "In Progress", 0xFFF59E0B,
                Card(newId("c"), "Test on-device model download resume", "Check pause/resume when switching Wi-Fi.", Priority.HIGH, listOf("ai", "offline"), now - 3 * HOUR, dueAt = now + 4 * HOUR),
            ),
            stage("review", "Review", 0xFF06B6D4, Card(newId("c"), "Review Android APK signing config", priority = Priority.MEDIUM, tags = listOf("release"), createdAt = now - 2 * HOUR)),
            stage("done", "Done", 0xFF10B981, Card(newId("c"), "Design adaptive light & dark tokens", priority = Priority.MEDIUM, tags = listOf("design"), createdAt = now - HOUR)),
        ),
    )

    fun launch(now: Long = System.currentTimeMillis()) = Board(
        id = newId("b"),
        title = "Product Launch",
        stages = listOf(
            stage(
                "todo", "Backlog", 0xFF6366F1,
                Card(newId("c"), "Market research & competitor notes", priority = Priority.HIGH, tags = listOf("research"), createdAt = now),
                Card(newId("c"), "Draft launch announcement post", priority = Priority.MEDIUM, tags = listOf("content"), createdAt = now, dueAt = now + 2 * DAY),
            ),
            stage("progress", "In Progress", 0xFFF59E0B, Card(newId("c"), "Build Android preview APK", priority = Priority.HIGH, tags = listOf("android"), createdAt = now)),
            stage("review", "Review", 0xFF06B6D4),
            stage("done", "Done", 0xFF10B981, Card(newId("c"), "Configure CI build pipeline", priority = Priority.LOW, tags = listOf("devops"), createdAt = now)),
        ),
    )

    fun empty(title: String = "My Board") = Board(
        id = newId("b"),
        title = title,
        stages = listOf(
            stage("todo", "To Do", 0xFF6366F1),
            stage("progress", "In Progress", 0xFFF59E0B),
            stage("review", "Review", 0xFF06B6D4),
            stage("done", "Done", 0xFF10B981),
        ),
    )

    val templates: List<Pair<String, (String) -> Board>> = listOf(
        "Blank" to { t -> empty(t) },
        "Sprint" to { t -> sprint().copy(title = t) },
        "Launch" to { t -> launch().copy(title = t) },
    )

    fun templateByName(name: String?): ((String) -> Board) = when (name?.lowercase()) {
        "sprint", "sprint board" -> { t -> sprint().copy(title = t) }
        "launch", "product launch" -> { t -> launch().copy(title = t) }
        else -> { t -> empty(t) }
    }
}
