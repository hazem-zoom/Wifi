package com.hazem.wifiusage

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Local storage: one row per sample = (ssid, time, bytes used since the previous sample). */
class UsageDb(context: Context) : SQLiteOpenHelper(context, "usage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (ts INTEGER NOT NULL, ssid TEXT NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ts ON samples(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun insert(ts: Long, ssid: String, bytes: Long) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("ts", ts); put("ssid", ssid); put("bytes", bytes)
        })
    }

    /** Total bytes for a network (or all networks when ssid == null) inside [from, to]. */
    fun total(ssid: String?, from: Long, to: Long): Long {
        val sql = "SELECT COALESCE(SUM(bytes),0) FROM samples WHERE ts BETWEEN ? AND ?" +
            if (ssid != null) " AND ssid = ?" else ""
        val args = if (ssid != null) arrayOf("$from", "$to", ssid) else arrayOf("$from", "$to")
        readableDatabase.rawQuery(sql, args).use { c -> c.moveToFirst(); return c.getLong(0) }
    }

    fun knownSsids(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT ssid FROM samples ORDER BY ssid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
}

object WifiUsage {

    /** Total Wi-Fi bytes (rx + tx) on the device between two timestamps, straight from the system. */
    fun systemWifiBytes(context: Context, from: Long, to: Long): Long {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val bucket = NetworkStats.Bucket()
        val stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, from, to)
        var total = 0L
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            total += bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        return total
    }

    /** Current Wi-Fi network name, or null when not on Wi-Fi. Needs location permission. */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return null
        return if (ssid == "<unknown ssid>" || ssid.isBlank()) null else ssid
    }
}

/**
 * Runs every ~15 min (WorkManager minimum). Takes the Wi-Fi bytes used since the last run
 * and attributes them to the network we're connected to right now.
 */
class SampleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_sample", now - 15 * 60 * 1000)

        val bytes = WifiUsage.systemWifiBytes(applicationContext, last, now)
        val ssid = WifiUsage.currentSsid(applicationContext)

        if (bytes > 0 && ssid != null) {
            UsageDb(applicationContext).insert(now, ssid, bytes)
        }
        prefs.edit().putLong("last_sample", now).apply()
        return Result.success()
    }
}
