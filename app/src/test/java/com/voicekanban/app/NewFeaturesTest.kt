package com.voicekanban.app

import com.voicekanban.app.ai.Action
import com.voicekanban.app.ai.Dispatcher
import com.voicekanban.app.data.AppState
import com.voicekanban.app.data.CalendarReminder
import com.voicekanban.app.data.Presets
import com.voicekanban.app.data.ReminderRepeat
import com.voicekanban.app.data.SupportCategory
import com.voicekanban.app.data.SupportPriority
import com.voicekanban.app.data.SupportTicket
import com.voicekanban.app.data.SupportTicketStatus
import com.voicekanban.app.data.Workspace
import com.voicekanban.app.music.WorkoutMusicEngine
import com.voicekanban.app.support.SupportTicketManager
import com.voicekanban.app.update.AppUpdater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NewFeaturesTest {

    // ------------------------------------------------------------ Calendar Reminders

    @Test
    fun calendarReminderDoesNotTouchKanbanBoard() {
        val board = Presets.sprint()
        val ws = Workspace(listOf(board), board.id)
        val initialCards = board.totalCards
        val at = LocalDate.of(2026, 12, 1).atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val outcome = Dispatcher.apply(
            actions = listOf(Action.AddReminder("Dentist appointment", at, "Bring X-ray", ReminderRepeat.NONE)),
            start = ws,
            startReminders = emptyList(),
        )

        assertTrue(outcome.mutated)
        assertEquals(initialCards, outcome.workspace.activeBoard.totalCards)
        assertEquals(1, outcome.calendarReminders?.size)
        assertEquals("Dentist appointment", outcome.calendarReminders?.first()?.title)
    }

    @Test
    fun calendarReminderRecurrenceRules() {
        val zone = ZoneId.of("UTC")
        val jan31 = LocalDate.of(2026, 1, 31).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()

        val once = CalendarReminder("r1", "One-off", at = jan31, repeat = ReminderRepeat.NONE)
        assertTrue(once.occursOn(LocalDate.of(2026, 1, 31), zone))
        assertFalse(once.occursOn(LocalDate.of(2026, 2, 1), zone))

        val daily = once.copy(repeat = ReminderRepeat.DAILY)
        assertFalse(daily.occursOn(LocalDate.of(2026, 1, 30), zone))
        assertTrue(daily.occursOn(LocalDate.of(2026, 2, 15), zone))

        val weekly = once.copy(repeat = ReminderRepeat.WEEKLY)
        assertTrue(weekly.occursOn(LocalDate.of(2026, 2, 7), zone))
        assertFalse(weekly.occursOn(LocalDate.of(2026, 2, 8), zone))

        val monthly = once.copy(repeat = ReminderRepeat.MONTHLY)
        // Clamps 31st to Feb 28 in non-leap year 2026
        assertTrue(monthly.occursOn(LocalDate.of(2026, 2, 28), zone))
        assertTrue(monthly.occursOn(LocalDate.of(2026, 3, 31), zone))
        assertFalse(monthly.occursOn(LocalDate.of(2026, 3, 28), zone))
    }

    // ------------------------------------------------------------ GitHub Release Updater

    @Test
    fun semverComparisonWorksAcrossFormats() {
        assertTrue(AppUpdater.isNewerVersion("v3.3.0", "3.2.1"))
        assertTrue(AppUpdater.isNewerVersion("3.2.2", "3.2.1"))
        assertTrue(AppUpdater.isNewerVersion("4.0.0", "3.9.9"))
        assertTrue(AppUpdater.isNewerVersion("3.3.0", "3.3.0-beta.1"))
        assertFalse(AppUpdater.isNewerVersion("v3.3.0", "3.3.0"))
        assertFalse(AppUpdater.isNewerVersion("v3.2.1", "3.3.0"))
        assertFalse(AppUpdater.isNewerVersion("3.3.0-rc1", "3.3.0"))
    }

    @Test
    fun parsesGitHubReleasePayload() {
        val sample = """
        {
          "tag_name": "v3.4.0",
          "name": "Voxban 3.4.0",
          "draft": false,
          "body": "Added calendar reminders and workout music.",
          "html_url": "https://github.com/meyadstack/Voxban/releases/tag/v3.4.0",
          "published_at": "2026-11-24T00:00:00Z",
          "assets": [
            {
              "name": "Voxban-3.4.0.apk",
              "size": 35123456,
              "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "browser_download_url": "https://github.com/meyadstack/Voxban/releases/download/v3.4.0/Voxban-3.4.0.apk"
            }
          ]
        }
        """.trimIndent()

        val rel = AppUpdater.parseReleaseJson(sample)
        assertNotNull(rel)
        assertEquals("3.4.0", rel!!.version)
        assertEquals("Voxban-3.4.0.apk", rel.apkName)
        assertEquals(35123456L, rel.apkSize)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", rel.sha256)

        val draft = sample.replace("\"draft\": false", "\"draft\": true")
        assertNull(AppUpdater.parseReleaseJson(draft))
    }

    // ------------------------------------------------------------ Workout Music Parser

    @Test
    fun parsesMusicSearchAndDuration() {
        val sampleSearch = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "dQw4w9WgXcQ" },
                          "flexColumns": [
                            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Eye of the Tiger" } ] } } },
                            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Song" }, { "text": " • " }, { "text": "Survivor" }, { "text": " • " }, { "text": "4:05" } ] } } }
                          ],
                          "thumbnail": {
                            "musicThumbnailRenderer": {
                              "thumbnail": { "thumbnails": [ { "url": "https://i.ytimg.com/small.jpg", "width": 60 }, { "url": "https://i.ytimg.com/large.jpg", "width": 544 } ] }
                            }
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val tracks = WorkoutMusicEngine.parseSearchTracks(Json.parseToJsonElement(sampleSearch).jsonObject)
        assertEquals(1, tracks.size)
        assertEquals("dQw4w9WgXcQ", tracks[0].videoId)
        assertEquals("Eye of the Tiger", tracks[0].title)
        assertEquals("Survivor", tracks[0].artist)
        assertEquals(245L, tracks[0].durationSec)
        assertEquals("https://i.ytimg.com/large.jpg", tracks[0].thumbnailUrl)
        assertEquals("4:05", WorkoutMusicEngine.formatDuration(245_000L))
    }

    // ------------------------------------------------------------ Support Ticket System

    @Test
    fun supportTicketFormatsEmailAndTargetsOwnerInbox() {
        assertEquals("abdullahmeyad7@gmail.com", SupportTicketManager.SUPPORT_EMAIL)
        val id = SupportTicketManager.generateTicketId(LocalDate.of(2026, 11, 24))
        assertTrue("Expected VB-2611-XXXX format, got $id", Regex("^VB-2611-[A-Z2-9]{4}$").matches(id))

        val ticket = SupportTicket(
            id = id,
            category = SupportCategory.BUG,
            priority = SupportPriority.URGENT,
            subject = "Audio pause issue",
            description = "Music paused when switching tabs.",
            contactEmail = "alex@example.com",
            includeDiagnostics = true,
            status = SupportTicketStatus.SENT,
            createdAt = 1_795_500_000_000L,
        )

        val subject = SupportTicketManager.formatEmailSubject(ticket)
        assertEquals("[Voxban Support #$id] [Bug report] Audio pause issue", subject)

        val followUpSubject = SupportTicketManager.formatEmailSubject(ticket, isFollowUp = true)
        assertTrue(followUpSubject.startsWith("Re: [Voxban Support #$id]"))

        val body = SupportTicketManager.formatEmailBody(
            ticket = ticket,
            userName = "Alex",
            diagnostics = "- App: Voxban 3.3.0 (build 9)",
            zone = ZoneId.of("UTC"),
        )
        assertTrue(body.contains("Ticket ID:  #$id"))
        assertTrue(body.contains("Category:   Bug report"))
        assertTrue(body.contains("Priority:   Urgent"))
        assertTrue(body.contains("Alex · alex@example.com"))
        assertTrue(body.contains("Music paused when switching tabs."))
        assertTrue(body.contains("App: Voxban 3.3.0"))

        val mailto = SupportTicketManager.buildMailtoUriString(ticket, "Alex")
        assertTrue(mailto.startsWith("mailto:abdullahmeyad7@gmail.com?subject="))
        assertTrue(mailto.contains("body="))

        val webGmail = SupportTicketManager.buildWebGmailUrl(ticket, "Alex")
        assertTrue(webGmail.startsWith("https://mail.google.com/mail/?view=cm&fs=1&to=abdullahmeyad7%40gmail.com"))

        // Verify AppState serialization round-trip preserves supportTickets
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val state = AppState(workspace = Workspace(listOf(Presets.empty()), "b"), supportTickets = listOf(ticket))
        val decoded = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), state))
        assertEquals(1, decoded.supportTickets.size)
        assertEquals(ticket.id, decoded.supportTickets.first().id)
        assertEquals(SupportPriority.URGENT, decoded.supportTickets.first().priority)
    }

    @Test
    fun liveMusicSearchAndStreamExtractionWhenEnabled() {
        if (System.getenv("VOXBAN_LIVE_NET_TEST") != "true") return
        kotlinx.coroutines.runBlocking {
            val results = WorkoutMusicEngine.searchSongs("high energy workout music")
            assertTrue("Expected search results, got ${results.size}", results.isNotEmpty())
            val first = results.first()
            println("LIVE_MUSIC_FIRST: ${first.videoId} | ${first.title} | ${first.artist} | ${first.durationSec}s")
            val radio = WorkoutMusicEngine.fetchRadioQueue(first.videoId)
            println("LIVE_MUSIC_RADIO: ${radio.size} tracks")
            val stream = WorkoutMusicEngine.extractAudioStream(first.videoId)
            println("LIVE_MUSIC_STREAM: client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType} urlPresent=${stream.audioUrl.isNotBlank()} sabr=${stream.sabrBootstrap != null} len=${stream.contentLengthBytes}")
            assertTrue("Expected either direct audioUrl or sabrBootstrap", stream.audioUrl.isNotBlank() || stream.sabrBootstrap != null)
        }
    }
}
