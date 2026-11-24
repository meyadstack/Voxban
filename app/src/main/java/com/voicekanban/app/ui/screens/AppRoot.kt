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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.AppLanguage
import com.voicekanban.app.data.Engine
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.Section
import com.voicekanban.app.ui.components.LogoMark
import com.voicekanban.app.ui.components.pagerSwipeTransition
import com.voicekanban.app.ui.theme.VK
import com.voicekanban.app.update.AppUpdater
import com.voicekanban.app.update.UpdateState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: MainViewModel, locked: Boolean, onUnlock: () -> Unit) {
    val app by vm.state.collectAsState()
    val section by vm.section.collectAsState()
    val updateState by AppUpdater.state.collectAsState()
    val c = VK.colors
    val r = VK.responsive
    val ctx = LocalContext.current

    // Always check GitHub Releases on launch; mandatory update blocks outdated builds cleanly.
    LaunchedEffect(Unit) {
        AppUpdater.checkForUpdate(ctx, manual = false, forceCheck = true)
    }

    if (locked) { LockScreen(onUnlock); return }
    if (AppUpdater.isUpdateRequired(updateState)) {
        MandatoryUpdateScreen(state = updateState)
        return
    }
    if (!app.settings.onboarded) { OnboardingScreen(vm); return }

    var settingsOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var updateSheetOpen by remember { mutableStateOf(false) }
    var supportOpen by remember { mutableStateOf(false) }
    var pairOpen by remember { mutableStateOf(false) }
    val pendingOpenSupport by vm.openSupport.collectAsState()
    val pendingOpenPair by vm.openPairDesktop.collectAsState()

    LaunchedEffect(pendingOpenSupport) {
        if (pendingOpenSupport) {
            supportOpen = true
            vm.clearOpenSupport()
        }
    }
    LaunchedEffect(pendingOpenPair) {
        if (pendingOpenPair) {
            pairOpen = true
            vm.clearOpenPairDesktop()
        }
    }

    // A running focus session takes over the whole screen.
    app.activeFocus?.let { f -> FocusOverlay(vm, f); return }

    val primaryIdx = PRIMARY_SECTIONS.indexOf(section)
    val isPrimary = primaryIdx >= 0
    val primaryPager = rememberPagerState(initialPage = primaryIdx.coerceAtLeast(0)) { PRIMARY_SECTIONS.size }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Sync programmatic tab taps -> pager with crisp spring swipe animation
    LaunchedEffect(section) {
        val idx = PRIMARY_SECTIONS.indexOf(section)
        if (idx >= 0 && primaryPager.currentPage != idx) {
            primaryPager.animateScrollToPage(idx, animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f))
        }
    }

    // Sync user finger swipes on primary pager -> active section + subtle haptic tick
    LaunchedEffect(primaryPager, isPrimary) {
        if (!isPrimary) return@LaunchedEffect
        snapshotFlow { primaryPager.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val target = PRIMARY_SECTIONS[page]
                if (vm.section.value != target && vm.section.value in PRIMARY_SECTIONS) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    vm.setSection(target)
                }
            }
    }

    val displayedSection = if (isPrimary) PRIMARY_SECTIONS[primaryPager.targetPage] else section

    val mainContent: @Composable (Modifier) -> Unit = { mod ->
        Column(mod.imePadding()) {
            // In-app GitHub release update banner when a newer release is available / downloading / ready
            if (updateState is UpdateState.Available || updateState is UpdateState.Downloading || updateState is UpdateState.ReadyToInstall) {
                Box(Modifier.statusBarsPadding()) {
                    UpdateBanner(
                        state = updateState,
                        onOpen = { updateSheetOpen = true },
                        onDismiss = {
                            (updateState as? UpdateState.Available)?.let { av ->
                                vm.updateSettings { it.copy(dismissedUpdateTag = av.release.tagName) }
                            }
                            AppUpdater.dismissBanner()
                        },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = if (isPrimary) null else section,
                    transitionSpec = {
                        val goingToMore = targetState != null
                        val dir = if (goingToMore) 1 else -1
                        (fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { dir * it / 5 })
                            .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -dir * it / 6 })
                    },
                    label = "rootSectionLayer",
                ) { moreSection ->
                    if (moreSection == null) {
                        // 1:1 finger-tracked swipe navigation across Home ↔ Tasks ↔ Voice AI ↔ Workout
                        HorizontalPager(
                            state = primaryPager,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                        ) { page ->
                            Box(Modifier.fillMaxSize().pagerSwipeTransition(primaryPager, page, tilt = false)) {
                                when (PRIMARY_SECTIONS[page]) {
                                    Section.HOME -> DashboardScreen(vm, onSettings = { settingsOpen = true })
                                    Section.BOARD -> BoardScreen(
                                        vm,
                                        onOpenUpdateSheet = { updateSheetOpen = true },
                                        onOpenSupport = { supportOpen = true },
                                        onOpenPairDesktop = { pairOpen = true },
                                    )
                                    Section.ASSISTANT -> AssistantScreen(vm)
                                    Section.WORKOUT -> WorkoutScreen(vm)
                                    else -> Unit
                                }
                            }
                        }
                    } else {
                        when (moreSection) {
                            Section.NOTES -> NotesScreen(vm)
                            Section.MONEY -> MoneyScreen(vm)
                            Section.VAULT -> VaultScreen(vm)
                            Section.FOCUS -> FocusScreen(vm)
                            else -> Unit
                        }
                    }
                }
            }
            // Slim workout music mini-player when listening while browsing other tabs
            if (displayedSection != Section.WORKOUT && !WindowInsets.isImeVisible) {
                MiniMusicBar(onOpenWorkout = { vm.setSection(Section.WORKOUT) })
            }
            // Bottom tab bar on portrait phones (steps aside while typing)
            if (!r.useRailNav && !WindowInsets.isImeVisible) {
                TabBar(displayedSection, onMore = { moreOpen = true }) { target ->
                    vm.setSection(target)
                    val idx = PRIMARY_SECTIONS.indexOf(target)
                    if (idx >= 0) {
                        scope.launch {
                            primaryPager.animateScrollToPage(idx, animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f))
                        }
                    }
                }
            }
        }
    }

    if (r.useRailNav) {
        // Landscape phones, foldables, and tablets: compact left Navigation Rail preserves full vertical height.
        Row(Modifier.fillMaxSize().background(c.background)) {
            NavRail(
                section = section,
                onSelect = { vm.setSection(it) },
                onSettings = { settingsOpen = true },
                onSupport = { supportOpen = true },
                onPairDesktop = { pairOpen = true },
            )
            Box(Modifier.fillMaxHeight().width(1.dp).background(c.border))
            mainContent(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        mainContent(Modifier.fillMaxSize().background(c.background))
    }

    if (moreOpen) MoreSheet(
        onDismiss = { moreOpen = false },
        onSettings = { moreOpen = false; settingsOpen = true },
        onSupport = { moreOpen = false; supportOpen = true },
        onPairDesktop = { moreOpen = false; pairOpen = true },
    ) { moreOpen = false; vm.setSection(it) }
    if (settingsOpen) SettingsSheet(
        vm = vm,
        onDismiss = { settingsOpen = false },
        onOpenUpdateSheet = { settingsOpen = false; updateSheetOpen = true },
        onOpenSupport = { settingsOpen = false; supportOpen = true },
        onOpenPairDesktop = { settingsOpen = false; pairOpen = true },
    )
    if (updateSheetOpen) UpdateSheet(vm, updateState, onDismiss = { updateSheetOpen = false })
    if (supportOpen) SupportSheet(vm, onDismiss = { supportOpen = false })
    if (pairOpen) DesktopPairSheet(vm, onDismiss = { pairOpen = false })
}

private val PRIMARY_SECTIONS = listOf(
    Section.HOME,
    Section.BOARD,
    Section.ASSISTANT,
    Section.WORKOUT,
)

private val MORE_SECTIONS = listOf(
    Triple(Section.NOTES, "Notes", Icons.AutoMirrored.Rounded.StickyNote2),
    Triple(Section.MONEY, "Money", Icons.Rounded.AccountBalanceWallet),
    Triple(Section.VAULT, "Vault", Icons.Rounded.Lock),
    Triple(Section.FOCUS, "Focus", Icons.Rounded.SelfImprovement),
)

private val ALL_NAV_SECTIONS = listOf(
    Triple(Section.HOME, "Home", Icons.Rounded.SpaceDashboard),
    Triple(Section.BOARD, "Tasks", Icons.Rounded.GridView),
    Triple(Section.ASSISTANT, "Voice AI", Icons.Rounded.GraphicEq),
    Triple(Section.WORKOUT, "Workout", Icons.Rounded.DirectionsRun),
) + MORE_SECTIONS

@Composable
private fun NavRail(section: Section, onSelect: (Section) -> Unit, onSettings: () -> Unit, onSupport: () -> Unit, onPairDesktop: () -> Unit) {
    val c = VK.colors
    Column(
        Modifier.fillMaxHeight().width(76.dp).background(c.surface)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ALL_NAV_SECTIONS.forEach { (s, label, icon) ->
            val active = s == section
            val bg by animateColorAsState(if (active) c.limeTile else Color.Transparent, tween(200), label = "railBg")
            val fg by animateColorAsState(if (active) c.onLime else c.inkSecondary, tween(200), label = "railFg")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                    .clickable { onSelect(s) }.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = fg, modifier = Modifier.size(20.dp))
                Text(
                    label,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onPairDesktop)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.QrCodeScanner, "Pair PC", tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            Text("Pair PC", fontSize = 10.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSupport)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.SupportAgent, "Support", tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            Text("Support", fontSize = 10.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSettings)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Settings, "Settings", tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            Text("Settings", fontSize = 10.sp, color = c.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun TabBar(section: Section, onMore: () -> Unit, onChange: (Section) -> Unit) {
    val c = VK.colors
    val r = VK.responsive
    val inMore = MORE_SECTIONS.firstOrNull { it.first == section }
    Surface(color = c.background) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = if (r.isCompactWidth) 8.dp else 12.dp, vertical = 8.dp)
                .clip(CircleShape).background(c.raised).border(1.dp, c.buttonBorder, CircleShape).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Triple(Section.HOME, "Home", Icons.Rounded.SpaceDashboard),
                Triple(Section.BOARD, "Tasks", Icons.Rounded.GridView),
                Triple(Section.ASSISTANT, "Voice AI", Icons.Rounded.GraphicEq),
                Triple(Section.WORKOUT, "Workout", Icons.Rounded.DirectionsRun),
                Triple(null, inMore?.second ?: "More", inMore?.third ?: Icons.Rounded.Apps),
            ).forEach { (s, label, icon) ->
                val active = if (s == null) inMore != null else s == section
                val bg by animateColorAsState(if (active) c.limeTile else Color.Transparent, tween(220), label = "tabBg")
                val fg by animateColorAsState(if (active) c.onLime else c.inkSecondary, tween(220), label = "tabFg")
                val targetW = if (active) (if (r.isCompactWidth) 2.35f else 2.2f) else 1f
                val w by animateFloatAsState(targetW, spring(dampingRatio = 0.8f, stiffness = 380f), label = "tabW")
                Row(
                    Modifier.weight(w).heightIn(min = 46.dp).clip(CircleShape).background(bg)
                        .clickable { if (s == null) onMore() else onChange(s) }
                        .padding(horizontal = if (r.isCompactWidth) 4.dp else 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, label, tint = fg, modifier = Modifier.size(if (r.isCompactWidth) 19.dp else 20.dp))
                    if (active) {
                        Spacer(Modifier.width(if (r.isCompactWidth) 4.dp else 6.dp))
                        Text(
                            label,
                            fontSize = if (r.isCompactWidth) 12.sp else 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSheet(onDismiss: () -> Unit, onSettings: () -> Unit, onSupport: () -> Unit, onPairDesktop: () -> Unit, onPick: (Section) -> Unit) {
    val c = VK.colors
    val cols = if (VK.responsive.isWideWidth) 3 else 2
    val items = MORE_SECTIONS.map { Triple<String, String, ImageVector>(it.first.name, it.second, it.third) } +
        listOf(
            Triple("PAIR_PC", "Pair Desktop", Icons.Rounded.QrCodeScanner),
            Triple("SUPPORT", "Support", Icons.Rounded.SupportAgent),
            Triple("SETTINGS", "Settings", Icons.Rounded.Settings),
        )
    AppSheet("More", onDismiss = onDismiss) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label, icon) ->
                    Column(
                        Modifier.weight(1f).heightIn(min = 88.dp).clip(RoundedCornerShape(22.dp)).background(c.raised)
                            .border(1.dp, c.buttonBorder, RoundedCornerShape(22.dp))
                            .clickable {
                                when (key) {
                                    "SETTINGS" -> onSettings()
                                    "SUPPORT" -> onSupport()
                                    "PAIR_PC" -> onPairDesktop()
                                    else -> onPick(Section.valueOf(key))
                                }
                            }.padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(c.limeTile), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = c.onLime, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = VK.colors
    Column(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoMark(80.dp)
        Text("Voxban is locked", style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
        Text("Unlock with your fingerprint, face or screen lock.", color = c.inkSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).widthIn(max = 320.dp))
        Button(onClick = onUnlock, Modifier.padding(top = 28.dp).heightIn(min = 48.dp), shape = CircleShape) {
            Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingScreen(vm: MainViewModel) {
    val c = VK.colors
    val r = VK.responsive
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

    Box(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = r.formMaxWidth).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = r.headerGutter, vertical = 14.dp),
        ) {
            // Top header with step pills
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(c.raised).border(1.dp, c.buttonBorder, CircleShape).clickable { step-- },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.ink, modifier = Modifier.size(20.dp))
                    }
                } else {
                    LogoMark(42.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voxban", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1)
                    Text("Step ${step + 1} of 3", fontSize = 12.sp, color = c.inkSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { idx ->
                        val w by animateDpAsState(if (idx == step) 26.dp else 9.dp, spring(dampingRatio = 0.75f, stiffness = 300f), label = "dotW")
                        val dotBg by animateColorAsState(
                            when {
                                idx == step -> c.limeTile
                                idx < step -> c.ink
                                else -> c.sageTile
                            },
                            tween(220), label = "dotBg",
                        )
                        Box(Modifier.height(9.dp).width(w).clip(CircleShape).background(dotBg))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

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
                val pad = if (r.isCompactWidth) 16.dp else 22.dp
                when (currentStep) {
                    0 -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(pad),
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
                            fontSize = if (r.isCompactWidth) 24.sp else 28.sp,
                            color = c.ink,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "Your Voice AI will use your name when it talks with you.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                        )

                        InputField(
                            value = userName,
                            onValue = { userName = it },
                            placeholder = "Your name or nickname (e.g. Alex)",
                            leading = Icons.Rounded.Person,
                            onDone = { if (userName.isNotBlank()) step = 1 },
                        )

                        val previewName = userName.trim().ifBlank { "Friend" }
                        Column(
                            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                                .background(c.limeTile).padding(16.dp),
                        ) {
                            Text("Meet your assistant", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.onLime.copy(alpha = 0.7f))
                            Text(
                                "Hi $previewName — I can manage your tasks, write notes from scattered words and keep your passwords safe.",
                                fontSize = if (r.isCompactWidth) 16.sp else 18.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.Normal,
                                color = c.onLime,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { step = 1 },
                            enabled = userName.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                        ) {
                            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            "Used Voxban before?", fontSize = 13.sp, color = c.inkSecondary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                        )
                        RestoreButton(vm, outlined = false, label = "Restore from backup file")
                    }

                    1 -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(pad),
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
                            fontSize = if (r.isCompactWidth) 24.sp else 28.sp,
                            color = c.ink,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            "Voice AI will listen and speak to ${userName.trim().ifBlank { "you" }} in this language.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                        )

                        val langCols = if (r.screenWidthDp >= 520) 3 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguage.entries.chunked(langCols).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { lang ->
                                        val selected = lang.code == language
                                        val cardBg by animateColorAsState(if (selected) c.limeTile else c.raised, tween(200), label = "langBg")
                                        val textFg = if (selected) c.onLime else c.ink
                                        val subFg = if (selected) c.onLime.copy(alpha = 0.72f) else c.inkSecondary
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                                                .background(cardBg)
                                                .border(1.dp, if (selected) c.onLime.copy(alpha = 0.25f) else c.border, RoundedCornerShape(20.dp))
                                                .clickable { language = lang.code }
                                                .padding(if (r.isCompactWidth) 11.dp else 14.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    lang.nativeName,
                                                    fontSize = if (r.isCompactWidth) 15.sp else 17.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = textFg,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (selected) {
                                                    Box(Modifier.size(20.dp).clip(CircleShape).background(c.onLime), contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Rounded.Check, null, tint = c.limeTile, modifier = Modifier.size(13.dp))
                                                    }
                                                }
                                            }
                                            Text(lang.label, fontSize = 12.sp, color = subFg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                    repeat(langCols - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }

                        Column(
                            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(c.sageTile.copy(alpha = 0.45f)).padding(14.dp),
                        ) {
                            Text("Voice in ${AppLanguage.byCode(language).label}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.inkSecondary)
                            Text(
                                "Replies are spoken by a native ${AppLanguage.byCode(language).label} ElevenLabs voice (add your ElevenLabs key in Settings → Voice).",
                                fontSize = 14.sp,
                                color = c.ink,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = { step = 2 },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                        ) {
                            Text("Continue to AI Model", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }

                    else -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surface)
                            .border(1.dp, c.border, RoundedCornerShape(28.dp)).padding(pad),
                    ) {
                        Row(
                            Modifier.clip(CircleShape).background(c.limeTile).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Shield, null, tint = c.onLime, modifier = Modifier.size(14.dp))
                            Text("  No account · stays on your phone", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.onLime, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Text(
                            "Select your AI model",
                            style = MaterialTheme.typography.titleLarge,
                            fontSize = if (r.isCompactWidth) 24.sp else 28.sp,
                            color = c.ink,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            "Pick how the assistant understands ${userName.trim().ifBlank { "you" }}. Change this anytime in Settings.",
                            color = c.inkSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                        )

                        Engine.selectable.forEach { e ->
                            val sel = e == engine
                            val cardBg by animateColorAsState(if (sel) c.limeTile else c.raised, tween(200), label = "engBg")
                            val fg = if (sel) c.onLime else c.ink
                            val subFg = if (sel) c.onLime.copy(alpha = 0.72f) else c.inkSecondary
                            Column(
                                Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cardBg)
                                    .border(1.dp, if (sel) c.onLime.copy(alpha = 0.25f) else c.border, RoundedCornerShape(22.dp))
                                    .clickable { engine = e }.padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(40.dp).clip(CircleShape).background(if (sel) c.onLime else c.surfaceWell),
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
                            Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 52.dp),
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
}
