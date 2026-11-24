package com.voicekanban.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class Priority { HIGH, MEDIUM, LOW;
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
    val rank: Int get() = ordinal
    fun next(): Priority = when (this) { LOW -> MEDIUM; MEDIUM -> HIGH; HIGH -> LOW }
}

/** Minutes before the deadline to remind; 0 = at the deadline, null = no reminder. */
val REMINDER_CHOICES: List<Pair<Int?, String>> = listOf(
    null to "No reminder",
    0 to "At deadline",
    15 to "15 min before",
    60 to "1 hour before",
    24 * 60 to "1 day before",
)

/** Alert lead times for standalone calendar reminders (always notifies). */
val CAL_REMINDER_CHOICES: List<Pair<Int, String>> = listOf(
    0 to "At time",
    15 to "15 min before",
    60 to "1 hour before",
    24 * 60 to "1 day before",
)

@Serializable
enum class ReminderRepeat(val label: String) {
    NONE("Once"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
}

/**
 * A standalone calendar reminder.
 * Shown ONLY in the Calendar — never added to any Kanban board or stage.
 */
@Serializable
data class CalendarReminder(
    val id: String,
    val title: String,
    val notes: String = "",
    /** Scheduled date & time (epoch millis). */
    val at: Long,
    /** Minutes before [at] to fire the alert (0 = right on time). */
    val remindBeforeMin: Int = 0,
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun occursOn(day: java.time.LocalDate, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean {
        val start = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        if (day.isBefore(start)) return false
        return when (repeat) {
            ReminderRepeat.NONE -> day == start
            ReminderRepeat.DAILY -> true
            ReminderRepeat.WEEKLY -> day.dayOfWeek == start.dayOfWeek
            ReminderRepeat.MONTHLY -> {
                val targetDom = start.dayOfMonth.coerceAtMost(day.lengthOfMonth())
                day.dayOfMonth == targetDom
            }
        }
    }

    /** Returns the next occurrence timestamp (epoch millis) whose alarm trigger is still in the future. */
    fun nextOccurrenceAt(nowMs: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        if (done) return null
        val leadMs = remindBeforeMin * 60_000L
        if (repeat == ReminderRepeat.NONE) {
            return if (at - leadMs > nowMs - 60_000L) at else null
        }
        var zdt = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val anchorDom = zdt.dayOfMonth
        var guard = 0
        while (zdt.toInstant().toEpochMilli() - leadMs <= nowMs && guard < 1500) {
            zdt = when (repeat) {
                ReminderRepeat.DAILY -> zdt.plusDays(1)
                ReminderRepeat.WEEKLY -> zdt.plusWeeks(1)
                ReminderRepeat.MONTHLY -> {
                    val nextMonth = zdt.plusMonths(1)
                    nextMonth.withDayOfMonth(anchorDom.coerceAtMost(nextMonth.toLocalDate().lengthOfMonth()))
                }
                ReminderRepeat.NONE -> break
            }
            guard++
        }
        return zdt.toInstant().toEpochMilli()
    }

    /** Advances a repeating reminder to the occurrence strictly after [afterMs]. */
    fun advanceAfter(afterMs: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): CalendarReminder {
        if (repeat == ReminderRepeat.NONE) return copy(done = true)
        val next = nextOccurrenceAt(afterMs + remindBeforeMin * 60_000L + 60_000L, zone) ?: return copy(done = true)
        return copy(at = next, done = false)
    }
}

/** A track saved or played in the workout music player. */
@Serializable
data class WorkoutTrack(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSec: Long = 0L,
    val thumbnailUrl: String = "",
)

@Serializable
data class Card(
    val id: String,
    val text: String,
    val notes: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Deadline (epoch millis) or null. */
    val dueAt: Long? = null,
    /** Minutes before [dueAt] to notify; null disables the reminder. */
    val remindBeforeMin: Int? = 60,
)

@Serializable
data class Stage(
    val id: String,
    val title: String,
    /** ARGB colour, e.g. 0xFF6366F1. */
    val color: Long,
    val cards: List<Card> = emptyList(),
)

@Serializable
data class Board(
    val id: String,
    val title: String,
    val stages: List<Stage>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** The "done" stage: id "done" if present, otherwise the last stage. */
    val doneStage: Stage? get() = stages.firstOrNull { it.id == "done" } ?: stages.lastOrNull()
    val totalCards: Int get() = stages.sumOf { it.cards.size }
    val doneCount: Int get() = doneStage?.cards?.size ?: 0
    val progressPct: Int get() = if (totalCards == 0) 0 else (doneCount * 100 / totalCards)
    fun isDone(stageId: String) = doneStage?.id == stageId
}

@Serializable
data class Workspace(
    val boards: List<Board>,
    val activeBoardId: String,
) {
    val activeBoard: Board get() = boards.firstOrNull { it.id == activeBoardId } ?: boards.first()
}

@Serializable
enum class Engine(val label: String, val vendor: String, val needsKey: Boolean) {
    /** Retired option (kept so old saved settings still load); migrated to [ON_DEVICE]. */
    BUILT_IN("Offline rules", "Retired", false),
    ON_DEVICE("On-device AI", "Gemma / Qwen · Offline", false),
    OPENAI("ChatGPT", "OpenAI", true),
    OPENROUTER("OpenRouter", "Hundreds of models · one key", true),
    GEMINI("Gemini", "Google", true),
    CLAUDE("Claude", "Anthropic", true);

    companion object {
        /** Engines offered in onboarding and Settings (the others are retired and migrate to Gemini). */
        val selectable: List<Engine> get() = listOf(GEMINI, OPENAI, CLAUDE)
    }
}

@Serializable
enum class AppLanguage(val code: String, val label: String, val nativeName: String) {
    EN("en-US", "English", "English"),
    BN("bn-BD", "Bengali", "বাংলা"),
    HI("hi-IN", "Hindi", "हिन्दी"),
    ES("es-ES", "Spanish", "Español"),
    AR("ar-SA", "Arabic", "العربية"),
    FR("fr-FR", "French", "Français"),
    DE("de-DE", "German", "Deutsch"),
    PT("pt-BR", "Portuguese", "Português"),
    ZH("zh-CN", "Chinese", "中文"),
    JA("ja-JP", "Japanese", "日本語"),
    TR("tr-TR", "Turkish", "Türkçe"),
    KO("ko-KR", "Korean", "한국어");

    /** ISO 639-1 code, e.g. "bn". */
    val iso: String get() = code.substringBefore('-')

    fun greeting(name: String): String {
        val n = name.trim().ifBlank { when (this) {
            BN -> "বন্ধু"; HI -> "दोस्त"; ES -> "amigo"; AR -> "صديقي"; FR -> "ami"; DE -> "Freund"; PT -> "amigo"; ZH -> "朋友"; JA -> "さん"; TR -> "dostum"; KO -> "님"; EN -> "there"
        } }
        return when (this) {
            EN -> "Hello $n! How can I help with your tasks today?"
            BN -> "হ্যালো $n! আজ আপনার কাজে কীভাবে সাহায্য করতে পারি?"
            HI -> "नमस्ते $n! आज मैं आपके कामों में कैसे मदद कर सकता हूँ?"
            ES -> "¡Hola $n! ¿Cómo puedo ayudarte con tus tareas hoy?"
            AR -> "مرحباً $n! كيف يمكنني مساعدتك في مهامك اليوم؟"
            FR -> "Bonjour $n ! Comment puis-je vous aider avec vos tâches aujourd'hui ?"
            DE -> "Hallo $n! Wie kann ich dir heute bei deinen Aufgaben helfen?"
            PT -> "Olá $n! Como posso ajudar com suas tarefas hoje?"
            ZH -> "你好，$n！今天我能帮你处理什么任务？"
            JA -> "こんにちは、$n！今日はどのようにお手伝いしましょうか？"
            TR -> "Merhaba $n! Bugün görevlerinde sana nasıl yardımcı olabilirim?"
            KO -> "안녕하세요, $n! 오늘 어떤 작업을 도와드릴까요?"
        }
    }

    companion object {
        fun byCode(code: String): AppLanguage = entries.firstOrNull { it.code.equals(code, true) || it.name.equals(code, true) } ?: EN
    }
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val userName: String = "",
    val language: String = AppLanguage.EN.code,
    val engine: Engine = Engine.GEMINI,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Model id chosen per cloud engine (picked from the provider's model list). */
    val cloudModels: Map<String, String> = emptyMap(),
    /** ElevenLabs voice for English replies. */
    val voiceId: String = "cgSgspJ2msm6clMCkdW9",
    val voiceName: String = "Jessica",
    /** ElevenLabs voice per language code (e.g. "bn-BD"), so each language has a native speaker. */
    val voiceByLanguage: Map<String, VoiceChoice> = emptyMap(),
    val speakReplies: Boolean = true,
    val handsFree: Boolean = false,
    val biometricLock: Boolean = false,
    val remindersEnabled: Boolean = true,
    val quickActionsEnabled: Boolean = false,
    /** Money manager currency symbol; blank = from the phone's region. */
    val currency: String = "",
    /** Body weight for calorie estimates. */
    val weightKg: Double = 70.0,
    /** Automatically check GitHub Releases for app updates on startup. */
    val autoCheckUpdates: Boolean = true,
    /** Release tag the user chose to skip ("Later"). */
    val dismissedUpdateTag: String = "",
    /** Paired Voxban Windows PC desktop app for LAN sync. */
    val pairedDesktop: PairedDesktop? = null,
) {
    val appLanguage: AppLanguage get() = AppLanguage.byCode(language)

    /** The ElevenLabs voice for the current language, or null when none is chosen yet. */
    fun voiceFor(lang: AppLanguage = appLanguage): VoiceChoice? =
        voiceByLanguage[lang.code] ?: if (lang == AppLanguage.EN && voiceId.isNotBlank()) VoiceChoice(voiceId, voiceName) else null
}

@Serializable
data class PairedDesktop(
    val host: String,
    val port: Int = 43821,
    val token: String,
    val pcName: String = "Voxban PC",
    val lastSyncedAt: Long = 0L,
)

@Serializable
data class VoiceChoice(
    val id: String,
    val name: String,
    /** ElevenLabs model this voice is verified for in its language (e.g. eleven_v4_turbo); blank = pick by language. */
    val model: String = "",
)

@Serializable
data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val changes: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val engine: String? = null,
    val isError: Boolean = false,
    val fromVoice: Boolean = false,
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Background colours for note pages (light tint, dark tint). */
val NOTE_COLORS: List<Pair<String, Long>> = listOf(
    "Lime" to 0xFFEAFF87,
    "Mint" to 0xFFC8F2DC,
    "Sky" to 0xFFCFE6FF,
    "Lavender" to 0xFFE3DAFF,
    "Peach" to 0xFFFFDCC7,
    "Rose" to 0xFFFFD6E2,
    "Sand" to 0xFFF4EBD0,
    "Cloud" to 0xFFFFFFFF,
)

fun noteColorByName(name: String?): Long? =
    name?.trim()?.let { n -> NOTE_COLORS.firstOrNull { it.first.equals(n, true) }?.second }

@Serializable
data class Note(
    val id: String,
    val title: String,
    /** Light markdown: # headings, - bullets, - [ ] checkboxes, **bold**. */
    val body: String,
    val color: Long = NOTE_COLORS.first().second,
    val pinned: Boolean = false,
    /** The scattered words the note was made from. */
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A saved login. Lives only in encrypted storage (never in state.json). */
@Serializable
data class PasswordEntry(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class TxType { EXPENSE, INCOME }

/** Money manager categories with an emoji for the list. */
val EXPENSE_CATEGORIES: List<Pair<String, String>> = listOf(
    "Food" to "🍔", "Groceries" to "🛒", "Transport" to "🚌", "Shopping" to "🛍️", "Bills" to "🧾", "Rent" to "🏠",
    "Health" to "💊", "Education" to "📚", "Entertainment" to "🎬", "Travel" to "✈️", "Gifts" to "🎁", "Other" to "💸",
)
val INCOME_CATEGORIES: List<Pair<String, String>> = listOf(
    "Salary" to "💼", "Business" to "🏪", "Freelance" to "💻", "Investment" to "📈", "Gift" to "🎁", "Other" to "💰",
)

fun categoryEmoji(type: TxType, category: String): String =
    (if (type == TxType.INCOME) INCOME_CATEGORIES else EXPENSE_CATEGORIES).firstOrNull { it.first.equals(category, true) }?.second
        ?: if (type == TxType.INCOME) "💰" else "💸"

/** Matches free text ("groceries", "salary") to a known category, else keeps it capitalised. */
fun normalizeCategory(type: TxType, raw: String?): String {
    val r = raw?.trim().orEmpty()
    if (r.isEmpty()) return "Other"
    val list = if (type == TxType.INCOME) INCOME_CATEGORIES else EXPENSE_CATEGORIES
    return list.firstOrNull { it.first.equals(r, true) }?.first
        ?: list.firstOrNull { r.contains(it.first, true) || it.first.contains(r, true) }?.first
        ?: r.replaceFirstChar { it.uppercase() }
}

@Serializable
data class Transaction(
    val id: String,
    val amount: Double,
    val type: TxType = TxType.EXPENSE,
    val category: String = "Other",
    val note: String = "",
    /** When it happened (epoch millis). */
    val at: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** Monthly spending limit for an expense category. */
@Serializable
data class Budget(val category: String, val limit: Double)

fun currencySymbol(setting: String): String = setting.ifBlank {
    runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).symbol }.getOrDefault("$")
}

fun formatMoney(amount: Double, symbol: String): String {
    val whole = amount % 1.0 == 0.0
    val n = if (whole) "%,.0f".format(amount) else "%,.2f".format(amount)
    return "$symbol$n"
}

// ---------------------------------------------------------------- workouts

/** A kind of workout; built-in ones can be edited, user-made ones deleted. */
@Serializable
data class WorkoutType(
    val id: String,
    val name: String,
    val emoji: String,
    /** Track distance and speed with GPS. */
    val gps: Boolean = false,
    /** Count steps with the phone's step sensor. */
    val steps: Boolean = false,
    /** Energy cost (MET) used to estimate calories. */
    val met: Double = 5.0,
    /** Daily goal in minutes. */
    val goalMin: Int = 30,
    val builtIn: Boolean = false,
)

val DEFAULT_WORKOUT_TYPES: List<WorkoutType> = listOf(
    WorkoutType("walking", "Walking", "🚶", gps = true, steps = true, met = 3.5, goalMin = 30, builtIn = true),
    WorkoutType("running", "Running", "🏃", gps = true, steps = true, met = 9.8, goalMin = 25, builtIn = true),
    WorkoutType("cycling", "Cycling", "🚴", gps = true, met = 7.5, goalMin = 40, builtIn = true),
    WorkoutType("yoga", "Yoga", "🧘", met = 2.5, goalMin = 20, builtIn = true),
    WorkoutType("strength", "Strength", "🏋️", met = 5.0, goalMin = 30, builtIn = true),
    WorkoutType("hiit", "HIIT", "🔥", met = 8.0, goalMin = 20, builtIn = true),
)

@Serializable
data class WorkoutSession(
    val id: String,
    val typeId: String,
    val name: String,
    val emoji: String,
    val startedAt: Long,
    val durationSec: Long,
    val distanceM: Double = 0.0,
    val steps: Int = 0,
    val calories: Double = 0.0,
)

/** Daily "time to move" notification. Days use java.time DayOfWeek values (1 = Monday … 7 = Sunday). */
@Serializable
data class WorkoutReminder(
    val enabled: Boolean = false,
    val hour: Int = 18,
    val minute: Int = 0,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val typeId: String = "walking",
)

/** A focus (phone lock) session. */
@Serializable
data class FocusSession(
    val id: String,
    val startedAt: Long,
    val plannedMin: Int,
    val actualSec: Long,
    val completed: Boolean,
)

/** A focus session that is running right now (survives the app being closed). */
@Serializable
data class ActiveFocus(
    val startedAt: Long,
    val until: Long,
    val plannedMin: Int,
    val pinned: Boolean,
    val dnd: Boolean,
    /** Interruption filter to restore after Do Not Disturb. */
    val previousFilter: Int = 1,
)

@Serializable
data class DownloadedTrack(
    val track: WorkoutTrack,
    val localPath: String,
    val mimeType: String = "audio/webm",
    val sizeBytes: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis(),
    val exportedToMusicFolder: Boolean = false,
)

// ---------------------------------------------------------------- support tickets

@Serializable
enum class SupportCategory(val label: String, val emoji: String) {
    BUG("Bug report", "🐛"),
    FEATURE("Feature request", "✨"),
    AI_VOICE("AI & Voice", "🎙️"),
    WORKOUT_MUSIC("Workout & Music", "🎵"),
    DATA_VAULT("Backup & Vault", "🔐"),
    OTHER("General help", "💬"),
}

@Serializable
enum class SupportPriority(val label: String) {
    LOW("Low"),
    NORMAL("Normal"),
    URGENT("Urgent"),
}

@Serializable
enum class SupportTicketStatus(val label: String) {
    SENT("Sent"),
    DRAFT("Draft"),
    RESOLVED("Resolved"),
}

@Serializable
data class SupportTicket(
    val id: String,
    val category: SupportCategory = SupportCategory.BUG,
    val priority: SupportPriority = SupportPriority.NORMAL,
    val subject: String,
    val description: String,
    val contactEmail: String = "",
    val includeDiagnostics: Boolean = true,
    val status: SupportTicketStatus = SupportTicketStatus.SENT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppState(
    val workspace: Workspace,
    val settings: Settings = Settings(),
    /** Legacy single conversation; migrated into [sessions] on load. */
    val chat: List<ChatMessage> = emptyList(),
    /** Typed chat conversations, newest first. */
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    /** The rolling voice conversation (kept for the assistant's memory, not listed as a session). */
    val voiceChat: List<ChatMessage> = emptyList(),
    val notes: List<Note> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    /** Workout types; empty means the defaults. */
    val workoutTypes: List<WorkoutType> = emptyList(),
    val workouts: List<WorkoutSession> = emptyList(),
    val workoutReminder: WorkoutReminder = WorkoutReminder(),
    val focusSessions: List<FocusSession> = emptyList(),
    val activeFocus: ActiveFocus? = null,
    /** Calendar-only reminders (never shown on the Kanban board). */
    val calendarReminders: List<CalendarReminder> = emptyList(),
    /** Saved favourite tracks for the workout music player. */
    val savedWorkoutTracks: List<WorkoutTrack> = emptyList(),
    /** Offline downloaded workout music tracks stored on device. */
    val downloadedWorkoutTracks: List<DownloadedTrack> = emptyList(),
    /** Support tickets created by the user and mailed to support. */
    val supportTickets: List<SupportTicket> = emptyList(),
) {
    val types: List<WorkoutType> get() = workoutTypes.ifEmpty { DEFAULT_WORKOUT_TYPES }
    val activeSession: ChatSession? get() = sessions.firstOrNull { it.id == activeSessionId }
}

fun newId(prefix: String): String =
    "$prefix-${System.currentTimeMillis().toString(36)}-${(0..0xFFFFF).random().toString(36)}"
