package com.example.fitnesstracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.fitnesstracker.data.*
import com.example.fitnesstracker.ui.theme.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- Datenmodelle ---

data class WorkoutSession(
    val id: Long = 0,
    val name: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val exercises: List<Exercise>,
    val timestamp: Long = System.currentTimeMillis()
)

data class Exercise(
    val name: String,
    val description: String? = "",
    val sets: List<WorkoutSet>
)

data class WorkoutSet(
    val currentKg: String,
    val currentReps: String,
    val lastKg: String,
    val lastReps: String,
    val isDone: Boolean = false
)

data class AppData(
    val workouts: List<String>,
    val exercisesPerWorkout: Map<String, List<Exercise>>,
    val history: List<WorkoutSession>,
    val planName: String,
    val unitsPerWeek: Int
)

enum class ProgressMetric(val label: String, val unit: String) {
    MAX_WEIGHT("Max. Gewicht", "kg"),
    VOLUME("Volumen", "kg"),
    ONE_RM("Est. 1RM", "kg")
}

// --- Hilfsfunktionen ---

private const val PREFS_NAME = "fitness_tracker_prefs"
private const val KEY_ALL_DATA = "all_app_data"

fun isCurrentWeek(timestamp: Long): Boolean {
    val cal = Calendar.getInstance()
    val currentWeek = cal.get(Calendar.WEEK_OF_YEAR)
    val currentYear = cal.get(Calendar.YEAR)
    val targetCal = Calendar.getInstance()
    targetCal.timeInMillis = timestamp
    return currentWeek == targetCal.get(Calendar.WEEK_OF_YEAR) && currentYear == targetCal.get(Calendar.YEAR)
}

fun getDurationMillis(session: WorkoutSession): Long {
    val sdf = SimpleDateFormat("HH:mm", Locale.GERMAN)
    return try {
        val start = sdf.parse(session.startTime)?.time ?: 0L
        val end = sdf.parse(session.endTime)?.time ?: 0L
        var diff = end - start
        if (diff < 0) diff += 86400000L
        diff
    } catch (e: Exception) { 0L }
}

enum class TimeRange(val label: String) {
    LAST_7_DAYS("Letzte 7 Tage"),
    LAST_30_DAYS("Letzte 30 Tage"),
    THIS_YEAR("Dieses Jahr"),
    ALL("Insgesamt")
}

// --- Haupt-Activity ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            FitnessTrackerApp()
        }
    }
}

@Composable
fun FitnessTrackerApp() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.fitnessDao()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val workouts = remember { mutableStateListOf<String>() }
    val exercisesPerWorkout = remember { mutableStateMapOf<String, List<Exercise>>() }
    val workoutHistory = remember { mutableStateListOf<WorkoutSession>() }
    var planName by remember { mutableStateOf("Mein Plan") }
    var unitsPerWeek by remember { mutableIntStateOf(4) }
    val sessionStartTimes = remember { mutableStateMapOf<String, Long>() }

    LaunchedEffect(Unit) {
        val templates = dao.getAllTemplates()
        val historyEntities = dao.getAllHistory()
        val settings = dao.getSettings()

        if (templates.isEmpty() && historyEntities.isEmpty()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_ALL_DATA, null)
            if (json != null) {
                try {
                    val gson = Gson()
                    val legacyData = gson.fromJson(json, AppData::class.java)
                    dao.saveSettings(SettingsEntity(planName = legacyData.planName, unitsPerWeek = legacyData.unitsPerWeek))
                    legacyData.workouts.forEachIndexed { i, n ->
                        dao.insertTemplate(WorkoutTemplateEntity(n, gson.toJson(legacyData.exercisesPerWorkout[n] ?: emptyList<Exercise>()), i))
                    }
                    legacyData.history.forEach { s ->
                        dao.insertSession(WorkoutSessionEntity(name = s.name, date = s.date, startTime = s.startTime, endTime = s.endTime, exercisesJson = gson.toJson(s.exercises), timestamp = s.timestamp))
                    }
                    prefs.edit().remove(KEY_ALL_DATA).apply()
                } catch (e: Exception) {}
            } else {
                listOf("Push", "Pull", "Legs").forEachIndexed { i, n -> dao.insertTemplate(WorkoutTemplateEntity(n, "[]", i)) }
            }
        }
        
        val freshTemplates = dao.getAllTemplates()
        workouts.clear(); workouts.addAll(freshTemplates.map { it.name })
        freshTemplates.forEach { 
            val type = object : com.google.gson.reflect.TypeToken<List<Exercise>>() {}.type
            exercisesPerWorkout[it.name] = Gson().fromJson(it.exercisesJson, type)
        }
        val freshHistory = dao.getAllHistory()
        workoutHistory.clear()
        workoutHistory.addAll(freshHistory.map { h ->
            val type = object : com.google.gson.reflect.TypeToken<List<Exercise>>() {}.type
            WorkoutSession(h.id, h.name, h.date, h.startTime, h.endTime, Gson().fromJson(h.exercisesJson, type), h.timestamp)
        })
        dao.getSettings()?.let { planName = it.planName; unitsPerWeek = it.unitsPerWeek }
    }

    val triggerSave: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            dao.saveSettings(SettingsEntity(planName = planName, unitsPerWeek = unitsPerWeek))
            workouts.forEachIndexed { i, n ->
                dao.insertTemplate(WorkoutTemplateEntity(n, Gson().toJson(exercisesPerWorkout[n] ?: emptyList<Exercise>()), i))
            }
        }
    }

    val onUpdateSession: (WorkoutSession) -> Unit = { updated ->
        scope.launch(Dispatchers.IO) {
            dao.insertSession(WorkoutSessionEntity(
                id = updated.id,
                name = updated.name,
                date = updated.date,
                startTime = updated.startTime,
                endTime = updated.endTime,
                exercisesJson = Gson().toJson(updated.exercises),
                timestamp = updated.timestamp
            ))
            val idx = workoutHistory.indexOfFirst { it.id == updated.id }
            if (idx != -1) {
                workoutHistory[idx] = updated
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Build, null) }, label = { Text("Training") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = BluePrimary, selectedTextColor = BluePrimary, indicatorColor = Color.Transparent))
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Info, null) }, label = { Text("Statistik") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = BluePrimary, selectedTextColor = BluePrimary, indicatorColor = Color.Transparent))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                TrainingScreen(workouts, exercisesPerWorkout, sessionStartTimes, onDataChange = triggerSave, 
                    onFinishSession = { workoutName ->
                        val startTime = sessionStartTimes[workoutName] ?: return@TrainingScreen
                        val endTime = System.currentTimeMillis()
                        val sdfD = SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN); val sdfT = SimpleDateFormat("HH:mm", Locale.GERMAN)
                        val currentEx = exercisesPerWorkout[workoutName] ?: emptyList()
                        val newSession = WorkoutSession(name = workoutName, date = sdfD.format(Date(endTime)), startTime = sdfT.format(Date(startTime)), endTime = sdfT.format(Date(endTime)), exercises = currentEx.map { it.copy(sets = it.sets.filter { s -> s.currentKg.isNotEmpty() }) }, timestamp = endTime)
                        
                        scope.launch(Dispatchers.IO) { 
                            val id = dao.insertSession(WorkoutSessionEntity(name = newSession.name, date = newSession.date, startTime = newSession.startTime, endTime = newSession.endTime, exercisesJson = Gson().toJson(newSession.exercises), timestamp = newSession.timestamp))
                            workoutHistory.add(0, newSession.copy(id = id))
                        }
                        sessionStartTimes.remove(workoutName)
                        exercisesPerWorkout[workoutName] = currentEx.map { ex -> ex.copy(sets = ex.sets.map { s -> WorkoutSet("", "", s.currentKg.ifEmpty { s.lastKg }, s.currentReps.ifEmpty { s.lastReps }) }) }
                        triggerSave()
                    }, onDeleteWorkout = { name -> scope.launch(Dispatchers.IO) { dao.deleteTemplate(name) } })
            } else {
                StatisticsScreen(workouts, workoutHistory, planName, { planName = it; triggerSave() }, unitsPerWeek, { unitsPerWeek = it; triggerSave() }, exercisesPerWorkout, onUpdateSession)
            }
        }
    }
}

// --- UI Screens ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrainingScreen(
    workouts: SnapshotStateList<String>,
    exercisesPerWorkout: SnapshotStateMap<String, List<Exercise>>,
    sessionStartTimes: SnapshotStateMap<String, Long>,
    onDataChange: () -> Unit,
    onFinishSession: (String) -> Unit,
    onDeleteWorkout: (String) -> Unit
) {
    var activeIdx by remember { mutableIntStateOf(0) }
    var showAddW by remember { mutableStateOf(false) }; var newWN by remember { mutableStateOf("") }
    var showAddEx by remember { mutableStateOf(false) }; var newEN by remember { mutableStateOf("") }
    var editIdx by remember { mutableStateOf<Int?>(null) }; var showOpt by remember { mutableStateOf(false) }
    var showRen by remember { mutableStateOf(false) }; var renV by remember { mutableStateOf("") }

    val activeName = workouts.getOrNull(activeIdx) ?: ""
    val currentEx = exercisesPerWorkout[activeName] ?: emptyList()

    if (showAddW) {
        AlertDialog(onDismissRequest = { showAddW = false }, title = { Text("Neues Training") }, text = { TextField(newWN, { newWN = it }, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (newWN.isNotBlank()) { workouts.add(newWN); exercisesPerWorkout[newWN] = emptyList(); activeIdx = workouts.size - 1; newWN = ""; showAddW = false; onDataChange() } }) { Text("Hinzufügen") } },
            dismissButton = { TextButton(onClick = { showAddW = false }) { Text("Abbrechen") } })
    }

    if (showOpt && editIdx != null) {
        val name = workouts[editIdx!!]
        AlertDialog(onDismissRequest = { showOpt = false }, title = { Text("Training: $name") }, text = { Text("Umbenennen oder löschen?") },
            confirmButton = { TextButton(onClick = { renV = name; showOpt = false; showRen = true }) { Text("Umbenennen") } },
            dismissButton = { TextButton(onClick = { val removed = workouts.removeAt(editIdx!!); exercisesPerWorkout.remove(removed); onDeleteWorkout(removed); if (activeIdx >= workouts.size) activeIdx = (workouts.size - 1).coerceAtLeast(0); showOpt = false; onDataChange() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("Löschen") } })
    }

    if (showRen && editIdx != null) {
        AlertDialog(onDismissRequest = { showRen = false }, title = { Text("Umbenennen") }, text = { TextField(renV, { renV = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (renV.isNotBlank()) { val old = workouts[editIdx!!]; workouts[editIdx!!] = renV; exercisesPerWorkout[renV] = exercisesPerWorkout.remove(old) ?: emptyList(); showRen = false; onDataChange() } }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { showRen = false }) { Text("Abbrechen") } })
    }

    if (showAddEx) {
        AlertDialog(onDismissRequest = { showAddEx = false }, title = { Text("Neue Übung") }, text = { TextField(newEN, { newEN = it }, placeholder = { Text("Übungsname") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (newEN.isNotBlank()) { exercisesPerWorkout[activeName] = currentEx + Exercise(newEN, sets = listOf(WorkoutSet("", "", "", ""))); newEN = ""; showAddEx = false; onDataChange() } }) { Text("Hinzufügen") } },
            dismissButton = { TextButton(onClick = { showAddEx = false }) { Text("Abbrechen") } })
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundColor)) {
        Column(modifier = Modifier.fillMaxWidth().background(BluePrimary).padding(16.dp)) {
            Text(text = activeName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                workouts.forEachIndexed { i, n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.combinedClickable(onClick = { activeIdx = i }, onLongClick = { editIdx = i; showOpt = true }).padding(8.dp)) {
                            Text(n, color = if (activeIdx == i) Color.White else Color.White.copy(0.6f), fontWeight = if (activeIdx == i) FontWeight.Bold else FontWeight.Normal)
                        }
                        if (activeIdx == i) Box(Modifier.width(40.dp).height(2.dp).background(Color.White))
                    }
                }
                TextButton(onClick = { showAddW = true }) { Text("+ Neu", color = Color.White.copy(0.6f)) }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("ÜBUNGEN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray) }
            itemsIndexed(currentEx) { i, ex ->
                ExerciseCard(ex, { up -> if (!sessionStartTimes.containsKey(activeName)) sessionStartTimes[activeName] = System.currentTimeMillis(); val nl = currentEx.toMutableList(); nl[i] = up; exercisesPerWorkout[activeName] = nl; onDataChange() },
                    { val nl = currentEx.toMutableList(); nl.removeAt(i); exercisesPerWorkout[activeName] = nl; onDataChange() },
                    { n -> val nl = currentEx.toMutableList(); nl[i] = ex.copy(name = n); exercisesPerWorkout[activeName] = nl; onDataChange() })
            }
            item { Button(onClick = { showAddEx = true }, Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), border = BorderStroke(1.dp, BluePrimary)) { Text("+ Neue Übung", color = BluePrimary) } }
            if (currentEx.isNotEmpty()) {
                item { Button(onClick = { onFinishSession(activeName) }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { Text("Einheit beenden", color = Color.White, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.Check, null, tint = Color.White) } } }
            }
        }
    }
}

@Composable
fun ExerciseCard(ex: Exercise, onChange: (Exercise) -> Unit, onDel: () -> Unit, onRen: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }; var ren by remember { mutableStateOf(false) }; var rVal by remember { mutableStateOf(ex.name) }
    var descDialog by remember { mutableStateOf(false) }; var dVal by remember { mutableStateOf(ex.description ?: "") }
    var showDesc by remember { mutableStateOf(false) }
    val description = ex.description ?: ""

    if (ren) { AlertDialog(onDismissRequest = { ren = false }, title = { Text("Übung umbenennen") }, text = { TextField(rVal, { rVal = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onRen(rVal); ren = false }) { Text("Speichern") } }, dismissButton = { TextButton(onClick = { ren = false }) { Text("Abbrechen") } }) }
    if (descDialog) { AlertDialog(onDismissRequest = { descDialog = false }, title = { Text("Beschreibung") }, text = { TextField(dVal, { dVal = it }, placeholder = { Text("Z.B. Sitzhöhe 3, Fokus auf Dehnung...") }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { onChange(ex.copy(description = dVal)); descDialog = false }) { Text("Speichern") } }, dismissButton = { TextButton(onClick = { descDialog = false }) { Text("Abbrechen") } }) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { showDesc = !showDesc }) {
                    Text(ex.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (showDesc && description.isNotBlank()) { Text(description, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)) }
                }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Beschreibung") }, onClick = { menu = false; showDesc = !showDesc }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        DropdownMenuItem(text = { Text("Beschreibung bearbeiten") }, onClick = { menu = false; dVal = description; descDialog = true }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menu = false; rVal = ex.name; ren = true }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; onDel() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(ex.sets) { i, s -> SetCard(i, s) { up -> val ns = ex.sets.toMutableList(); ns[i] = up; if (ns.size < 3 && i == ns.size - 1 && (up.currentKg.isNotEmpty() || up.currentReps.isNotEmpty())) ns.add(WorkoutSet("", "", "", "")); onChange(ex.copy(sets = ns)) } }
            }
        }
    }
}

@Composable
fun SetCard(idx: Int, s: WorkoutSet, onChange: (WorkoutSet) -> Unit) {
    Column(Modifier.width(130.dp).border(0.5.dp, BorderColor, RoundedCornerShape(12.dp)).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = if (s.isDone) BluePrimary else BluePrimary.copy(0.1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable { onChange(s.copy(isDone = !s.isDone)) }) {
            Text(if (s.currentKg.isEmpty()) "Satz ${idx + 1}" else "${s.currentKg}kg x ${s.currentReps}", color = if (s.isDone) Color.White else BluePrimary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("kg", fontSize = 10.sp); SetInputField(s.currentKg, { onChange(s.copy(currentKg = it)) }); Text(s.lastKg.ifEmpty { "—" }, fontSize = 11.sp, color = Color.Blue) }
            Text("×", Modifier.padding(top = 20.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text("Wdh", fontSize = 10.sp); SetInputField(s.currentReps, { onChange(s.copy(currentReps = it)) }); Text(s.lastReps.ifEmpty { "—" }, fontSize = 11.sp, color = Color.Blue) }
        }
    }
}

@Composable
fun SetInputField(v: String, onVal: (String) -> Unit) {
    BasicTextField(v, onVal, textStyle = TextStyle(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp)).padding(4.dp), singleLine = true)
}

// --- Statistik Screen ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(workouts: List<String>, history: List<WorkoutSession>, planName: String, onPlanNameChange: (String) -> Unit, unitsPerWeek: Int, onUnitsPerWeekChange: (Int) -> Unit, exercisesPerWorkout: Map<String, List<Exercise>>, onUpdateSession: (WorkoutSession) -> Unit) {
    var editing by remember { mutableStateOf(false) }; var selS by remember { mutableStateOf<WorkoutSession?>(null) }; var selEx by remember { mutableStateOf<String?>(null) }
    var tRange by remember { mutableStateOf(TimeRange.LAST_30_DAYS) }; var menu by remember { mutableStateOf(false) }
    val filtered = remember(history, tRange) {
        val now = System.currentTimeMillis(); val cal = Calendar.getInstance()
        history.filter { when (tRange) {
            TimeRange.LAST_7_DAYS -> it.timestamp > now - 604800000L
            TimeRange.LAST_30_DAYS -> it.timestamp > now - 2592000000L
            TimeRange.THIS_YEAR -> { cal.timeInMillis = now; val y = cal.get(Calendar.YEAR); cal.timeInMillis = it.timestamp; cal.get(Calendar.YEAR) == y }
            TimeRange.ALL -> true
        } }
    }
    val totalTime = filtered.sumOf { getDurationMillis(it) }; val h = totalTime / 3600000; val m = (totalTime / 60000) % 60
    val uniqueEx = remember(filtered, exercisesPerWorkout) { (exercisesPerWorkout.values.flatten().map { it.name } + filtered.flatMap { it.exercises }.map { it.name }).distinct().sorted() }

    if (selS != null) SessionDetailDialog(selS!!, onUpdateSession) { selS = null }
    if (selEx != null) ExerciseProgressDialog(selEx!!, history) { selEx = null }

    Column(Modifier.fillMaxSize().background(BackgroundColor).padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Statistik", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Box { Row(modifier = Modifier.clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) { Text(tRange.label, color = DarkGray); Icon(Icons.Default.ArrowDropDown, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { TimeRange.entries.forEach { r -> DropdownMenuItem(text = { Text(r.label) }, onClick = { tRange = r; menu = false }) } }
        }
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatCard("${filtered.size}", "Einheiten", Modifier.weight(1f)); StatCard("${filtered.sumOf { it.exercises.size }}", "Übungen", Modifier.weight(1f)); StatCard("${filtered.sumOf { it.exercises.sumOf { ex -> ex.sets.size } }}", "Sätze", Modifier.weight(1f)) }
        Card(Modifier.fillMaxWidth().padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, null, tint = BluePrimary); Spacer(Modifier.width(16.dp)); Column { Text("Gesamtzeit im Studio", fontSize = 12.sp, color = DarkGray); Text(if(h>0) "${h}h ${m}m" else "${m}m", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = BluePrimary) } }
        }
        Spacer(Modifier.height(24.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("TRAININGSPLAN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray); IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, null, tint = BluePrimary) } }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (editing) { TextField(planName, onPlanNameChange, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); TextField(unitsPerWeek.toString(), { onUnitsPerWeekChange(it.toIntOrNull() ?: unitsPerWeek) }, label = { Text("Einheiten/Woche") }, modifier = Modifier.fillMaxWidth()) }
                else { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(planName, fontWeight = FontWeight.Bold); Text("$unitsPerWeek Einheiten/Woche", color = DarkGray, fontSize = 12.sp) } }
                Spacer(modifier = Modifier.height(16.dp)); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { workouts.forEach { n -> PlanChip(n, history.any { it.name == n && isCurrentWeek(it.timestamp) }) } }
            }
        }
        Spacer(Modifier.height(24.dp)); Text("FORTSCHRITT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray); Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) { Column(modifier = Modifier.padding(8.dp)) { uniqueEx.forEach { n -> Row(modifier = Modifier.fillMaxWidth().clickable { selEx = n }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(n); Icon(Icons.Default.KeyboardArrowRight, null, tint = BluePrimary) }; if (n != uniqueEx.last()) Divider(color = BorderColor.copy(0.5f)) } } }
        Spacer(Modifier.height(24.dp)); Text("LETZTE EINHEITEN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray); history.forEach { s -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selS = s }, colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(s.name, fontWeight = FontWeight.Bold); Text("${s.date} · ${s.startTime}-${s.endTime}", color = DarkGray, fontSize = 12.sp) }; Icon(Icons.Default.KeyboardArrowRight, null, tint = BluePrimary) } } }
    }
}

@Composable
fun LineChart(
    data: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = BluePrimary
) {
    if (data.size < 2) {
        Box(modifier = modifier.background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("Nicht genügend Daten für den Graph", fontSize = 12.sp, color = Color.Gray)
        }
        return
    }

    val minVal = (data.minOrNull() ?: 0.0).toFloat()
    val maxVal = (data.maxOrNull() ?: 0.0).toFloat()
    val range = if (maxVal == minVal) 1f else maxVal - minVal
    val leftPadding = 80f
    val verticalPadding = 40f

    Canvas(modifier = modifier) {
        val chartHeight = size.height - 2 * verticalPadding
        val chartWidth = size.width - leftPadding - 20f
        val gridLines = 4

        for (i in 0..gridLines) {
            val y = size.height - verticalPadding - (i * chartHeight / gridLines)
            val value = minVal + (i * (maxVal - minVal) / gridLines)
            drawLine(color = Color.LightGray.copy(alpha = 0.5f), start = Offset(leftPadding, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("${String.format("%.1f", value)} kg", 10f, y + 10f, android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 24f })
        }

        val spacing = chartWidth / (data.size - 1)
        val points = data.mapIndexed { index, value ->
            Offset(x = leftPadding + index * spacing, y = size.height - verticalPadding - ((value.toFloat() - minVal) / range * chartHeight))
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]; val p1 = points[i]
                cubicTo(p0.x + (p1.x - p0.x) / 2f, p0.y, p0.x + (p1.x - p0.x) / 2f, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = android.graphics.Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]; val p1 = points[i]
                cubicTo(p0.x + (p1.x - p0.x) / 2f, p0.y, p0.x + (p1.x - p0.x) / 2f, p1.y, p1.x, p1.y)
            }
            lineTo(points.last().x, size.height - verticalPadding); lineTo(points.first().x, size.height - verticalPadding); close()
        }

        drawContext.canvas.nativeCanvas.drawPath(fillPath, android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, points.minOf { it.y }, 0f, size.height - verticalPadding, lineColor.copy(alpha = 0.3f).toArgb(), Color.Transparent.toArgb(), android.graphics.Shader.TileMode.CLAMP)
        })

        drawPath(path = path, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEach { point -> drawCircle(Color.White, radius = 4.dp.toPx(), center = point); drawCircle(lineColor, radius = 2.dp.toPx(), center = point) }
    }
}

@Composable
fun ExerciseProgressDialog(name: String, history: List<WorkoutSession>, onDismiss: () -> Unit) {
    var selectedMetric by remember { mutableStateOf(ProgressMetric.MAX_WEIGHT) }
    val data = remember(name, history) {
        history.asReversed().mapNotNull { s ->
            val ex = s.exercises.find { it.name == name }
            if (ex != null && ex.sets.any { it.currentKg.isNotEmpty() }) {
                val max = ex.sets.mapNotNull { it.currentKg.toDoubleOrNull() }.maxOrNull() ?: 0.0
                val totalVolume = ex.sets.sumOf { (it.currentKg.toDoubleOrNull() ?: 0.0) * (it.currentReps.toDoubleOrNull() ?: 0.0) }
                val bestSet1RM = ex.sets.mapNotNull { set ->
                    val w = set.currentKg.toDoubleOrNull() ?: 0.0; val r = set.currentReps.toDoubleOrNull() ?: 0.0
                    if (r > 0) w * (1 + r / 30.0) else null
                }.maxOrNull() ?: 0.0
                s.date to mapOf(ProgressMetric.MAX_WEIGHT to max, ProgressMetric.VOLUME to totalVolume, ProgressMetric.ONE_RM to bestSet1RM)
            } else null
        }
    }
    val chartPoints = data.map { it.second[selectedMetric] ?: 0.0 }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.85f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold); IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                }
                ScrollableTabRow(selectedTabIndex = selectedMetric.ordinal, containerColor = Color.Transparent, edgePadding = 0.dp, divider = {}, indicator = {}) {
                    ProgressMetric.entries.forEach { metric ->
                        Tab(selected = selectedMetric == metric, onClick = { selectedMetric = metric }, text = { Text(metric.label, fontSize = 12.sp, color = if (selectedMetric == metric) BluePrimary else Color.Gray, fontWeight = if (selectedMetric == metric) FontWeight.Bold else FontWeight.Normal) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (chartPoints.size >= 2) LineChart(data = chartPoints, modifier = Modifier.fillMaxWidth().height(180.dp))
                else Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("Nicht genügend Datenpunkte", color = Color.Gray, fontSize = 12.sp) }
                Text("Historie (${selectedMetric.label})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                Divider()
                LazyColumn(Modifier.weight(1f)) {
                    items(data.reversed()) { (date, metrics) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column { Text(date, fontSize = 12.sp, color = Color.Gray); Text("${String.format("%.1f", metrics[selectedMetric])} ${selectedMetric.unit}", fontWeight = FontWeight.Bold) }
                            if (selectedMetric == ProgressMetric.MAX_WEIGHT) Text("Vol: ${metrics[ProgressMetric.VOLUME]?.toInt()} kg", fontSize = 11.sp, color = BluePrimary.copy(0.7f))
                        }
                        Divider(color = BorderColor.copy(0.3f))
                    }
                }
            }
        }
    }
}

@Composable
fun SessionDetailDialog(s: WorkoutSession, onUpdate: (WorkoutSession) -> Unit, onDismiss: () -> Unit) {
    var editMode by remember { mutableStateOf(false) }
    var editedSession by remember { mutableStateOf(s) }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.8f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        if (editMode) {
                            BasicTextField(editedSession.name, { editedSession = editedSession.copy(name = it) }, textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        } else {
                            Text(s.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(s.date, fontSize = 14.sp, color = DarkGray)
                        Text("${s.startTime} - ${s.endTime}", fontSize = 14.sp, color = DarkGray)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                        IconButton(onClick = { editMode = !editMode }) { Icon(if (editMode) Icons.Default.Check else Icons.Default.Edit, null, tint = BluePrimary) }
                    }
                }
                Spacer(Modifier.height(16.dp)); Divider()
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(editedSession.exercises) { exIdx, ex ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(ex.name, fontWeight = FontWeight.Bold, color = BluePrimary)
                            ex.sets.forEachIndexed { setIdx, set ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Satz ${setIdx + 1}", fontSize = 14.sp)
                                    if (editMode) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            SetInputField(set.currentKg, { v -> 
                                                val newEx = editedSession.exercises.toMutableList()
                                                val newSets = ex.sets.toMutableList()
                                                newSets[setIdx] = set.copy(currentKg = v)
                                                newEx[exIdx] = ex.copy(sets = newSets)
                                                editedSession = editedSession.copy(exercises = newEx)
                                            })
                                            Text(" kg x ", fontSize = 12.sp)
                                            SetInputField(set.currentReps, { v -> 
                                                val newEx = editedSession.exercises.toMutableList()
                                                val newSets = ex.sets.toMutableList()
                                                newSets[setIdx] = set.copy(currentReps = v)
                                                newEx[exIdx] = ex.copy(sets = newSets)
                                                editedSession = editedSession.copy(exercises = newEx)
                                            })
                                            Text(" Wdh", fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("${set.currentKg} kg x ${set.currentReps} Wdh", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }
                if (editMode) {
                    Button(onClick = { onUpdate(editedSession); editMode = false; onDismiss() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("Änderungen speichern")
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(v: String, l: String, m: Modifier = Modifier) {
    Card(modifier = m, colors = CardDefaults.cardColors(containerColor = CardBackground), border = BorderStroke(1.dp, BorderColor)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BluePrimary); Text(l, fontSize = 10.sp, color = DarkGray) }
    }
}

@Composable
fun PlanChip(n: String, done: Boolean) {
    Surface(color = if (done) Color(0xFFE8F5E9) else Color.Transparent, shape = RoundedCornerShape(16.dp), border = if (!done) BorderStroke(1.dp, BorderColor) else null) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(n, color = if (done) Color(0xFF4CAF50) else DarkGray, fontSize = 12.sp); if (done) Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp)) }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() { FitnessTrackerApp() }
