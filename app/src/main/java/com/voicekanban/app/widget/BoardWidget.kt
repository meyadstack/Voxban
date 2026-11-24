package com.voicekanban.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider
import com.voicekanban.app.MainActivity
import com.voicekanban.app.ai.Assistant
import com.voicekanban.app.ai.DateParser
import com.voicekanban.app.data.Priority
import com.voicekanban.app.data.Repository
import com.voicekanban.app.data.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val BRAND = Color(0xFF1B201C)
private val LIME = Color(0xFFEAFF87)
private val PAPER = ColorProvider(day = Color(0xFFEFF1EC), night = Color(0xFF121613))
private val INSET = ColorProvider(day = Color(0xFFF7F8F5), night = Color(0xFF1B211D))
private val INK = ColorProvider(day = Color(0xFF1B201C), night = Color(0xFFF4F6F2))
private val INK2 = ColorProvider(day = Color(0xFF525A53), night = Color(0xFFB8C0B8))

class BoardGlanceWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ws = Repository.get(context).state.value.workspace
        provideContent { GlanceTheme { WidgetContent(context, ws) } }
    }
}

private fun open(context: Context, uri: String) =
    actionStartActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)))

@Composable
private fun WidgetContent(context: Context, ws: Workspace) {
    val board = ws.activeBoard
    val next = Assistant.openCards(board).sortedWith(compareBy({ it.first.priority.rank }, { it.first.dueAt ?: Long.MAX_VALUE })).take(3)
    val ink = INK
    val sub = INK2

    Column(
        modifier = GlanceModifier.fillMaxSize().background(PAPER).cornerRadius(16.dp).padding(14.dp)
            .clickable(open(context, "voicekanban://board")),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Box(modifier = GlanceModifier.size(26.dp).background(BRAND).cornerRadius(7.dp), contentAlignment = Alignment.Center) {
                Text("⁞⁞⁞", style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(board.title, maxLines = 1, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text("${board.doneCount} of ${board.totalCards} done · ${board.progressPct}%", style = TextStyle(color = sub, fontSize = 11.sp))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        // Progress bar
        val trackWidth = LocalSize.current.width - 28.dp
        Box(modifier = GlanceModifier.fillMaxWidth().height(5.dp).background(INSET).cornerRadius(3.dp)) {
            if (board.progressPct > 0) Box(modifier = GlanceModifier.width(trackWidth * (board.progressPct / 100f)).height(5.dp).background(BRAND).cornerRadius(3.dp)) {}
        }
        Spacer(GlanceModifier.height(8.dp))
        Text("UP NEXT", style = TextStyle(color = sub, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(4.dp))
        Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
            if (next.isEmpty()) Text("All clear - say “Add …” to plan more.", style = TextStyle(color = sub, fontSize = 12.sp))
            next.forEach { (card, _) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(vertical = 2.dp)) {
                    val dot = when (card.priority) { Priority.HIGH -> Color(0xFFEF4444); Priority.MEDIUM -> Color(0xFFF59E0B); Priority.LOW -> Color(0xFF10B981) }
                    Box(modifier = GlanceModifier.size(7.dp).background(dot).cornerRadius(4.dp)) {}
                    Spacer(GlanceModifier.width(7.dp))
                    Text(card.text + (card.dueAt?.let { " · ${DateParser.relative(it)}" } ?: ""), maxLines = 1, style = TextStyle(color = ink, fontSize = 13.sp))
                }
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Primary: voice AI - opens the orb already listening.
            Row(
                modifier = GlanceModifier.defaultWeight().height(44.dp).background(LIME).cornerRadius(22.dp)
                    .clickable(open(context, "voicekanban://assistant?listen=1")),
                verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = GlanceModifier.size(22.dp).background(ColorProvider(Color(0x221B201C))).cornerRadius(11.dp), contentAlignment = Alignment.Center) {
                    Text("🎙", style = TextStyle(fontSize = 12.sp))
                }
                Spacer(GlanceModifier.width(8.dp))
                Text("Voice AI", style = TextStyle(color = ColorProvider(BRAND), fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier.height(44.dp).width(64.dp).background(INSET).cornerRadius(22.dp).clickable(open(context, "voicekanban://board")),
                contentAlignment = Alignment.Center,
            ) { Text("Open", style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) }
        }
    }
}

class BoardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BoardGlanceWidget()
}

object BoardWidget {
    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { BoardGlanceWidget().updateAll(context) } }
    }
}
