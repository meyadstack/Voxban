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
import com.voicekanban.app.music.MusicDownloadManager
import com.voicekanban.app.music.WorkoutMusicEngine
import com.voicekanban.app.support.SupportTicketManager
import com.voicekanban.app.sync.DesktopSyncManager
import com.voicekanban.app.ui.components.rubberBandOffset
import com.voicekanban.app.ui.components.shouldTriggerSwipe
import com.voicekanban.app.ui.screens.ChangelogBlock
import com.voicekanban.app.ui.screens.parseChangelogMarkdown
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

    // ------------------------------------------------------------ Desktop QR Pairing & Music Download Helpers

    @Test
    fun parsesWindowsDesktopQrUrlAndMusicDownloadHelpers() {
        val qrUrl = "https://github.com/meyadstack/Voxban/releases/latest?voxban_pair=1&host=192.168.1.42&port=43821&token=tok_abc123&code=482910&name=My+Windows+PC"
        val parsed = DesktopSyncManager.parseQrPayload(qrUrl)
        assertNotNull(parsed)
        assertEquals("192.168.1.42", parsed!!.host)
        assertEquals(43821, parsed.port)
        assertEquals("tok_abc123", parsed.token)
        assertEquals("482910", parsed.code)
        assertEquals("My Windows PC", parsed.pcName)

        val deepLink = DesktopSyncManager.parseQrPayload("voicekanban://pair?host=10.0.0.5&port=43821&token=xyz")
        assertNotNull(deepLink)
        assertEquals("10.0.0.5", deepLink!!.host)
        assertEquals("xyz", deepLink.token)

        assertNull(DesktopSyncManager.parseQrPayload("https://example.com/random"))

        assertEquals("m4a", MusicDownloadManager.extensionForMime("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("webm", MusicDownloadManager.extensionForMime("audio/webm; codecs=\"opus\""))
        assertEquals("Eye of the Tiger - Survivor", MusicDownloadManager.sanitizeFileName("Eye of the Tiger / : * ? - Survivor"))
        assertEquals("3.5 MB", MusicDownloadManager.formatFileSize(3_670_016L))
    }

    @Test
    fun parsesGithubReleaseMarkdownChangelogCleanly() {
        val raw = """
            ### What's New in Voxban 3.4.1
            - **Fixed Phone Pairing** - `Stage.color` now syncs smoothly.
            - **Markdown Changelog** - [Release notes](https://github.com/meyadstack/Voxban) render cleanly.

            | Shot 1 | Shot 2 |
            |:---:|:---:|
            | <img src="https://raw.githubusercontent.com/meyadstack/Voxban/v3.4.0/docs/screenshots/pair-pc.png" width="200" /> | <img src="https://raw.githubusercontent.com/meyadstack/Voxban/v3.4.0/docs/screenshots/windows-desktop.png" width="420" /> |

            ---
            1. Install the latest APK.
        """.trimIndent()

        val blocks = parseChangelogMarkdown(raw)
        assertTrue(blocks.any { it is ChangelogBlock.Heading && it.level == 3 && it.text.contains("3.4.1") })
        assertTrue(blocks.any { it is ChangelogBlock.Bullet && it.text.contains("Fixed Phone Pairing") })
        assertTrue(blocks.any { it is ChangelogBlock.Images && it.urls.size == 2 })
        assertTrue(blocks.any { it is ChangelogBlock.Divider })
        assertTrue(blocks.any { it is ChangelogBlock.Numbered && it.index == "1" })
        // Must NEVER emit raw table separator pipes or raw HTML tags as paragraphs
        assertFalse(blocks.any { it is ChangelogBlock.Paragraph && (it.text.contains(":---:") || it.text.contains("<img")) })
    }

    @Test
    fun swipeMotionPhysicsRespectsDistanceVelocityAndRubberBand() {
        // Inside maxTravel -> 1:1 tracking
        assertEquals(60f, rubberBandOffset(60f, 120f), 0.001f)
        assertEquals(-60f, rubberBandOffset(-60f, 120f), 0.001f)
        // Beyond maxTravel -> damped rubber-band (never hard stop, never linear explosion)
        val stretched = rubberBandOffset(220f, 120f)
        assertTrue("Expected rubber-banded value between 120 and 160, got $stretched", stretched > 120f && stretched < 160f)

        // Distance threshold triggers even at zero velocity
        assertTrue(shouldTriggerSwipe(offsetPx = 90f, velocityPxPerSec = 0f, thresholdPx = 82f, flingVelocityPx = 860f))
        assertTrue(shouldTriggerSwipe(offsetPx = -90f, velocityPxPerSec = 0f, thresholdPx = 82f, flingVelocityPx = 860f))
        // Fast flick triggers over shorter distance in the same direction
        assertTrue(shouldTriggerSwipe(offsetPx = 40f, velocityPxPerSec = 1100f, thresholdPx = 82f, flingVelocityPx = 860f))
        // Flick in opposite direction (user changed mind and pulled back) must NOT trigger
        assertFalse(shouldTriggerSwipe(offsetPx = 40f, velocityPxPerSec = -1100f, thresholdPx = 82f, flingVelocityPx = 860f))
        // Short slow drag must NOT trigger
        assertFalse(shouldTriggerSwipe(offsetPx = 30f, velocityPxPerSec = 120f, thresholdPx = 82f, flingVelocityPx = 860f))
    }

    @Test
    fun mandatoryUpdateGateEnforcesAvailableAndDownloadingStates() {
        val rel = com.voicekanban.app.update.ReleaseInfo(
            tagName = "v3.5.0",
            version = "3.5.0",
            title = "Voxban 3.5.0",
            notes = "Mandatory update",
            apkUrl = "https://example.com/Voxban.apk",
            apkName = "Voxban-3.5.0.apk",
            apkSize = 40_000_000L,
        )
        assertTrue(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.Available(rel)))
        assertTrue(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.Downloading(rel, 100L, 1000L)))
        assertTrue(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.Error("Network drop", rel)))
        assertFalse(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.Idle))
        assertFalse(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.UpToDate(1234L)))
        assertFalse(AppUpdater.isUpdateRequired(com.voicekanban.app.update.UpdateState.Error("Offline", null)))
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
