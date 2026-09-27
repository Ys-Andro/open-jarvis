package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val scheduleAtMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)

    @Update
    suspend fun update(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [AutomationEntity::class], version = 1)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null

        fun getInstance(context: Context): AutomationDB {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AutomationDB::class.java,
                    "automations.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val schedule = when (scheduleType) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs.coerceAtLeast(60_000L))
    }

    return AutomationManager.Automation(
        id = id,
        name = name,
        command = command,
        schedule = schedule,
        enabled = enabled,
        lastRun = lastRun,
        lastResult = lastResult,
        runCount = runCount
    )
}

fun AutomationManager.Automation.toEntity(): AutomationEntity {
    return when (val value = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> AutomationEntity(
            id = id, name = name, command = command, scheduleType = "daily",
            scheduleHour = value.hour, scheduleMinute = value.minute,
            enabled = enabled, lastRun = lastRun, lastResult = lastResult, runCount = runCount
        )
        is AutomationManager.AutomationSchedule.Weekly -> AutomationEntity(
            id = id, name = name, command = command, scheduleType = "weekly",
            scheduleHour = value.hour, scheduleMinute = value.minute,
            scheduleDayOfWeek = value.dayOfWeek,
            enabled = enabled, lastRun = lastRun, lastResult = lastResult, runCount = runCount
        )
        is AutomationManager.AutomationSchedule.Interval -> AutomationEntity(
            id = id, name = name, command = command, scheduleType = "interval",
            scheduleIntervalMs = value.intervalMs,
            enabled = enabled, lastRun = lastRun, lastResult = lastResult, runCount = runCount
        )
        is AutomationManager.AutomationSchedule.Once -> AutomationEntity(
            id = id, name = name, command = command, scheduleType = "once",
            scheduleIntervalMs = value.atMs,
            enabled = enabled, lastRun = lastRun, lastResult = lastResult, runCount = runCount
        )
    }
}

class AutomationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()

        return try {
            val db = AutomationDB.getInstance(applicationContext)
            val dao = db.automationDao()

            val entity = dao.getById(id) ?: return Result.failure()
            val automation = entity.toAutomation()

            kotlinx.coroutines.delay(2000)

            val updated = automation.copy(
                lastRun = System.currentTimeMillis(),
                lastResult = "success",
                runCount = automation.runCount + 1
            )
            dao.update(updated.toEntity())

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
