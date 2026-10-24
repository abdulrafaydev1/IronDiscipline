package com.irondiscipline.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.domain.model.InstalledAppInfo
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class UsageTrackerHelper(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val packageManager: PackageManager = context.packageManager

    /**
     * Real-time in-memory foreground session tracking to complement UsageStatsManager
     * so quota enforcement reacts to the exact second a limit is reached.
     */
    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var currentSessionStartEpochMs: Long = 0L

    @Volatile
    private var trackedDateString: String = LocalDate.now().toString()

    private val internalDailySecondsMap = ConcurrentHashMap<String, Long>()

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Called by AccessibilityService whenever foreground package changes.
     */
    @Synchronized
    fun onForegroundPackageChanged(newPackageName: String?, nowEpochMs: Long = System.currentTimeMillis()) {
        rolloverDateIfNeeded()
        val previousPkg = currentForegroundPackage
        if (previousPkg != null && currentSessionStartEpochMs > 0L) {
            val elapsedSeconds = ((nowEpochMs - currentSessionStartEpochMs) / 1000L).coerceIn(0L, 7200L)
            if (elapsedSeconds > 0L) {
                val existing = internalDailySecondsMap[previousPkg] ?: 0L
                internalDailySecondsMap[previousPkg] = existing + elapsedSeconds
            }
        }
        if (newPackageName != null && newPackageName != context.packageName) {
            currentForegroundPackage = newPackageName
            currentSessionStartEpochMs = nowEpochMs
        } else {
            currentForegroundPackage = null
            currentSessionStartEpochMs = 0L
        }
    }

    @Synchronized
    private fun rolloverDateIfNeeded() {
        val today = LocalDate.now().toString()
        if (today != trackedDateString) {
            trackedDateString = today
            internalDailySecondsMap.clear()
            currentSessionStartEpochMs = System.currentTimeMillis()
        }
    }

    /**
     * Returns today's usage in seconds for a specific package by combining
     * exact UsageEvents calculation and internal foreground session tracking.
     */
    fun getTodayUsageSecondsForPackage(
        packageName: String,
        persistedSeconds: Long = 0L
    ): Long {
        rolloverDateIfNeeded()
        val usageStatsSeconds = queryTodayUsageSecondsMap()[packageName] ?: 0L
        val internalCommitted = internalDailySecondsMap[packageName] ?: 0L
        val activeOngoingSeconds = if (currentForegroundPackage == packageName && currentSessionStartEpochMs > 0L) {
            ((System.currentTimeMillis() - currentSessionStartEpochMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
        val internalTotal = internalCommitted + activeOngoingSeconds
        return maxOf(usageStatsSeconds, internalTotal, persistedSeconds)
    }

    /**
     * Queries UsageStatsManager for all packages used since 00:00 local time today.
     * Uses UsageEvents (ACTIVITY_RESUMED / MOVE_TO_FOREGROUND -> ACTIVITY_PAUSED / MOVE_TO_BACKGROUND)
     * for high precision, falling back to queryAndAggregateUsageStats if events are empty.
     */
    fun queryTodayUsageSecondsMap(): Map<String, Long> {
        rolloverDateIfNeeded()
        if (!hasUsageStatsPermission() || usageStatsManager == null) {
            return getInternalSnapshotWithActiveSession()
        }

        val zoneId = ZoneId.systemDefault()
        val startOfDayEpochMs = LocalDate.now(zoneId)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val nowEpochMs = System.currentTimeMillis()

        val durationMillisMap = HashMap<String, Long>()
        val foregroundStartMap = HashMap<String, Long>()

        try {
            val usageEvents: UsageEvents? = usageStatsManager.queryEvents(startOfDayEpochMs, nowEpochMs)
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    val timestamp = event.timeStamp

                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            foregroundStartMap[pkg] = timestamp
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            val startTs = foregroundStartMap.remove(pkg)
                            if (startTs != null && timestamp > startTs) {
                                val delta = (timestamp - startTs).coerceAtMost(4 * 3600 * 1000L)
                                durationMillisMap[pkg] = (durationMillisMap[pkg] ?: 0L) + delta
                            }
                        }
                        UsageEvents.Event.DEVICE_SHUTDOWN -> {
                            for ((activePkg, startTs) in foregroundStartMap) {
                                if (timestamp > startTs) {
                                    val delta = (timestamp - startTs).coerceAtMost(4 * 3600 * 1000L)
                                    durationMillisMap[activePkg] = (durationMillisMap[activePkg] ?: 0L) + delta
                                }
                            }
                            foregroundStartMap.clear()
                        }
                    }
                }

                // Account for any package still in the foreground right now
                for ((activePkg, startTs) in foregroundStartMap) {
                    if (nowEpochMs > startTs) {
                        val delta = (nowEpochMs - startTs).coerceAtMost(2 * 3600 * 1000L)
                        durationMillisMap[activePkg] = (durationMillisMap[activePkg] ?: 0L) + delta
                    }
                }
            }

            if (durationMillisMap.isEmpty()) {
                val aggregated = usageStatsManager.queryAndAggregateUsageStats(startOfDayEpochMs, nowEpochMs)
                for ((pkg, stats) in aggregated) {
                    if (stats.totalTimeInForeground > 0L) {
                        durationMillisMap[pkg] = stats.totalTimeInForeground
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to internal tracking map if OEM UsageStats throws security/binder exception
        }

        val combinedSeconds = HashMap<String, Long>()
        for ((pkg, millis) in durationMillisMap) {
            combinedSeconds[pkg] = millis / 1000L
        }

        val internalSnapshot = getInternalSnapshotWithActiveSession()
        for ((pkg, internalSec) in internalSnapshot) {
            val current = combinedSeconds[pkg] ?: 0L
            if (internalSec > current) {
                combinedSeconds[pkg] = internalSec
            }
        }
        return combinedSeconds
    }

    private fun getInternalSnapshotWithActiveSession(): Map<String, Long> {
        val result = HashMap<String, Long>(internalDailySecondsMap)
        val activePkg = currentForegroundPackage
        val startMs = currentSessionStartEpochMs
        if (activePkg != null && startMs > 0L) {
            val ongoingSec = ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
            result[activePkg] = (result[activePkg] ?: 0L) + ongoingSec
        }
        return result
    }

    /**
     * Discovers all launchable apps on the device plus the pre-configured High-Risk Social Media apps
     * (Instagram, YouTube, TikTok, Facebook).
     */
    fun getInstalledAndTargetApps(): List<InstalledAppInfo> {
        val discoveredMap = LinkedHashMap<String, InstalledAppInfo>()

        // 1. Seed High-Risk targets first so they always appear prominently at the top
        for ((pkg, defaultLabel) in AppBlockRule.HIGH_RISK_TARGETS) {
            val installedInfo = try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { defaultLabel }
                val icon = packageManager.getApplicationIcon(appInfo)
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    isHighRisk = true,
                    isInstalledOnDevice = true,
                    icon = icon
                )
            } catch (_: PackageManager.NameNotFoundException) {
                InstalledAppInfo(
                    packageName = pkg,
                    appName = defaultLabel,
                    isHighRisk = true,
                    isInstalledOnDevice = false,
                    icon = null
                )
            }
            discoveredMap[pkg] = installedInfo
        }

        // 2. Query all launchable apps installed on the device
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = try {
            packageManager.queryIntentActivities(mainIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        for (resolveInfo in resolveInfos) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == context.packageName) continue
            if (pkg == "com.android.settings") continue
            if (discoveredMap.containsKey(pkg)) continue

            val appInfo = activityInfo.applicationInfo
            val isSystemApp = appInfo != null &&
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)

            // Include all user-installed apps and updated system apps (like Chrome/YouTube/Gmail)
            if (!isSystemApp || isCommonDistractingSystemApp(pkg)) {
                val label = try {
                    resolveInfo.loadLabel(packageManager).toString().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (_: Exception) {
                    null
                }
                discoveredMap[pkg] = InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    isHighRisk = AppBlockRule.HIGH_RISK_TARGETS.containsKey(pkg),
                    isInstalledOnDevice = true,
                    icon = icon
                )
            }
        }

        return discoveredMap.values.sortedWith(
            compareByDescending<InstalledAppInfo> { it.isHighRisk }
                .thenByDescending { it.isInstalledOnDevice }
                .thenBy { it.appName.lowercase() }
        )
    }

    fun resolveAppName(packageName: String): String {
        AppBlockRule.HIGH_RISK_TARGETS[packageName]?.let { return it }
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun isCommonDistractingSystemApp(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("youtube") ||
            lower.contains("chrome") ||
            lower.contains("browser") ||
            lower.contains("facebook") ||
            lower.contains("instagram") ||
            lower.contains("tiktok") ||
            lower.contains("netflix") ||
            lower.contains("reddit") ||
            lower.contains("twitter") ||
            lower.contains("snapchat")
    }

    companion object {
        @Volatile
        private var INSTANCE: UsageTrackerHelper? = null

        fun getInstance(context: Context): UsageTrackerHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UsageTrackerHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
