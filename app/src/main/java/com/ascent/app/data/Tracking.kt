package com.ascent.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class Analytics(
    val days: List<LocalDate>,
    val steps: List<Long>?,
    val sleepMin: List<Long>?,
    val screenMin: List<Long>?,
)

object Sources {
    val HC_PERMS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    val HC_REQUEST = HC_PERMS + "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    private val zone get() = ZoneId.systemDefault()

    fun hcStatus(ctx: Context) = HealthConnectClient.getSdkStatus(ctx)

    suspend fun hasHc(ctx: Context): Boolean = try {
        hcStatus(ctx) == HealthConnectClient.SDK_AVAILABLE &&
            HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(HC_PERMS)
    } catch (e: Exception) { false }

    suspend fun steps(ctx: Context, date: LocalDate): Long? = try {
        val r = HealthConnectClient.getOrCreate(ctx).aggregate(
            AggregateRequest(
                setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant()),
            )
        )
        r[StepsRecord.COUNT_TOTAL] ?: 0L
    } catch (e: Exception) { null }

    /** Sleep attributed to [date] = sessions in the window (date-1 18:00, date 18:00]. */
    suspend fun sleepMinutes(ctx: Context, date: LocalDate): Long? = try {
        val r = HealthConnectClient.getOrCreate(ctx).aggregate(
            AggregateRequest(
                setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                TimeRangeFilter.between(
                    date.minusDays(1).atTime(18, 0).atZone(zone).toInstant(),
                    date.atTime(18, 0).atZone(zone).toInstant(),
                ),
            )
        )
        r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes() ?: 0L
    } catch (e: Exception) { null }

    fun hasUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    suspend fun screenMinutes(ctx: Context, date: LocalDate): Long? = withContext(Dispatchers.IO) {
        if (!hasUsage(ctx)) return@withContext null
        try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            val launcher = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
            val events = usm.queryEvents(start, end)
            val e = UsageEvents.Event()
            val open = HashMap<String, Long>()
            var total = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName == launcher) continue
                val key = e.packageName + "/" + e.className
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> open[key] = e.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        open.remove(key)?.let { total += e.timeStamp - it }
                }
            }
            open.values.forEach { total += end - it }
            total / 60000
        } catch (e: Exception) { null }
    }

    /** steps count / sleep hours / screen minutes */
    suspend fun value(ctx: Context, src: AutoSource, date: LocalDate): Double? = when (src) {
        AutoSource.NONE -> null
        AutoSource.STEPS -> if (hasHc(ctx)) steps(ctx, date)?.toDouble() else null
        AutoSource.SLEEP -> if (hasHc(ctx)) sleepMinutes(ctx, date)?.let { it / 60.0 } else null
        AutoSource.SCREEN -> screenMinutes(ctx, date)?.toDouble()
    }

    fun meets(src: AutoSource, v: Double, target: Double) = if (src == AutoSource.SCREEN) v <= target else v >= target

    suspend fun analytics(ctx: Context): Analytics {
        val today = LocalDate.now()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val hc = hasHc(ctx)
        return Analytics(
            days,
            if (hc) days.map { steps(ctx, it) ?: 0L } else null,
            if (hc) days.map { sleepMinutes(ctx, it) ?: 0L } else null,
            if (hasUsage(ctx)) days.map { screenMinutes(ctx, it) ?: 0L } else null,
        )
    }
}

object Tracker {
    private suspend fun applyAuto(ctx: Context, rec: DayRecord, s: Settings, date: LocalDate): DayRecord {
        var r = rec
        s.nn.forEachIndexed { i, n ->
            if (n.source == AutoSource.NONE) return@forEachIndexed
            val v = Sources.value(ctx, n.source, date) ?: return@forEachIndexed
            val ok = Sources.meets(n.source, v, n.target)
            r = r.withFlag(i, if (n.source == AutoSource.SCREEN) ok else r.flag(i) || ok)
        }
        return r
    }

    /** Finalise every unfinalised day from start until yesterday (auto-sources first; unticked = miss). */
    suspend fun catchUp(ctx: Context) {
        val s = Prefs.load(ctx)
        val dao = AscentDb.dao(ctx)
        val today = LocalDate.now()
        var d = LocalDate.parse(s.startDate)
        while (d.isBefore(today)) {
            val rec = dao.day(d.toString()) ?: DayRecord(d.toString())
            if (!rec.finalized) dao.upsertDay(applyAuto(ctx, rec, s, d).copy(finalized = true))
            d = d.plusDays(1)
        }
    }

    suspend fun refreshToday(ctx: Context) {
        val s = Prefs.load(ctx)
        val dao = AscentDb.dao(ctx)
        val today = LocalDate.now()
        val rec = dao.day(today.toString()) ?: DayRecord(today.toString())
        val upd = applyAuto(ctx, rec, s, today)
        if (upd != rec || dao.day(today.toString()) == null) dao.upsertDay(upd)
    }
}

class DayWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Tracker.catchUp(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(0, 5)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<DayWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("finalize-day", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
