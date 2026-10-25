package com.hazem.wifiusage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.usage.NetworkStats
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Colors for light / dark mode. Set once in onCreate before the UI is built. */
object Palette {
    var bg = 0
    var card = 0
    var text = 0
    var muted = 0
    var primary = 0
    var primaryEnd = 0
    var track = 0

    fun apply(dark: Boolean) {
        if (dark) {
            bg = 0xFF0F1220.toInt(); card = 0xFF1B2033.toInt(); text = 0xFFF1F3FA.toInt()
            muted = 0xFF9AA3B8.toInt(); primary = 0xFF5C7CFA.toInt(); primaryEnd = 0xFF845EF7.toInt()
            track = 0xFF2A3050.toInt()
        } else {
            bg = 0xFFF4F6FB.toInt(); card = Color.WHITE; text = 0xFF1B1F3B.toInt()
            muted = 0xFF6B7280.toInt(); primary = 0xFF3B5BDB.toInt(); primaryEnd = 0xFF7048E8.toInt()
            track = 0xFFE5E7EB.toInt()
        }
    }
}

private const val ORANGE = 0xFFF59F00.toInt()
private const val RED = 0xFFE03131.toInt()
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var db: UsageDb

    // 0 = today, 1 = 7 days, 2 = 30 days, 3 = plan cycle, 4 = custom
    private var mode = 0
    private var customFrom = 0L
    private var customTo = 0L
    private var selectedSsid: String? = null
    private var askedPerms = false
    private var appsToken = 0
    private var dialogShown = false
    private lateinit var keepAlivePill: TextView

    // Live updates while the app is on screen
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            Thread {
                WifiUsage.sampleNow(applicationContext)
                runOnUiThread {
                    tick++
                    refresh(withApps = tick % 6 == 0)
                }
            }.start()
            handler.postDelayed(this, 5000)
        }
    }

    private lateinit var heroValue: TextView
    private lateinit var heroRange: TextView
    private lateinit var subtitle: TextView
    private lateinit var networkValue: TextView
    private lateinit var chart: BarChartView
    private lateinit var limitBox: LinearLayout
    private lateinit var networksBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private val chips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = isDark()
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        Palette.apply(dark)
        db = UsageDb(this)

        savedInstanceState?.let {
            mode = it.getInt("mode", 0)
            selectedSsid = it.getString("ssid")
            customFrom = it.getLong("cf", 0L)
            customTo = it.getLong("ct", 0L)
        }
        buildUi(dark)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode)
        outState.putString("ssid", selectedSsid)
        outState.putLong("cf", customFrom)
        outState.putLong("ct", customTo)
    }

    private fun isDark(): Boolean = when (Prefs.themeMode(this)) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // ───────────────────────── UI ─────────────────────────

    @Suppress("DEPRECATION")
    private fun buildUi(dark: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Palette.bg)
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            text("استهلاك الواي فاي", 24f, Palette.text, bold = true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        header.addView(pill("⟳ تحديث") { manualRefresh() }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) })
        root.addView(header)
        subtitle = text("", 13f, Palette.muted).apply {
            setPadding(0, dp(6), 0, dp(16))
            setOnClickListener { fixSsid() }
        }
        root.addView(subtitle)

        // Hero card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.primary, Palette.primaryEnd)
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
        val labels = listOf("اليوم", "7 أيام", "30 يوم", "الباقة", "مخصص")
        labels.forEachIndexed { i, label ->
            val chip = text(label, 13f, Palette.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { if (i == 4) pickCustomRange() else { mode = i; refresh() } }
            }
            chips.add(chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = if (i < labels.size - 1) dp(6) else 0
            })
        }
        root.addView(chipRow, matchWrap(bottom = 16))

        // Network card
        val netCard = card().apply {
            setOnClickListener { chooseNetwork() }
            isClickable = true
        }
        netCard.addView(text("الشبكة", 12f, Palette.muted))
        val netRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        networkValue = text("كل الشبكات", 18f, Palette.text, bold = true)
        netRow.addView(networkValue, LinearLayout.LayoutParams(0, WRAP, 1f))
        netRow.addView(text("▾", 18f, Palette.muted))
        netCard.addView(netRow)
        root.addView(netCard, matchWrap(bottom = 10))

        // WhatsApp button: tap = send, long-press = change the saved number
        val waButton = text("إرسال على واتساب", 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply { setColor(0xFF25D366.toInt()); cornerRadius = dp(16).toFloat() }
            setOnClickListener { sendToWhatsApp() }
            setOnLongClickListener { changeWhatsAppNumber(); true }
        }
        root.addView(waButton, matchWrap(bottom = 4))
        root.addView(
            text("اضغط مطوّلاً على الزرار لتغيير الرقم المحفوظ لهذه الشبكة", 11f, Palette.muted)
                .apply { setPadding(0, 0, 0, dp(16)) },
            matchWrap()
        )

        // Plan / limit card
        limitBox = card().apply {
            isClickable = true
            setOnClickListener { showPlanDialog() }
        }
        root.addView(limitBox, matchWrap(bottom = 16))

        // Daily chart card
        val chartCard = card()
        chartCard.addView(text("الاستهلاك يوم بيوم", 14f, Palette.text, bold = true))
        chart = BarChartView(this)
        chartCard.addView(chart, LinearLayout.LayoutParams(MATCH, dp(170)).apply { topMargin = dp(12) })
        root.addView(chartCard, matchWrap(bottom = 16))

        // Networks comparison
        val netsCard = card()
        netsCard.addView(text("مقارنة الشبكات", 14f, Palette.text, bold = true))
        netsCard.addView(text("اضغط على شبكة لعرضها", 11f, Palette.muted))
        networksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        netsCard.addView(networksBox)
        root.addView(netsCard, matchWrap(bottom = 16))

        // Top apps
        val appsCard = card()
        appsCard.addView(text("أكتر التطبيقات استهلاكاً", 14f, Palette.text, bold = true))
        appsCard.addView(text("على كل شبكات الواي فاي في الفترة المختارة", 11f, Palette.muted))
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        appsCard.addView(appsBox)
        root.addView(appsCard, matchWrap(bottom = 16))

        // Tools
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(pill("إعدادات الباقة") { showPlanDialog() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        tools.addView(pill("المظهر") { showThemeDialog() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        tools.addView(pill("تصدير CSV") { exportCsv() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(tools, matchWrap(bottom = 8))

        keepAlivePill = pill("") { toggleKeepAlive() }
        root.addView(keepAlivePill, matchWrap(bottom = 4))
        root.addView(
            text("بيعمل إشعار ثابت صغير عشان أندرويد ما يقفلش التطبيق، وبيشتغل تلقائي بعد إعادة تشغيل الموبايل.", 11f, Palette.muted)
                .apply { setPadding(0, 0, 0, dp(16)) },
            matchWrap()
        )
        updateKeepAlivePill()

        root.addView(text("بيتحدّث تلقائياً كل 15 دقيقة، والبيانات بتبدأ من أول مرة فتحت فيها التطبيق.", 11f, Palette.muted))

        // Credit
        root.addView(
            text("Developed by Hazem Abdallah", 12f, Palette.muted, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            },
            matchWrap()
        )

        // Keep content below the status bar on every Android version
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(20), insets.systemWindowInsetTop + dp(16), dp(20), insets.systemWindowInsetBottom + dp(24))
            insets
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.bg)
            isFillViewport = true
            addView(root)
        })
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        window.decorView.systemUiVisibility =
            if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(20).toFloat() }
    }

    private fun pill(label: String, onClick: () -> Unit) = text(label, 13f, Palette.primary, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(14), dp(4), dp(14))
        background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(14).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A labelled row with a size and a thin bar, used by the network and app lists. */
    private fun usageRow(
        label: String, bytes: Long, max: Long, selected: Boolean = false, onClick: (() -> Unit)? = null
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            text(label, 14f, if (selected) Palette.primary else Palette.text, bold = selected),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        top.addView(text(Fmt.text(bytes), 13f, Palette.muted))
        col.addView(top)
        col.addView(
            ProgressBarView(this).apply {
                fraction = bytes.toFloat() / max
                color = Palette.primary
            },
            LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(6) }
        )
        if (onClick != null) col.setOnClickListener { onClick() }
        return col
    }

    // ───────────────────────── Lifecycle / permissions ─────────────────────────

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            Toast.makeText(this, "فعّل Usage access للتطبيق", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (!askedPerms) {
            askedPerms = true
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 1)
                return
            }
        }
        start()
    }

    private fun start() {
        startTracking()
        NetworkWatcher.register(applicationContext)
        applyKeepAlive()
        refresh()
        maybeAskBackgroundLocation()
        maybeAskBattery()
        // Sample + refresh every 5 seconds while the app is open
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /** The "تحديث" button: take a sample now, and ask for location if it's missing. */
    private fun manualRefresh() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
            return
        }
        Toast.makeText(this, "جاري التحديث...", Toast.LENGTH_SHORT).show()
        Thread {
            WifiUsage.sampleNow(applicationContext)
            runOnUiThread {
                refresh()
                Toast.makeText(this, "تم التحديث", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Why the network name can't be read, or null if everything needed is on. */
    private fun ssidProblem(): String? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "اسمح للتطبيق بالوصول للموقع عشان اسم الشبكة يظهر (اضغط هنا)"
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            return "شغّل الموقع (GPS) عشان اسم الشبكة يظهر (اضغط هنا)"
        }
        return null
    }

    /** Tap on the status line: fix whatever is stopping the network name from showing. */
    private fun fixSsid() {
        when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
            ssidProblem() != null -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> manualRefresh()
        }
    }

    private fun startTracking() {
        val work = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wifi_sampler", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /** Reading the Wi-Fi name in the background needs "Allow all the time" for location. Ask once. */
    private fun maybeAskBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        val prefs = Prefs.of(this)
        if (prefs.getBoolean("bg_asked", false)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("bg_asked", true).apply()
        dialogShown = true
        AlertDialog.Builder(this)
            .setTitle("خطوة مهمة للدقة")
            .setMessage("عشان التطبيق يعرف اسم الشبكة وهو شغال في الخلفية، افتح الإعدادات > الأذونات > الموقع، واختار «السماح طوال الوقت».")
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    // ───────────────────────── Always-on tracking ─────────────────────────

    private fun toggleKeepAlive() {
        Prefs.setKeepAlive(this, !Prefs.keepAlive(this))
        applyKeepAlive()
        updateKeepAlivePill()
    }

    private fun updateKeepAlivePill() {
        keepAlivePill.text =
            if (Prefs.keepAlive(this)) "التشغيل الدائم: مفعّل ✓" else "التشغيل الدائم: متوقف"
    }

    private fun applyKeepAlive() {
        val svc = Intent(this, TrackerService::class.java)
        if (Prefs.keepAlive(this)) {
            try { startForegroundService(svc) } catch (e: Exception) { }
        } else {
            stopService(svc)
        }
    }

    /** Ask once to be exempt from battery optimisation, so Android doesn't stop the tracking. */
    private fun maybeAskBattery() {
        if (dialogShown) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = Prefs.of(this)
        if (prefs.getBoolean("batt_asked", false)) return
        prefs.edit().putBoolean("batt_asked", true).apply()
        dialogShown = true
        AlertDialog.Builder(this)
            .setTitle("عشان التتبع ما يتقطعش")
            .setMessage("اسمح للتطبيق يشتغل في الخلفية بدون قيود البطارية.")
            .setPositiveButton("سماح") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
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
            0 -> Billing.startOfDay(0) to now
            1 -> Billing.startOfDay(6) to now
            2 -> Billing.startOfDay(29) to now
            3 -> Billing.cycleStart(Prefs.renewalDay(this, selectedSsid), now) to now
            else -> customFrom to customTo
        }
    }

    private fun refresh(withApps: Boolean = true) {
        val (from, to) = range()
        val bytes = db.total(selectedSsid, from, to)

        // Hero number (big number, smaller unit)
        val (num, unit) = Fmt.split(bytes)
        heroValue.text = SpannableString("$num $unit").apply {
            setSpan(RelativeSizeSpan(0.4f), num.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)
        heroRange.text = "من ${f.format(from)}  إلى ${f.format(to)}"

        // Chips highlight
        chips.forEachIndexed { i, c ->
            val on = i == mode
            c.setTextColor(if (on) Color.WHITE else Palette.text)
            c.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.background = GradientDrawable().apply {
                setColor(if (on) Palette.primary else Palette.card); cornerRadius = dp(14).toFloat()
            }
        }

        networkValue.text = selectedSsid ?: "كل الشبكات"
        val live = WifiUsage.currentSsid(this)
        when {
            live != null -> {
                subtitle.text = "متصل الآن بـ $live · تحديث مباشر"
                subtitle.setTextColor(Palette.muted)
            }
            WifiUsage.isOnWifi(this) -> {
                subtitle.text = "⚠ " + (ssidProblem() ?: "متصل بواي فاي بس مش قادر أقرأ اسمه (اضغط للتحديث)")
                subtitle.setTextColor(ORANGE)
            }
            else -> {
                subtitle.text = "غير متصل بشبكة واي فاي"
                subtitle.setTextColor(Palette.muted)
            }
        }

        updateLimitCard()
        updateChart(from, to)
        updateNetworks(from, to)
        if (withApps) loadApps(from, to)
    }

    private fun updateLimitCard() {
        limitBox.removeAllViews()
        limitBox.addView(text("الباقة", 14f, Palette.text, bold = true))

        val limit = Prefs.limitGb(this, selectedSsid)
        if (limit <= 0f) {
            limitBox.addView(
                text("لم تحدد حد أقصى لهذه الشبكة. اضغط هنا لتحديده.", 12f, Palette.muted)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
            return
        }

        val now = System.currentTimeMillis()
        val renew = Prefs.renewalDay(this, selectedSsid)
        val start = Billing.cycleStart(renew, now)
        val end = Billing.nextCycleStart(renew, now)
        val usedGb = db.total(selectedSsid, start, now) / Fmt.GB

        val dayMs = 86_400_000.0
        val daysLeft = Math.ceil((end - now) / dayMs).toInt().coerceAtLeast(1)
        val elapsedDays = ((now - start) / dayMs).coerceAtLeast(1.0)
        val remaining = (limit - usedGb).coerceAtLeast(0.0)
        val frac = (usedGb / limit).toFloat()

        limitBox.addView(
            text("%.1f / %.0f GB".format(Locale.US, usedGb, limit.toDouble()), 22f, Palette.text, bold = true)
                .apply { setPadding(0, dp(6), 0, dp(8)) }
        )
        limitBox.addView(
            ProgressBarView(this).apply {
                fraction = frac
                color = when {
                    frac >= 1f -> RED
                    frac >= 0.8f -> ORANGE
                    else -> Palette.primary
                }
            },
            LinearLayout.LayoutParams(MATCH, dp(10))
        )

        val line1 = if (usedGb >= limit) {
            "تجاوزت الباقة بـ %.1f GB".format(Locale.US, usedGb - limit)
        } else {
            "باقي $daysLeft يوم · المسموح يومياً %.2f GB".format(Locale.US, remaining / daysLeft)
        }
        limitBox.addView(
            text(line1, 12f, if (usedGb >= limit) RED else Palette.muted).apply { setPadding(0, dp(10), 0, 0) }
        )
        limitBox.addView(
            text(
                "متوسط استهلاكك اليومي: %.2f GB · التجديد يوم $renew".format(Locale.US, usedGb / elapsedDays),
                12f, Palette.muted
            ).apply { setPadding(0, dp(2), 0, 0) }
        )
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
        chart.labels = shown.map {
            Calendar.getInstance().apply { timeInMillis = it.first }[Calendar.DAY_OF_MONTH].toString()
        }
        chart.invalidate()
    }

    private fun updateNetworks(from: Long, to: Long) {
        networksBox.removeAllViews()
        val rows = db.ssidTotals(from, to)
        if (rows.isEmpty()) {
            networksBox.addView(text("لا توجد بيانات بعد", 12f, Palette.muted))
            return
        }
        val max = rows.maxOf { it.second }.coerceAtLeast(1L)
        for ((name, b) in rows) {
            networksBox.addView(usageRow(name, b, max, selected = name == selectedSsid) {
                selectedSsid = name
                refresh()
            })
        }
    }

    // ───────────────────────── Per-app usage ─────────────────────────

    private fun loadApps(from: Long, to: Long) {
        val token = ++appsToken
        Thread {
            val rows = try { topApps(from, to) } catch (e: Exception) { emptyList() }
            runOnUiThread { if (token == appsToken) showApps(rows) }
        }.start()
    }

    private fun showApps(rows: List<Pair<String, Long>>) {
        appsBox.removeAllViews()
        if (rows.isEmpty()) {
            appsBox.addView(text("لا توجد بيانات", 12f, Palette.muted))
            return
        }
        val max = rows.first().second.coerceAtLeast(1L)
        for ((name, b) in rows) appsBox.addView(usageRow(name, b, max))
    }

    private fun topApps(from: Long, to: Long): List<Pair<String, Long>> {
        val perUid = WifiUsage.appBytes(this, from, to)
        val byName = HashMap<String, Long>()
        for ((uid, b) in perUid) {
            if (b <= 0) continue
            val name = when (uid) {
                NetworkStats.Bucket.UID_REMOVED -> "تطبيقات محذوفة"
                NetworkStats.Bucket.UID_TETHERING -> "مشاركة الإنترنت (Hotspot)"
                0, 1000 -> "النظام"
                else -> labelFor(uid)
            }
            byName[name] = (byName[name] ?: 0L) + b
        }
        return byName.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
    }

    @Suppress("DEPRECATION")
    private fun labelFor(uid: Int): String {
        val pm = packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "uid $uid"
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

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

    private fun showPlanDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val currentLimit = Prefs.limitGb(this, selectedSsid)
        val limitInput = EditText(this).apply {
            hint = "مثال: 200"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            if (currentLimit > 0f) setText(currentLimit.toString().removeSuffix(".0"))
        }
        val dayInput = EditText(this).apply {
            hint = "من 1 إلى 31"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setText(Prefs.renewalDay(this@MainActivity, selectedSsid).toString())
        }
        box.addView(text("الحد الأقصى للباقة (GB) — اتركه فاضي لإلغائه", 12f, Palette.muted))
        box.addView(limitInput)
        box.addView(text("يوم تجديد الباقة في الشهر", 12f, Palette.muted).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(dayInput)

        AlertDialog.Builder(this)
            .setTitle("باقة ${selectedSsid ?: "كل الشبكات"}")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val limit = (limitInput.text.toString().toFloatOrNull() ?: 0f).coerceAtLeast(0f)
                val day = (dayInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 31)
                Prefs.setPlan(this, selectedSsid, limit, day)
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showThemeDialog() {
        AlertDialog.Builder(this)
            .setTitle("المظهر")
            .setSingleChoiceItems(arrayOf("تلقائي (حسب الموبايل)", "فاتح", "داكن"), Prefs.themeMode(this)) { d, which ->
                Prefs.of(this).edit().putInt("theme", which).apply()
                d.dismiss()
                recreate()
            }
            .show()
    }

    private fun pickCustomRange() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val end = Calendar.getInstance().apply { set(y2, m2, d2, 23, 59, 59) }.timeInMillis
                customFrom = start; customTo = end; mode = 4
                refresh()
            }, y, m, d).apply { setTitle("تاريخ النهاية") }.show()
        }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH])
            .apply { setTitle("تاريخ البداية") }.show()
    }

    // ───────────────────────── Export ─────────────────────────

    private fun exportCsv() {
        val (from, to) = range()
        val rows = db.dailyRows(selectedSsid, from, to)
        if (rows.isEmpty()) {
            Toast.makeText(this, "مفيش بيانات للفترة دي", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("﻿date,network,usage_mb\n")
        for ((day, ssid, bytes) in rows) {
            sb.append(day).append(",\"").append(ssid.replace("\"", "\"\"")).append("\",")
                .append("%.2f".format(Locale.US, bytes / 1_048_576.0)).append('\n')
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "wifi_usage.csv")
        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "تصدير الاستهلاك"))
    }

    // ───────────────────────── WhatsApp ─────────────────────────

    private fun sendToWhatsApp() {
        val saved = Prefs.whatsapp(this, selectedSsid)
        if (saved == null) askNumber("حفظ وإرسال") { sendTo(it) } else sendTo(saved)
    }

    private fun changeWhatsAppNumber() {
        askNumber("حفظ") { Toast.makeText(this, "تم حفظ الرقم", Toast.LENGTH_SHORT).show() }
    }

    private fun askNumber(positive: String, then: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "مثال: 01012345678"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setText(Prefs.whatsapp(this@MainActivity, selectedSsid) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("رقم واتساب اللي هيستلم الاستهلاك")
            .setMessage("الشبكة: ${selectedSsid ?: "كل الشبكات"}")
            .setView(input)
            .setPositiveButton(positive) { _, _ ->
                val n = normalizeNumber(input.text.toString())
                if (n.length < 8) {
                    Toast.makeText(this, "الرقم غير صحيح", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setWhatsapp(this, selectedSsid, n)
                    then(n)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** Digits only, with the country code. A local Egyptian number (01xxxxxxxxx) becomes 201xxxxxxxxx. */
    private fun normalizeNumber(raw: String): String {
        var n = raw.filter { it.isDigit() }
        if (n.startsWith("00")) n = n.drop(2)
        if (n.startsWith("01") && n.length == 11) n = "2$n"
        return n
    }

    private fun sendTo(number: String) {
        val (from, to) = range()
        val usage = Fmt.text(db.total(selectedSsid, from, to))
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)
        val msg = "استهلاك ${selectedSsid ?: "كل الشبكات"}\n" +
            "من ${f.format(from)} إلى ${f.format(to)}\n" +
            usage
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(msg))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "واتساب غير متاح على الجهاز", Toast.LENGTH_LONG).show()
        }
    }
}

/** Thin rounded progress bar. */
class ProgressBarView(context: Context) : View(context) {

    var fraction = 0f
    var color = Palette.primary

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        trackPaint.color = Palette.track
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, trackPaint)

        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        fillPaint.color = color
        val fw = (w * f).coerceAtLeast(h)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val rect = if (rtl) RectF(w - fw, 0f, w, h) else RectF(0f, 0f, fw, h)
        c.drawRoundRect(rect, r, r, fillPaint)
    }
}

/** Simple bar chart: one bar per day. */
class BarChartView(context: Context) : View(context) {

    var values: List<Long> = emptyList()
    var labels: List<String> = emptyList()

    private val d = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.primary }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted; textSize = 11 * d; textAlign = Paint.Align.CENTER
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
