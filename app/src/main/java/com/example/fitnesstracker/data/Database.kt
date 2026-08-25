package com.example.fitnesstracker.data

import android.content.Context
import androidx.room.*
import com.example.fitnesstracker.Exercise
import com.example.fitnesstracker.WorkoutSession
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "workout_history")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val exercisesJson: String,
    val timestamp: Long
)

@Entity(tableName = "workout_templates")
data class WorkoutTemplateEntity(
    @PrimaryKey val name: String,
    val exercisesJson: String,
    val order: Int
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val planName: String,
    val unitsPerWeek: Int
)

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromExerciseList(value: List<Exercise>): String = gson.toJson(value)

    @TypeConverter
    fun toExerciseList(value: String): List<Exercise> {
        val listType = object : TypeToken<List<Exercise>>() {}.type
        return gson.fromJson(value, listType)
    }
}

@Dao
interface FitnessDao {
    @Query("SELECT * FROM workout_history ORDER BY timestamp DESC")
    suspend fun getAllHistory(): List<WorkoutSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Query("DELETE FROM workout_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM workout_templates ORDER BY `order` ASC")
    suspend fun getAllTemplates(): List<WorkoutTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity)

    @Query("DELETE FROM workout_templates WHERE name = :name")
    suspend fun deleteTemplate(name: String)

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    @Query("DELETE FROM workout_templates")
    suspend fun deleteAllTemplates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<WorkoutTemplateEntity>)

    @Transaction
    suspend fun replaceAllTemplates(templates: List<WorkoutTemplateEntity>) {
        deleteAllTemplates()
        insertTemplates(templates)
    }
}

@Database(
    entities = [WorkoutSessionEntity::class, WorkoutTemplateEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fitnessDao(): FitnessDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fitness_tracker_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
