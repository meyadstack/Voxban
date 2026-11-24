package com.voicekanban.app.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicekanban.app.data.EXPENSE_CATEGORIES
import com.voicekanban.app.data.INCOME_CATEGORIES
import com.voicekanban.app.data.Transaction
import com.voicekanban.app.data.TxType
import com.voicekanban.app.data.categoryEmoji
import com.voicekanban.app.data.formatMoney
import com.voicekanban.app.data.newId
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.theme.VK
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** Money manager: monthly income / spending, categories with budgets, and every transaction. */
@Composable
fun MoneyScreen(vm: MainViewModel) {
    val c = VK.colors
    val app by vm.state.collectAsState()
    val cur = vm.currency
    var month by remember { mutableStateOf(YearMonth.now()) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var budgetFor by remember { mutableStateOf<String?>(null) }

    val inMonth = app.transactions.filter { YearMonth.from(it.at.date()) == month }.sortedByDescending { it.at }
    val income = inMonth.filter { it.type == TxType.INCOME }.sumOf { it.amount }
    val spent = inMonth.filter { it.type == TxType.EXPENSE }.sumOf { it.amount }
    val byCat = inMonth.filter { it.type == TxType.EXPENSE }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
    val cats = (byCat.keys + app.budgets.map { it.category }).distinct().sortedByDescending { byCat[it] ?: 0.0 }
    val monthLabel = "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}"

    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader(
            title = "Money",
            subtitle = "$monthLabel · ${inMonth.size} transaction${if (inMonth.size == 1) "" else "s"}",
            leading = { HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") { month = month.minusMonths(1) } },
            trailing = {
                if (month < YearMonth.now()) HeaderButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") { month = month.plusMonths(1) }
                else HeaderButton(Icons.Rounded.Add, "Add transaction") { editing = Transaction(newId("t"), 0.0) }
            },
        )

        val r = VK.responsive
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = r.gutter, end = r.gutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Summary
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.limeTile).padding(if (r.isCompactWidth) 16.dp else 20.dp)) {
                    Text("Balance this month", fontSize = 12.sp, color = c.onLime.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
                    Text(
                        (if (income - spent < 0) "−" else "") + formatMoney(kotlin.math.abs(income - spent), cur),
                        fontSize = if (r.isCompactWidth) 30.sp else 38.sp,
                        lineHeight = if (r.isCompactWidth) 36.sp else 44.sp,
                        color = c.onLime,
                        letterSpacing = (-1).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat(Modifier.weight(1f), "Income", formatMoney(income, cur), c.low)
                        Stat(Modifier.weight(1f), "Spent", formatMoney(spent, cur), c.high)
                    }
                }
            }
            // Quick add buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { editing = Transaction(newId("t"), 0.0, TxType.EXPENSE) }, Modifier.weight(1f).height(48.dp), shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
                    ) { Text("− Expense", fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { editing = Transaction(newId("t"), 0.0, TxType.INCOME) }, Modifier.weight(1f).height(48.dp), shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, c.buttonBorder),
                        colors = ButtonDefaults.buttonColors(containerColor = c.raised, contentColor = c.ink),
                    ) { Text("+ Income", fontWeight = FontWeight.SemiBold) }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp))
                        .clickable { vm.openAssistant(false) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = c.accentText, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(10.dp))
                    Text("Or just tell Voice AI: “I spent 250 on lunch” · “Got my salary 45,000” · “Set food budget to 6,000”", fontSize = 13.sp, color = c.inkSecondary)
                }
            }
            // Categories & budgets
            if (cats.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(24.dp)).padding(16.dp)) {
                    Text("Spending by category", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text("Tap a category to set a monthly budget.", fontSize = 12.sp, color = c.inkTertiary, modifier = Modifier.padding(bottom = 6.dp))
                    val max = (cats.maxOfOrNull { maxOf(byCat[it] ?: 0.0, app.budgets.firstOrNull { b -> b.category == it }?.limit ?: 0.0) } ?: 1.0).coerceAtLeast(1.0)
                    cats.forEach { cat ->
                        val amt = byCat[cat] ?: 0.0
                        val budget = app.budgets.firstOrNull { it.category.equals(cat, true) }?.limit
                        val over = budget != null && amt > budget
                        Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { budgetFor = cat }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(categoryEmoji(TxType.EXPENSE, cat) + "  " + cat, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text(formatMoney(amt, cur) + (budget?.let { " / ${formatMoney(it, cur)}" } ?: ""), color = if (over) c.high else c.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            val frac = if (budget != null) (amt / budget).toFloat() else (amt / max).toFloat()
                            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surfaceWell)) {
                                Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(if (over) c.high else if (budget != null && frac > 0.8f) c.medium else c.voiceSecondary))
                            }
                        }
                    }
                }
            }
            // Transactions grouped by day
            if (inMonth.isEmpty()) item {
                Text("No transactions in $monthLabel yet.", color = c.inkSecondary, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
            inMonth.groupBy { it.at.date() }.forEach { (day, list) ->
                item(key = "d$day") {
                    val net = list.sumOf { if (it.type == TxType.INCOME) it.amount else -it.amount }
                    Row(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                        Text(dayLabel(day), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.inkSecondary, modifier = Modifier.weight(1f))
                        Text((if (net < 0) "−" else "+") + formatMoney(kotlin.math.abs(net), cur), fontSize = 12.sp, color = c.inkTertiary)
                    }
                }
                items(list, key = { it.id }) { t -> TxRow(t, cur) { editing = t } }
            }
        }
    }

    editing?.let { t -> TxEditor(vm, t, onDismiss = { editing = null }) }
    budgetFor?.let { cat -> BudgetSheet(vm, cat, app.budgets.firstOrNull { it.category.equals(cat, true) }?.limit, onDismiss = { budgetFor = null }) }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, dot: Color) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.55f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = Color(0xFF1B201C).copy(alpha = 0.7f))
        }
        Text(value, fontSize = if (VK.responsive.isCompactWidth) 16.sp else 18.sp, color = Color(0xFF1B201C), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
}

@Composable
private fun TxRow(t: Transaction, cur: String, onClick: () -> Unit) {
    val c = VK.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp, c.border, RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (t.type == TxType.INCOME) c.lowSoft else c.surfaceWell), contentAlignment = Alignment.Center) {
            Text(categoryEmoji(t.type, t.category), fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(t.note.ifBlank { t.category }, color = c.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.category + " · " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t.at)), color = c.inkTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            (if (t.type == TxType.INCOME) "+" else "−") + formatMoney(t.amount, cur),
            color = if (t.type == TxType.INCOME) c.low else c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
        )
    }
}

@Composable
private fun TxEditor(vm: MainViewModel, t: Transaction, onDismiss: () -> Unit) {
    val c = VK.colors
    val ctx = LocalContext.current
    val isNew = vm.state.value.transactions.none { it.id == t.id }
    var type by remember { mutableStateOf(t.type) }
    var amount by remember { mutableStateOf(if (t.amount > 0) t.amount.toString().removeSuffix(".0") else "") }
    var category by remember { mutableStateOf(t.category.takeIf { !isNew } ?: "") }
    var note by remember { mutableStateOf(t.note) }
    var at by remember { mutableLongStateOf(t.at) }
    val cats = if (type == TxType.INCOME) INCOME_CATEGORIES else EXPENSE_CATEGORIES
    val value = amount.replace(",", "").toDoubleOrNull()

    AppSheet(if (isNew) "New ${if (type == TxType.INCOME) "income" else "expense"}" else "Edit transaction", onDismiss = onDismiss) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Expense", type == TxType.EXPENSE, Modifier.weight(1f)) { type = TxType.EXPENSE }
            Pill("Income", type == TxType.INCOME, Modifier.weight(1f)) { type = TxType.INCOME }
        }
        TextField(
            amount, { v -> amount = v.filter { it.isDigit() || it == '.' || it == ',' } },
            Modifier.padding(top = 12.dp).fillMaxWidth(),
            prefix = { Text(vm.currency, fontSize = 30.sp, color = c.inkSecondary) },
            placeholder = { Text("0", fontSize = 30.sp, color = c.inkTertiary) },
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = c.ink),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.surfaceWell, unfocusedContainerColor = c.surfaceWell, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = c.accent),
        )
        Text("Category", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cats.forEach { (name, emoji) -> Chip("$emoji $name", selected = category == name) { category = name } }
        }
        Spacer(Modifier.height(12.dp))
        InputField(note, { note = it }, "Note (e.g. lunch with Sam)", leading = Icons.Rounded.EditNote)
        Row(
            Modifier.padding(top = 10.dp).clip(CircleShape).background(c.surfaceWell).clickable {
                val d = at.date()
                DatePickerDialog(ctx, { _, y, m, dd ->
                    val time = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
                    at = LocalDate.of(y, m + 1, dd).atTime(time).atZone(zone).toInstant().toEpochMilli()
                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = c.inkSecondary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(dayLabel(at.date()), color = c.ink, fontSize = 14.sp)
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isNew) Button(
                onClick = { vm.deleteTransaction(t.id); onDismiss() }, Modifier.height(52.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.dangerSoft, contentColor = c.danger),
            ) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
            Button(
                onClick = {
                    vm.saveTransaction(t.copy(amount = value ?: 0.0, type = type, category = category.ifBlank { "Other" }, note = note.trim(), at = at))
                    onDismiss()
                },
                enabled = value != null && value > 0,
                modifier = Modifier.weight(1f).height(52.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
            ) { Text(if (isNew) "Save" else "Save changes", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BudgetSheet(vm: MainViewModel, category: String, current: Double?, onDismiss: () -> Unit) {
    val c = VK.colors
    var limit by remember { mutableStateOf(current?.toString()?.removeSuffix(".0") ?: "") }
    AppSheet("$category budget", "Monthly limit — the bar turns red when you go over", onDismiss = onDismiss) {
        TextField(
            limit, { v -> limit = v.filter { it.isDigit() || it == '.' } }, Modifier.padding(top = 8.dp).fillMaxWidth(),
            prefix = { Text(vm.currency, fontSize = 24.sp, color = c.inkSecondary) }, placeholder = { Text("No budget", color = c.inkTertiary) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = c.ink), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = c.surfaceWell, unfocusedContainerColor = c.surfaceWell, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = c.accent),
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current != null) Button(
                onClick = { vm.setBudget(category, null); onDismiss() }, Modifier.height(50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.dangerSoft, contentColor = c.danger),
            ) { Text("Remove") }
            Button(
                onClick = { vm.setBudget(category, limit.toDoubleOrNull()); onDismiss() }, Modifier.weight(1f).height(50.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.raised),
            ) { Text("Save budget", fontWeight = FontWeight.SemiBold) }
        }
    }
}
