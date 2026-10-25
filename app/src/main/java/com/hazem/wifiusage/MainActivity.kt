package com.hazem.wifiusage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val BG = 0xFFF4F6FB.toInt()
private const val PRIMARY = 0xFF3B5BDB.toInt()
private const val PRIMARY_END = 0xFF7048E8.toInt()
private const val TEXT = 0xFF1B1F3B.toInt()
private const val MUTED = 0xFF6B7280.toInt()
private const val CARD = Color.WHITE

class MainActivity : Activity() {

    private lateinit var db: UsageDb

    // 0 = today, 1 = 7 days, 2 = 30 days, 3 = custom
    private var mode = 0
    private var customFrom = 0L
    private var customTo = 0L
    private var selectedSsid: String? = null

    private lateinit var heroValue: TextView
    private lateinit var heroRange: TextView
    private lateinit var subtitle: TextView
    private lateinit var networkValue: TextView
    private lateinit var chart: BarChartView
    private val chips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = UsageDb(this)
        buildUi()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Header
        root.addView(text("استهلاك الواي فاي", 24f, TEXT, bold = true))
        subtitle = text("", 13f, MUTED).apply { setPadding(0, dp(2), 0, dp(16)) }
        root.addView(subtitle)

        // Hero card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(PRIMARY, PRIMARY_END)
            ).apply { cornerRadius = dp(24).toFloat() }
        }
        hero.addView(text("إجمالي الاستهلاك", 14f, 0xCCFFFFFF.toInt()))
        heroValue = text("0 MB", 46f, Color.WHITE, bold = true).apply { setPadding(0, dp(6), 0, dp(6)) }
        hero.addView(heroValue)
        heroRange = text("", 12f, 0xB3FFFFFF.toInt())
        hero.addView(heroRange)
        root.addView(hero, matchWrap(bottom = 16))

        // Time chips
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("اليوم", "7 أيام", "30 يوم", "مخصص").forEachIndexed { i, label ->
            val chip = text(label, 14f, TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { if (i == 3) pickCustomRange() else { mode = i; refresh() } }
            }
            chips.add(chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i < 3) dp(8) else 0
            })
        }
        root.addView(chipRow, matchWrap(bottom = 16))

        // Network card
        val netCard = card().apply {
            setOnClickListener { chooseNetwork() }
            isClickable = true
        }
        netCard.addView(text("الشبكة", 12f, MUTED))
        val netRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        networkValue = text("كل الشبكات", 18f, TEXT, bold = true)
        netRow.addView(networkValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        netRow.addView(text("▾", 18f, MUTED))
        netCard.addView(netRow)
        root.addView(netCard, matchWrap(bottom = 16))

        // Chart card
        val chartCard = card()
        chartCard.addView(text("الاستهلاك يوم بيوم", 14f, TEXT, bold = true))
        chart = BarChartView(this)
        chartCard.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply {
            topMargin = dp(12)
        })
        root.addView(chartCard, matchWrap(bottom = 12))

        root.addView(text("بيتحدّث تلقائياً كل 15 دقيقة، والبيانات بتبدأ من أول مرة فتحت فيها التطبيق.", 11f, MUTED))

        // Keep content below the status bar on every Android version
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(20), insets.systemWindowInsetTop + dp(16), dp(20), insets.systemWindowInsetBottom + dp(24))
            insets
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
        window.statusBarColor = BG
        window.navigationBarColor = BG
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ───────────────────────── Lifecycle / permissions ─────────────────────────

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            Toast.makeText(this, "فعّل Usage access للتطبيق", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        startTracking()
        refresh()
        // Take a sample right away so the network list isn't empty on first launch
        Thread {
            WifiUsage.sampleNow(applicationContext)
            runOnUiThread { refresh() }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        onResume()
    }

    private fun startTracking() {
        val work = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wifi_sampler", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    // ───────────────────────── Data ─────────────────────────

    private fun range(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (mode) {
            0 -> startOfDay(0) to now
            1 -> startOfDay(6) to now
            2 -> startOfDay(29) to now
            else -> customFrom to customTo
        }
    }

    private fun refresh() {
        val (from, to) = range()
        val bytes = db.total(selectedSsid, from, to)

        // Hero number (big number, smaller unit)
        val (num, unit) = splitBytes(bytes)
        heroValue.text = SpannableString("$num $unit").apply {
            setSpan(RelativeSizeSpan(0.4f), num.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)
        heroRange.text = "من ${f.format(from)}  إلى ${f.format(to)}"

        // Chips highlight
        chips.forEachIndexed { i, c ->
            val on = i == mode
            c.setTextColor(if (on) Color.WHITE else TEXT)
            c.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.background = GradientDrawable().apply {
                setColor(if (on) PRIMARY else CARD); cornerRadius = dp(14).toFloat()
            }
        }

        networkValue.text = selectedSsid ?: "كل الشبكات"
        val now = WifiUsage.currentSsid(this)
        subtitle.text = if (now != null) "متصل الآن بـ $now" else "غير متصل بشبكة واي فاي"

        updateChart(from, to)
    }

    private fun updateChart(from: Long, to: Long) {
        val days = mutableListOf<Pair<Long, Long>>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= to) {
            val s = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            days.add(s to minOf(cal.timeInMillis - 1, to))
        }
        val shown = days.takeLast(31)
        chart.values = shown.map { db.total(selectedSsid, it.first, it.second) }
        chart.labels = shown.map { Calendar.getInstance().apply { timeInMillis = it.first }[Calendar.DAY_OF_MONTH].toString() }
        chart.invalidate()
    }

    private fun splitBytes(b: Long): Pair<String, String> =
        if (b >= 1_073_741_824L) "%.2f".format(Locale.US, b / 1_073_741_824.0) to "GB"
        else "%.0f".format(Locale.US, b / 1_048_576.0) to "MB"

    // ───────────────────────── Dialogs ─────────────────────────

    private fun chooseNetwork() {
        val known = db.knownSsids()
        val current = WifiUsage.currentSsid(this)
        val all = (known + listOfNotNull(current)).distinct()
        val labels = listOf("كل الشبكات") + all.map { if (it == current) "$it  (متصل الآن)" else it }
        AlertDialog.Builder(this)
            .setTitle("اختر الشبكة")
            .setItems(labels.toTypedArray()) { _, i ->
                selectedSsid = if (i == 0) null else all[i - 1]
                refresh()
            }
            .show()
    }

    private fun pickCustomRange() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val end = Calendar.getInstance().apply { set(y2, m2, d2, 23, 59, 59) }.timeInMillis
                customFrom = start; customTo = end; mode = 3
                refresh()
            }, y, m, d).apply { setTitle("تاريخ النهاية") }.show()
        }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH])
            .apply { setTitle("تاريخ البداية") }.show()
    }

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Simple bar chart: one bar per day. */
class BarChartView(context: Context) : View(context) {

    var values: List<Long> = emptyList()
    var labels: List<String> = emptyList()

    private val d = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5E7EB.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED; textSize = 11 * d; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n == 0) return
        val max = values.max().coerceAtLeast(1L)
        val labelH = 22 * d
        val chartH = height - labelH
        val slot = width.toFloat() / n
        val barW = slot * 0.62f
        val step = if (n > 12) (n + 11) / 12 else 1

        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val h = (chartH * values[i] / max).coerceAtLeast(4 * d)
            val rect = RectF(cx - barW / 2, chartH - h, cx + barW / 2, chartH)
            c.drawRoundRect(rect, 6 * d, 6 * d, if (values[i] > 0) barPaint else emptyPaint)
            if (i % step == 0) c.drawText(labels[i], cx, height - 4 * d, textPaint)
        }
    }
}
