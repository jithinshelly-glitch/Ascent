package com.ascent.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "days")
data class DayRecord(
    @PrimaryKey val date: String,
    val d1: Boolean = false,
    val d2: Boolean = false,
    val d3: Boolean = false,
    val finalized: Boolean = false,
)

fun DayRecord.complete() = d1 && d2 && d3
fun DayRecord.flag(i: Int) = when (i) { 0 -> d1; 1 -> d2; else -> d3 }
fun DayRecord.withFlag(i: Int, v: Boolean) = when (i) { 0 -> copy(d1 = v); 1 -> copy(d2 = v); else -> copy(d3 = v) }

@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val weight: Double?,
    val waist: Double?,
    val chest: Double?,
    val hips: Double?,
    val arm: Double?,
    val thigh: Double?,
) {
    fun get(key: String): Double? = when (key) {
        "weight" -> weight; "waist" -> waist; "chest" -> chest; "hips" -> hips; "arm" -> arm; else -> thigh
    }
}

@Dao
interface AscentDao {
    @Query("SELECT * FROM days ORDER BY date")
    fun daysFlow(): Flow<List<DayRecord>>

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun day(date: String): DayRecord?

    @Upsert
    suspend fun upsertDay(r: DayRecord)

    @Query("SELECT * FROM measurements ORDER BY date, id")
    fun measurementsFlow(): Flow<List<Measurement>>

    @Insert
    suspend fun insertMeasurement(m: Measurement)

    @Delete
    suspend fun deleteMeasurement(m: Measurement)
}

@Database(entities = [DayRecord::class, Measurement::class], version = 1, exportSchema = false)
abstract class AscentDb : RoomDatabase() {
    abstract fun dao(): AscentDao

    companion object {
        @Volatile private var inst: AscentDb? = null
        fun dao(ctx: Context): AscentDao = (inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AscentDb::class.java, "ascent.db").build().also { inst = it }
        }).dao()
    }
}

enum class AutoSource(val label: String) { NONE("None"), STEPS("Steps"), SLEEP("Sleep"), SCREEN("Screen") }

data class NonNeg(val name: String, val target: Double, val unit: String, val source: AutoSource)

const val START_KG = 75.0
const val GOAL_KG = 68.0
const val HEIGHT_CM = 164
val MEASURES = listOf("waist", "chest", "hips", "arm", "thigh")
val DEFAULT_TARGETS = mapOf("waist" to 80.0, "chest" to 94.0, "hips" to 94.0, "arm" to 31.0, "thigh" to 54.0)
val DEFAULT_NN = listOf(
    NonNeg("Protein", 140.0, "g", AutoSource.NONE),
    NonNeg("Calories", 1700.0, "kcal max", AutoSource.NONE),
    NonNeg("Steps", 9000.0, "steps", AutoSource.STEPS),
)

data class Settings(
    val nn: List<NonNeg>,
    val startDate: String,
    val glbPath: String?,
    val targets: Map<String, Double>,
)

object Prefs {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("ascent", Context.MODE_PRIVATE)

    fun load(ctx: Context): Settings {
        val p = sp(ctx)
        val start = p.getString("start", null) ?: LocalDate.now().toString().also { p.edit().putString("start", it).apply() }
        val nn = (0..2).map { i ->
            val d = DEFAULT_NN[i]
            NonNeg(
                p.getString("nn${i}_name", d.name) ?: d.name,
                p.getString("nn${i}_t", null)?.toDoubleOrNull() ?: d.target,
                p.getString("nn${i}_u", d.unit) ?: d.unit,
                runCatching { AutoSource.valueOf(p.getString("nn${i}_s", d.source.name)!!) }.getOrDefault(d.source),
            )
        }
        val targets = MEASURES.associateWith { p.getString("t_$it", null)?.toDoubleOrNull() ?: DEFAULT_TARGETS.getValue(it) }
        return Settings(nn, start, p.getString("glb", null), targets)
    }

    fun save(ctx: Context, s: Settings) {
        val e = sp(ctx).edit()
        e.putString("start", s.startDate)
        s.nn.forEachIndexed { i, n ->
            e.putString("nn${i}_name", n.name).putString("nn${i}_t", n.target.toString())
                .putString("nn${i}_u", n.unit).putString("nn${i}_s", n.source.name)
        }
        s.targets.forEach { (k, v) -> e.putString("t_$k", v.toString()) }
        if (s.glbPath == null) e.remove("glb") else e.putString("glb", s.glbPath)
        e.apply()
    }
}
