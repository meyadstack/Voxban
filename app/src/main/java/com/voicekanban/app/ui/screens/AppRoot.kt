package com.voicekanban.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.AppLanguage
import com.voicekanban.app.data.Engine
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.Section
import com.voicekanban.app.ui.components.LogoMark
import com.voicekanban.app.ui.theme.VK

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: MainViewModel, locked: Boolean, onUnlock: () -> Unit) {
    val app by vm.state.collectAsState()
    val section by vm.section.collectAsState()
    val c = VK.colors

    if (locked) { LockScreen(onUnlock); return }
    if (!app.settings.onboarded) { OnboardingScreen(vm); return }

    // imePadding on the whole content: inputs always sit right above the keyboard.
    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                section,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { dir * it / 8 })
                        .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -dir * it / 8 })
                },
                label = "section",
            ) { s ->
                when (s) {
                    Section.BOARD -> BoardScreen(vm)
                    Section.ASSISTANT -> AssistantScreen(vm)
                    Section.NOTES -> NotesScreen(vm)
                    Section.MONEY -> MoneyScreen(vm)
                    Section.VAULT -> VaultScreen(vm)
                }
            }
        }
        // Tab bar steps aside while typing.
        if (!WindowInsets.isImeVisible) TabBar(section) { vm.setSection(it) }
    }
}

@Composable
private fun TabBar(section: Section, onChange: (Section) -> Unit) {
    val c = VK.colors
    Surface(color = c.background) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(CircleShape).background(c.raised).border(1.dp, c.border, CircleShape).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Triple(Section.BOARD, "Tasks", Icons.Rounded.GridView),
                Triple(Section.ASSISTANT, "Voice AI", Icons.Rounded.GraphicEq),
                Triple(Section.NOTES, "Notes", Icons.AutoMirrored.Rounded.StickyNote2),
                Triple(Section.MONEY, "Money", Icons.Rounded.AccountBalanceWallet),
                Triple(Section.VAULT, "Vault", Icons.Rounded.Lock),
            ).forEach { (s, label, icon) ->
                val active = s == section
                val bg by animateColorAsState(if (active) c.limeTile else Color.Transparent, tween(220), label = "tabBg")
                val fg by animateColorAsState(if (active) c.onLime else c.inkSecondary, tween(220), label = "tabFg")
                val w by animateFloatAsState(if (active) 2.1f else 1f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "tabW")
                Row(
                    Modifier.weight(w).height(48.dp).clip(CircleShape).background(bg)
                        .clickable { onChange(s) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp))
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = VK.colors
    Column(Modifier.fillMaxSize().background(c.background).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LogoMark(80.dp)
        Text("Voxban is locked", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 20.dp))
        Text("Unlock with your fingerprint, face or screen lock.", color = c.inkSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Button(onClick = onUnlock, Modifier.padding(top = 28.dp), shape = CircleShape) {
            Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingScreen(vm: MainViewModel) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) } // 0 = Name, 1 = Language, 2 = Model selection
    var userName by rememberSaveable { mutableStateOf(app.settings.userName) }
    var language by rememberSaveable { mutableStateOf(app.settings.language.ifBlank { AppLanguage.EN.code }) }
    var engine by rememberSaveable { mutableStateOf(Engine.GEMINI) }
    var key by rememberSaveable { mutableStateOf("") }

    val icons: Map<Engine, ImageVector> = mapOf(
        Engine.BUILT_IN to Icons.Rounded.Bolt, Engine.ON_DEVICE to Icons.Rounded.Memory,
        Engine.OPENAI to Icons.Rounded.AutoAwesome, Engine.GEMINI to Icons.Rounded.Psychology,
        Engine.CLAUDE to Icons.Rounded.ChatBubbleOutline, Engine.OPENROUTER to Icons.Rounded.Hub,
    )
    val blurbs = mapOf(
        Engine.BUILT_IN to "Works instantly with no key or download. Handles commands, deadlines and board management.",
        Engine.OPENAI to "Cloud model via your OpenAI API key.",
        Engine.GEMINI to "Cloud model via your Google AI Studio key (has a free tier).",
        Engine.CLAUDE to "Anthropic’s most capable model via your API key.",
    )

    Column(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Top header with step pills
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(c.raised).clickable { step-- },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink, modifier = Modifier.size(20.dp))
                }
            } else {
                LogoMark(44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Voxban", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = c.ink)
                Text("Step ${step + 1} of 3", fontSize = 12.sp, color = c.inkSecondary)
            }
            // Step indicator pills
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { idx ->
                    val w by animateDpAsState(if (idx == step) 28.dp else 10.dp, spring(dampingRatio = 0.75f, stiffness = 300f), label = "dotW")
                    val dotBg by animateColorAsState(
                        when {
                            idx == step -> c.limeTile
                            idx < step -> c.ink
                            else -> c.sageTile
                        },
                        tween(220), label = "dotBg",
                    )
                    Box(Modifier.height(10.dp).width(w).clip(CircleShape).background(dotBg))
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (targetState > initialState) {
                    (fadeIn(tween(260)) + slideInHorizontally(tween(280)) { it / 4 })
                        .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { -it / 4 })
                } else {
                    (fadeIn(tween(260)) + slideInHorizontally(tween(280)) { -it / 4 })
                        .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 4 })
                }
            },
            label = "onboardStep",
        ) { currentStep ->
            when (currentStep) {
                // ---------------- STEP 0: WHAT SHOULD THE AI CALL YOU ----------------
                0 -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(22.dp),
                ) {
                    Row(
                        Modifier.clip(CircleShape).background(c.limeTile).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Person, null, tint = c.onLime, modifier = Modifier.size(15.dp))
                        Text("  Personalize Voice AI", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onLime)
                    }

                    Text(
                        "What should the AI call you?",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        "Your Voice AI will use your name when it talks with you.",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
                    )

                    InputField(
                        value = userName,
                        onValue = { userName = it },
                        placeholder = "Your name or nickname (e.g. Alex)",
                        leading = Icons.Rounded.Person,
                        onDone = { if (userName.isNotBlank()) step = 1 },
                    )

                    // Live preview card in lime/sage style
                    val previewName = userName.trim().ifBlank { "Friend" }
                    Column(
                        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                            .background(c.limeTile).padding(18.dp),
                    ) {
                        Text("Meet your assistant", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.onLime.copy(alpha = 0.7f))
                        Text(
                            "Hi $previewName — I can manage your tasks, write notes from scattered words and keep your passwords safe.",
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Normal,
                            color = c.onLime,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { step = 1 },
                        enabled = userName.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                    ) {
                        Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                    // Reinstalled? Bring everything back from an exported file.
                    Text(
                        "Used Voxban before?", fontSize = 13.sp, color = c.inkSecondary,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                    RestoreButton(vm, outlined = false, label = "Restore from backup file")
                }

                // ---------------- STEP 1: LANGUAGE SELECTION ----------------
                1 -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(22.dp),
                ) {
                    Row(
                        Modifier.clip(CircleShape).background(c.limeTile).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Language, null, tint = c.onLime, modifier = Modifier.size(15.dp))
                        Text("  Voice & Speech Language", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onLime)
                    }

                    Text(
                        "Choose your language",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        "Voice AI will listen and speak to ${userName.trim().ifBlank { "you" }} in this language.",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppLanguage.entries.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { lang ->
                                    val selected = lang.code == language
                                    val cardBg by animateColorAsState(if (selected) c.limeTile else c.raised, tween(200), label = "langBg")
                                    val textFg = if (selected) c.onLime else c.ink
                                    val subFg = if (selected) c.onLime.copy(alpha = 0.72f) else c.inkSecondary
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                            .background(cardBg)
                                            .border(1.dp, if (selected) c.onLime.copy(alpha = 0.25f) else c.border, RoundedCornerShape(20.dp))
                                            .clickable { language = lang.code }
                                            .padding(14.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(lang.nativeName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = textFg, modifier = Modifier.weight(1f))
                                            if (selected) {
                                                Box(Modifier.size(22.dp).clip(CircleShape).background(c.onLime), contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Rounded.Check, null, tint = c.limeTile, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                        Text(lang.label, fontSize = 12.sp, color = subFg, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Greeting preview in the selected language
                    Column(
                        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(c.sageTile.copy(alpha = 0.45f)).padding(16.dp),
                    ) {
                        Text("Voice in ${AppLanguage.byCode(language).label}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.inkSecondary)
                        Text(
                            "Replies are spoken by a native ${AppLanguage.byCode(language).label} ElevenLabs voice (add your ElevenLabs key in Settings → Voice).",
                            fontSize = 15.sp,
                            color = c.ink,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { step = 2 },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                    ) {
                        Text("Continue to AI Model", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }

                // ---------------- STEP 2: MODEL SELECTION ----------------
                else -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                        .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(20.dp),
                ) {
                    Row(
                        Modifier.clip(CircleShape).background(c.limeTile).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Shield, null, tint = c.onLime, modifier = Modifier.size(14.dp))
                        Text("  No account · stays on your phone", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onLime)
                    }

                    Text(
                        "Select your AI model",
                        style = MaterialTheme.typography.titleLarge,
                        color = c.ink,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "Pick how the assistant understands ${userName.trim().ifBlank { "you" }}. Change this anytime in Settings.",
                        color = c.inkSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )

                    Engine.selectable.forEach { e ->
                        val sel = e == engine
                        val cardBg by animateColorAsState(if (sel) c.limeTile else c.raised, tween(200), label = "engBg")
                        val fg = if (sel) c.onLime else c.ink
                        val subFg = if (sel) c.onLime.copy(alpha = 0.72f) else c.inkSecondary
                        Column(
                            Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cardBg)
                                .border(1.dp, if (sel) c.onLime.copy(alpha = 0.25f) else c.border, RoundedCornerShape(22.dp))
                                .clickable { engine = e }.padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape).background(if (sel) c.onLime else c.surfaceWell),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(icons.getValue(e), null, tint = if (sel) c.limeTile else c.inkSecondary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.label, style = MaterialTheme.typography.titleMedium, color = fg)
                                    Text(e.vendor, fontSize = 12.sp, color = subFg)
                                }
                                Icon(
                                    if (sel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    null,
                                    tint = if (sel) c.onLime else c.borderStrong,
                                )
                            }
                            Text(blurbs.getValue(e), color = subFg, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            if (sel && e.needsKey) {
                                Spacer(Modifier.height(10.dp))
                                InputField(key, { key = it }, "Paste your ${e.label} API key")
                                Text("You can test the key and pick a model in Settings.", fontSize = 11.sp, color = subFg, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }

                    Button(
                        onClick = { vm.completeOnboarding(userName, language, engine, key) },
                        Modifier.padding(top = 8.dp).fillMaxWidth().height(54.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                    ) {
                        Text("Open my board", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
