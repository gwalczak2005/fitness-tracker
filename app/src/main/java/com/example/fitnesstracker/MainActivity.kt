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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.fitnesstracker.ui.theme.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FitnessTrackerApp()
        }
    }
}

data class WorkoutSession(
    val name: String,
    val date: String, // format: dd. MMMM yyyy
    val startTime: String,
    val endTime: String,
    val exercises: List<Exercise>,
    val timestamp: Long = System.currentTimeMillis()
)

data class Exercise(
    val name: String,
    val sets: List<WorkoutSet>
)

data class WorkoutSet(
    val currentKg: String,
    val currentReps: String,
    val lastKg: String,
    val lastReps: String,
    val isDone: Boolean = false
)

private const val PREFS_NAME = "fitness_tracker_prefs"
private const val KEY_ALL_DATA = "all_app_data"

data class AppData(
    val workouts: List<String>,
    val exercisesPerWorkout: Map<String, List<Exercise>>,
    val history: List<WorkoutSession>,
    val planName: String,
    val unitsPerWeek: Int
)

fun saveAppData(context: Context, data: AppData) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val gson = Gson()
    prefs.edit().putString(KEY_ALL_DATA, gson.toJson(data)).commit() // commit ensures immediate write to disk
}

fun isCurrentWeek(timestamp: Long): Boolean {
    val cal = Calendar.getInstance()
    val currentWeek = cal.get(Calendar.WEEK_OF_YEAR)
    val currentYear = cal.get(Calendar.YEAR)
    
    val targetCal = Calendar.getInstance()
    targetCal.timeInMillis = timestamp
    return currentWeek == targetCal.get(Calendar.WEEK_OF_YEAR) && currentYear == targetCal.get(Calendar.YEAR)
}

@Composable
fun FitnessTrackerApp() {
    val context = LocalContext.current
    val gson = Gson()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Initial Data Loading
    val savedData = remember {
        val json = prefs.getString(KEY_ALL_DATA, null)
        if (json != null) {
            try {
                gson.fromJson<AppData>(json, AppData::class.java)
            } catch (e: Exception) { null }
        } else null
    }

    var selectedTab by remember { mutableStateOf(0) }
    
    val workouts = remember { 
        mutableStateListOf<String>().apply { 
            addAll(savedData?.workouts ?: listOf("Upper 1", "Upper 2", "Upper 3")) 
        } 
    }
    
    val exercisesPerWorkout = remember { 
        mutableStateMapOf<String, List<Exercise>>().apply { 
            putAll(savedData?.exercisesPerWorkout ?: mapOf(
                "Upper 1" to emptyList(),
                "Upper 2" to listOf(
                    Exercise("Bizeps Curls", listOf(WorkoutSet("", "", "12", "10"))),
                    Exercise("Triceps Pushdown", listOf(WorkoutSet("", "", "20", "12")))
                ),
                "Upper 3" to emptyList()
            )) 
        } 
    }
    
    val workoutHistory = remember { 
        mutableStateListOf<WorkoutSession>().apply { 
            addAll(savedData?.history ?: emptyList()) 
        } 
    }
    
    var planName by remember { mutableStateOf(savedData?.planName ?: "PPL x Arnold") }
    var unitsPerWeek by remember { mutableStateOf(savedData?.unitsPerWeek ?: 6) }
    
    val sessionStartTimes = remember { mutableStateMapOf<String, Long>() }

    // Save Lambda
    val triggerSave = {
        saveAppData(context, AppData(
            workouts = workouts.toList(),
            exercisesPerWorkout = exercisesPerWorkout.toMap(),
            history = workoutHistory.toList(),
            planName = planName,
            unitsPerWeek = unitsPerWeek
        ))
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Training") },
                    label = { Text("Training") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BluePrimary,
                        selectedTextColor = BluePrimary,
                        indicatorColor = Color.Transparent
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Statistik") },
                    label = { Text("Statistik") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BluePrimary,
                        selectedTextColor = BluePrimary,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                TrainingScreen(
                    workouts = workouts,
                    exercisesPerWorkout = exercisesPerWorkout,
                    sessionStartTimes = sessionStartTimes,
                    onDataChange = triggerSave,
                    onFinishSession = { workoutName ->
                        val startTime = sessionStartTimes[workoutName]
                        if (startTime != null) {
                            val endTime = System.currentTimeMillis()
                            val sdfDate = SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN)
                            val sdfTime = SimpleDateFormat("HH:mm", Locale.GERMAN)
                            val currentExercises = exercisesPerWorkout[workoutName] ?: emptyList()
                            
                            workoutHistory.add(0, WorkoutSession(
                                name = workoutName,
                                date = sdfDate.format(Date(endTime)),
                                startTime = sdfTime.format(Date(startTime)),
                                endTime = sdfTime.format(Date(endTime)),
                                exercises = currentExercises.map { it.copy(sets = it.sets.filter { s -> s.currentKg.isNotEmpty() }.toList()) },
                                timestamp = endTime
                            ))
                            
                            sessionStartTimes.remove(workoutName)
                            exercisesPerWorkout[workoutName] = currentExercises.map { exercise ->
                                exercise.copy(sets = exercise.sets.map { set ->
                                    WorkoutSet(
                                        currentKg = "",
                                        currentReps = "",
                                        lastKg = set.currentKg.ifEmpty { set.lastKg },
                                        lastReps = set.currentReps.ifEmpty { set.lastReps }
                                    )
                                })
                            }
                            triggerSave()
                        }
                    }
                )
            } else {
                StatisticsScreen(
                    workouts = workouts,
                    history = workoutHistory,
                    planName = planName,
                    onPlanNameChange = { planName = it; triggerSave() },
                    unitsPerWeek = unitsPerWeek,
                    onUnitsPerWeekChange = { unitsPerWeek = it; triggerSave() },
                    exercisesPerWorkout = exercisesPerWorkout
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrainingScreen(
    workouts: SnapshotStateList<String>,
    exercisesPerWorkout: SnapshotStateMap<String, List<Exercise>>,
    sessionStartTimes: SnapshotStateMap<String, Long>,
    onDataChange: () -> Unit,
    onFinishSession: (String) -> Unit
) {
    var activeWorkoutIdx by remember { mutableStateOf(0) }
    var showAddWorkoutDialog by remember { mutableStateOf(false) }
    var newWorkoutName by remember { mutableStateOf("") }
    var showAddExerciseDialog by remember { mutableStateOf(false) }
    var newExerciseName by remember { mutableStateOf("") }

    var workoutToEditIdx by remember { mutableStateOf<Int?>(null) }
    var showWorkoutOptionsDialog by remember { mutableStateOf(false) }
    var showRenameWorkoutDialog by remember { mutableStateOf(false) }
    var renameWorkoutValue by remember { mutableStateOf("") }

    val activeWorkoutName = workouts.getOrNull(activeWorkoutIdx) ?: ""
    val currentExercises = exercisesPerWorkout[activeWorkoutName] ?: emptyList()

    if (showAddWorkoutDialog) {
        AlertDialog(
            onDismissRequest = { showAddWorkoutDialog = false },
            title = { Text("Neues Training") },
            text = {
                TextField(
                    value = newWorkoutName,
                    onValueChange = { newWorkoutName = it },
                    placeholder = { Text("Name z.B. Push 1") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newWorkoutName.isNotBlank()) {
                        workouts.add(newWorkoutName)
                        exercisesPerWorkout[newWorkoutName] = emptyList()
                        activeWorkoutIdx = workouts.size - 1
                        newWorkoutName = ""
                        showAddWorkoutDialog = false
                        onDataChange()
                    }
                }) { Text("Hinzufügen") }
            },
            dismissButton = {
                TextButton(onClick = { showAddWorkoutDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showWorkoutOptionsDialog && workoutToEditIdx != null) {
        val name = workouts[workoutToEditIdx!!]
        AlertDialog(
            onDismissRequest = { showWorkoutOptionsDialog = false },
            title = { Text("Training: $name") },
            text = { Text("Möchtest du diese Trainingseinheit umbenennen oder löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    renameWorkoutValue = name
                    showWorkoutOptionsDialog = false
                    showRenameWorkoutDialog = true
                }) { Text("Umbenennen") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val removedName = workouts.removeAt(workoutToEditIdx!!)
                    exercisesPerWorkout.remove(removedName)
                    if (activeWorkoutIdx >= workouts.size) {
                        activeWorkoutIdx = (workouts.size - 1).coerceAtLeast(0)
                    }
                    showWorkoutOptionsDialog = false
                    onDataChange()
                }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                    Text("Löschen")
                }
            }
        )
    }

    if (showRenameWorkoutDialog && workoutToEditIdx != null) {
        AlertDialog(
            onDismissRequest = { showRenameWorkoutDialog = false },
            title = { Text("Training umbenennen") },
            text = {
                TextField(value = renameWorkoutValue, onValueChange = { renameWorkoutValue = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameWorkoutValue.isNotBlank()) {
                        val oldName = workouts[workoutToEditIdx!!]
                        workouts[workoutToEditIdx!!] = renameWorkoutValue
                        val ex = exercisesPerWorkout.remove(oldName) ?: emptyList()
                        exercisesPerWorkout[renameWorkoutValue] = ex
                        showRenameWorkoutDialog = false
                        onDataChange()
                    }
                }) { Text("Speichern") }
            }
        )
    }

    if (showAddExerciseDialog) {
        AlertDialog(
            onDismissRequest = { showAddExerciseDialog = false },
            title = { Text("Neue Übung") },
            text = {
                TextField(
                    value = newExerciseName,
                    onValueChange = { newExerciseName = it },
                    placeholder = { Text("Übungsname") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newExerciseName.isNotBlank()) {
                        val updatedList = currentExercises + Exercise(newExerciseName, listOf(WorkoutSet("", "", "", "")))
                        exercisesPerWorkout[activeWorkoutName] = updatedList
                        newExerciseName = ""
                        showAddExerciseDialog = false
                        onDataChange()
                    }
                }) { Text("Hinzufügen") }
            },
            dismissButton = {
                TextButton(onClick = { showAddExerciseDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BackgroundColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BluePrimary)
                .padding(16.dp)
        ) {
            Text(
                text = activeWorkoutName,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                workouts.forEachIndexed { index, name ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { activeWorkoutIdx = index },
                                    onLongClick = {
                                        workoutToEditIdx = index
                                        showWorkoutOptionsDialog = true
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = name,
                                color = if (activeWorkoutIdx == index) Color.White else Color.White.copy(alpha = 0.6f),
                                fontWeight = if (activeWorkoutIdx == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 16.sp
                            )
                        }
                        if (activeWorkoutIdx == index) {
                            Box(modifier = Modifier.width(40.dp).height(2.dp).background(Color.White))
                        }
                    }
                }
                TextButton(onClick = { showAddWorkoutDialog = true }) {
                    Text("+ Neu", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("ÜBUNGEN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray)
            }
            itemsIndexed(currentExercises) { exerciseIdx, exercise ->
                ExerciseCard(
                    exercise = exercise,
                    onExerciseChange = { updatedExercise ->
                        if (!sessionStartTimes.containsKey(activeWorkoutName)) {
                            sessionStartTimes[activeWorkoutName] = System.currentTimeMillis()
                        }
                        val newList = currentExercises.toMutableList()
                        newList[exerciseIdx] = updatedExercise
                        exercisesPerWorkout[activeWorkoutName] = newList
                        onDataChange()
                    },
                    onDeleteExercise = {
                        val newList = currentExercises.toMutableList()
                        newList.removeAt(exerciseIdx)
                        exercisesPerWorkout[activeWorkoutName] = newList
                        onDataChange()
                    },
                    onRenameExercise = { newName ->
                        val newList = currentExercises.toMutableList()
                        newList[exerciseIdx] = exercise.copy(name = newName)
                        exercisesPerWorkout[activeWorkoutName] = newList
                        onDataChange()
                    }
                )
            }
            item {
                Button(
                    onClick = { showAddExerciseDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, BluePrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+ Neue Übung", color = BluePrimary)
                }
            }
            
            if (currentExercises.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onFinishSession(activeWorkoutName) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("Einheit beenden", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun ExerciseCard(
    exercise: Exercise,
    onExerciseChange: (Exercise) -> Unit,
    onDeleteExercise: () -> Unit,
    onRenameExercise: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf(exercise.name) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Übung umbenennen") },
            text = {
                TextField(value = renameValue, onValueChange = { renameValue = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameValue.isNotBlank()) {
                        onRenameExercise(renameValue)
                        showRenameDialog = false
                    }
                }) { Text("Speichern") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(exercise.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = BluePrimary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${exercise.sets.size} Sätze",
                            color = BluePrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Optionen")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Umbenennen") },
                            onClick = {
                                showMenu = false
                                renameValue = exercise.name
                                showRenameDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Löschen") },
                            onClick = {
                                showMenu = false
                                onDeleteExercise()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(exercise.sets) { setIdx, set ->
                    SetCard(
                        setIdx = setIdx,
                        set = set,
                        onSetChange = { updatedSet ->
                            val newSets = exercise.sets.toMutableList()
                            newSets[setIdx] = updatedSet
                            
                            if (newSets.size < 3 && setIdx == newSets.size - 1) {
                                if (updatedSet.currentKg.isNotEmpty() || updatedSet.currentReps.isNotEmpty()) {
                                    newSets.add(WorkoutSet("", "", "", ""))
                                }
                            }
                            onExerciseChange(exercise.copy(sets = newSets))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SetCard(
    setIdx: Int,
    set: WorkoutSet,
    onSetChange: (WorkoutSet) -> Unit
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .border(0.5.dp, BorderColor, RoundedCornerShape(12.dp))
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = if (set.isDone) BluePrimary else BluePrimary.copy(alpha = 0.1f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(bottom = 8.dp)
                .clickable { onSetChange(set.copy(isDone = !set.isDone)) }
        ) {
            val labelText = if (set.currentKg.isEmpty() && set.currentReps.isEmpty()) 
                "Satz ${setIdx + 1}" 
            else 
                "${set.currentKg.ifEmpty { "0" }}kg x ${set.currentReps.ifEmpty { "0" }}"
            
            Text(
                text = labelText,
                color = if (set.isDone) Color.White else BluePrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text("kg", fontSize = 10.sp, color = BluePrimary, fontWeight = FontWeight.Bold)
                SetInputField(
                    value = set.currentKg,
                    onValueChange = { onSetChange(set.copy(currentKg = it)) },
                    placeholder = "0"
                )
                Text(
                    text = set.lastKg.ifEmpty { "—" },
                    fontSize = 11.sp,
                    color = Color.Blue,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            Text(" × ", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text("Wdh", fontSize = 10.sp, color = BluePrimary, fontWeight = FontWeight.Bold)
                SetInputField(
                    value = set.currentReps,
                    onValueChange = { onSetChange(set.copy(currentReps = it)) },
                    placeholder = "0"
                )
                Text(
                    text = set.lastReps.ifEmpty { "—" },
                    fontSize = 11.sp,
                    color = Color.Blue,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun SetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = Color.Black
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp))
            .border(0.5.dp, Color.LightGray, RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    Text(text = placeholder, fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center)
                }
                innerTextField()
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(
    workouts: List<String>,
    history: List<WorkoutSession>,
    planName: String,
    onPlanNameChange: (String) -> Unit,
    unitsPerWeek: Int,
    onUnitsPerWeekChange: (Int) -> Unit,
    exercisesPerWorkout: Map<String, List<Exercise>>
) {
    var isEditingPlan by remember { mutableStateOf(false) }
    var selectedSessionForDetail by remember { mutableStateOf<WorkoutSession?>(null) }
    var selectedExerciseForProgress by remember { mutableStateOf<String?>(null) }

    val totalExercises = history.sumOf { it.exercises.count { ex -> ex.sets.any { s -> s.currentKg.isNotEmpty() } } }
    val totalSets = history.sumOf { it.exercises.sumOf { ex -> ex.sets.count { s -> s.currentKg.isNotEmpty() } } }

    val allUniqueExercises = remember(history, exercisesPerWorkout) {
        (exercisesPerWorkout.values.flatten().map { it.name } + 
         history.flatMap { it.exercises }.map { it.name }).distinct().sorted()
    }

    if (selectedSessionForDetail != null) {
        SessionDetailDialog(
            session = selectedSessionForDetail!!,
            onDismiss = { selectedSessionForDetail = null }
        )
    }

    if (selectedExerciseForProgress != null) {
        ExerciseProgressDialog(
            exerciseName = selectedExerciseForProgress!!,
            history = history,
            onDismiss = { selectedExerciseForProgress = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Statistik", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Letzte 30 Tage", color = DarkGray, fontSize = 14.sp)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${history.size}", "Einheiten", Modifier.weight(1f))
            StatCard("$totalExercises", "Übungen", Modifier.weight(1f))
            StatCard("$totalSets", "Sätze", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TRAININGSPLAN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray)
            IconButton(onClick = { isEditingPlan = !isEditingPlan }) {
                Icon(if (isEditingPlan) Icons.Default.Check else Icons.Default.Edit, contentDescription = null, tint = BluePrimary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isEditingPlan) {
                    TextField(
                        value = planName,
                        onValueChange = onPlanNameChange,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = unitsPerWeek.toString(),
                        onValueChange = { onUnitsPerWeekChange(it.toIntOrNull() ?: unitsPerWeek) },
                        label = { Text("Einheiten / Woche") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(planName, fontWeight = FontWeight.Bold)
                        Text("$unitsPerWeek Einheiten / Woche", color = DarkGray, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    workouts.forEach { name ->
                        val isCompleted = history.any { it.name == name && isCurrentWeek(it.timestamp) }
                        PlanChip(name, isCompleted)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("FORTSCHRITT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (allUniqueExercises.isEmpty()) {
                    Text("Noch keine Übungen vorhanden", color = DarkGray, modifier = Modifier.padding(8.dp), fontSize = 14.sp)
                } else {
                    allUniqueExercises.forEach { exerciseName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedExerciseForProgress = exerciseName }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(exerciseName, fontWeight = FontWeight.Medium)
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = BluePrimary, modifier = Modifier.size(20.dp))
                        }
                        if (exerciseName != allUniqueExercises.last()) {
                            Divider(color = BorderColor.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("LETZTE EINHEITEN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DarkGray)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (history.isEmpty()) {
            Text("Noch keine Einheiten absolviert", color = DarkGray, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            history.forEach { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedSessionForDetail = session },
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(session.name, fontWeight = FontWeight.Bold)
                            Text("${session.date} · ${session.startTime} - ${session.endTime}", color = DarkGray, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = BluePrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun ExerciseProgressDialog(exerciseName: String, history: List<WorkoutSession>, onDismiss: () -> Unit) {
    val progressData = remember(exerciseName, history) {
        history.asReversed().mapNotNull { session ->
            val exercise = session.exercises.find { it.name == exerciseName }
            if (exercise != null && exercise.sets.any { it.currentKg.isNotEmpty() }) {
                val maxWeight = exercise.sets.mapNotNull { it.currentKg.toDoubleOrNull() }.maxOrNull() ?: 0.0
                val totalReps = exercise.sets.mapNotNull { it.currentReps.toIntOrNull() }.sum()
                if (maxWeight > 0 || totalReps > 0) session.date to Pair(maxWeight, totalReps) else null
            } else null
        }
    }

    var isChartView by remember { mutableStateOf(false) }
    var showWeight by remember { mutableStateOf(true) }
    var showReps by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = exerciseName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { isChartView = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = if (!isChartView) BluePrimary else Color.Gray)
                    ) {
                        Text("Liste", fontWeight = if (!isChartView) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(
                        onClick = { isChartView = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = if (isChartView) BluePrimary else Color.Gray)
                    ) {
                        Text("Diagramm", fontWeight = if (isChartView) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                if (isChartView) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Checkbox(checked = showWeight, onCheckedChange = { showWeight = it })
                        Text("Gewicht", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Checkbox(checked = showReps, onCheckedChange = { showReps = it })
                        Text("Wdh", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                if (progressData.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Keine Fortschrittsdaten verfügbar", color = DarkGray)
                    }
                } else {
                    if (isChartView) {
                        ExerciseBarChart(progressData, showWeight, showReps)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(progressData.reversed()) { (date, data) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(date, fontSize = 12.sp, color = DarkGray)
                                        Text("${data.first} kg", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Gesamt Wdh.", fontSize = 12.sp, color = DarkGray)
                                        Text("${data.second}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = BluePrimary)
                                    }
                                }
                                Divider(color = BorderColor.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExerciseBarChart(
    data: List<Pair<String, Pair<Double, Int>>>,
    showWeight: Boolean,
    showReps: Boolean
) {
    if (data.isEmpty() || (!showWeight && !showReps)) {
        Box(modifier = Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Text("Bitte Option wählen", color = Color.Gray)
        }
        return
    }

    val maxWeight = if (showWeight) data.maxOf { it.second.first } else 0.0
    val maxReps = if (showReps) data.maxOf { it.second.second }.toDouble() else 0.0
    val maxValue = maxOf(maxWeight, maxReps).coerceAtLeast(1.0).toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 8.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val barSpacing = canvasWidth / data.size
                val barWidth = barSpacing * 0.6f
                
                data.forEachIndexed { index, (_, values) ->
                    val xBase = index * barSpacing + (barSpacing - barWidth) / 2
                    
                    if (showWeight) {
                        val weightHeight = (values.first.toFloat() / maxValue) * canvasHeight
                        drawRect(
                            color = Color(0xFF4CAF50), // Green
                            topLeft = Offset(xBase, canvasHeight - weightHeight),
                            size = Size(if (showReps) barWidth / 2 else barWidth, weightHeight)
                        )
                    }
                    
                    if (showReps) {
                        val repsHeight = (values.second.toFloat() / maxValue) * canvasHeight
                        val xOffset = if (showWeight) barWidth / 2 else 0f
                        drawRect(
                            color = BluePrimary, // Blue
                            topLeft = Offset(xBase + xOffset, canvasHeight - repsHeight),
                            size = Size(if (showWeight) barWidth / 2 else barWidth, repsHeight)
                        )
                    }
                }
                
                drawLine(
                    color = Color.LightGray,
                    start = Offset(0f, canvasHeight),
                    end = Offset(canvasWidth, canvasHeight),
                    strokeWidth = 2f
                )
            }
        }
        
        // Date labels (simplified)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            data.forEach { (date, _) ->
                val shortDate = date.split(".").firstOrNull() ?: ""
                Text(
                    text = shortDate,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )
            }
        }
        
        // Legend
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
            if (showWeight) {
                Box(modifier = Modifier.size(12.dp).background(Color(0xFF4CAF50), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Gewicht (kg)", fontSize = 10.sp)
                Spacer(modifier = Modifier.width(16.dp))
            }
            if (showReps) {
                Box(modifier = Modifier.size(12.dp).background(BluePrimary, RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Wdh.", fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun SessionDetailDialog(session: WorkoutSession, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = session.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }
                Text(text = session.date, fontSize = 14.sp, color = DarkGray)
                Text(text = "${session.startTime} - ${session.endTime}", fontSize = 14.sp, color = DarkGray)
                
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = BorderColor)
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(session.exercises) { exercise ->
                        if (exercise.sets.any { it.currentKg.isNotEmpty() }) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(text = exercise.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = BluePrimary)
                                exercise.sets.forEachIndexed { idx, set ->
                                    if (set.currentKg.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp, horizontal = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Satz ${idx + 1}", fontSize = 14.sp)
                                            Text(
                                                text = "${set.currentKg} kg  ×  ${set.currentReps} Wdh",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                            Divider(color = BorderColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BluePrimary)
            Text(label, fontSize = 10.sp, color = DarkGray)
        }
    }
}

@Composable
fun PlanChip(name: String, completed: Boolean) {
    Surface(
        color = if (completed) Color(0xFFE8F5E9) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = if (!completed) BorderStroke(1.dp, BorderColor) else null
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = if (completed) Color(0xFF4CAF50) else DarkGray, fontSize = 12.sp)
            if (completed) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun ProgressCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Maximalgewicht", fontWeight = FontWeight.Bold)
                Text("↑ 2kg", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                val heights = listOf(0.4f, 0.5f, 0.45f, 0.6f, 0.9f)
                val months = listOf("Nov", "Dez", "Jan", "Feb", "Mär")
                heights.forEachIndexed { index, h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .fillMaxHeight(h)
                                .background(if (index == 4) BluePrimary else BluePrimary.copy(alpha = 0.3f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(months[index], fontSize = 10.sp, color = DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun LastWorkoutCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = DarkGray, fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = BluePrimary)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    FitnessTrackerApp()
}
