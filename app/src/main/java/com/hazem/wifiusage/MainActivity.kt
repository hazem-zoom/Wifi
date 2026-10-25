package com.hazem.wifiusage

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var db: UsageDb
    private lateinit var spinner: Spinner
    private lateinit var result: TextView
    private lateinit var rangeLabel: TextView

    private var from = startOfDay(0)
    private var to = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = UsageDb(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        spinner = Spinner(this)
        rangeLabel = TextView(this).apply { textSize = 14f; setPadding(0, 24, 0, 24) }
        result = TextView(this).apply {
            textSize = 40f; gravity = Gravity.CENTER; setPadding(0, 64, 0, 0)
        }

        fun chip(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
        }

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip("اليوم") { setRange(startOfDay(0), now()) })
        chips.addView(chip("7 أيام") { setRange(startOfDay(7), now()) })
        chips.addView(chip("30 يوم") { setRange(startOfDay(30), now()) })
        chips.addView(chip("مخصص") { pickCustomRange() })

        root.addView(TextView(this).apply { text = "الشبكة"; textSize = 16f })
        root.addView(spinner)
        root.addView(rangeLabel)
        root.addView(chips)
        root.addView(result)
        setContentView(ScrollView(this).apply { addView(root) })

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

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
        loadNetworks()
        refresh()
    }

    private fun startTracking() {
        val work = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wifi_sampler", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun loadNetworks() {
        val names = listOf("كل الشبكات") + db.knownSsids()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    private fun refresh() {
        val sel = spinner.selectedItemPosition
        val ssid = if (sel <= 0) null else spinner.selectedItem as String
        val bytes = db.total(ssid, from, to)
        result.text = "%.2f GB".format(bytes / 1_073_741_824.0)
        rangeLabel.text = "من ${fmt(from)} إلى ${fmt(to)}"
    }

    private fun setRange(f: Long, t: Long) { from = f; to = t; refresh() }

    private fun pickCustomRange() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val end = Calendar.getInstance().apply { set(y2, m2, d2, 23, 59, 59) }.timeInMillis
                setRange(start, end)
            }, y, m, d).apply { setTitle("تاريخ النهاية") }.show()
        }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH])
            .apply { setTitle("تاريخ البداية") }.show()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun now() = System.currentTimeMillis()

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }.timeInMillis

    private fun fmt(ms: Long) = java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault()).format(ms)
}
