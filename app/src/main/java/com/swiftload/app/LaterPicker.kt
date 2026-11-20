package com.swiftload.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

/** "Download later" choices: Tonight (night bundle) · Pick a time · When on Wi-Fi. */
object LaterPicker {

    fun show(activity: AppCompatActivity, onPick: (ScheduleType, Long) -> Unit) {
        val options = arrayOf(
            "🌙  Tonight (night bundle ${Scheduler.nightLabel(activity)})",
            "🕒  Pick a time",
            "📶  When on Wi-Fi",
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("Download later")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> done(activity, onPick, ScheduleType.NIGHT, 0)
                    1 -> pickTime(activity, null) { minute -> done(activity, onPick, ScheduleType.TIME, Scheduler.nextTime(minute)) }
                    else -> done(activity, onPick, ScheduleType.WIFI, 0)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Clock picker; returns minutes of the day. */
    fun pickTime(activity: AppCompatActivity, initialMinute: Int?, title: String = "Start download at", onPick: (Int) -> Unit) {
        val now = Calendar.getInstance()
        val m = initialMinute ?: (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(m / 60)
            .setMinute(m % 60)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener { onPick(picker.hour * 60 + picker.minute) }
        picker.show(activity.supportFragmentManager, "time")
    }

    private fun done(activity: AppCompatActivity, onPick: (ScheduleType, Long) -> Unit, type: ScheduleType, at: Long) {
        onPick(type, at)
        offerBackgroundStart(activity)
    }

    /**
     * Android may not let an app start a download by itself while the phone sleeps (battery optimisation).
     * Asked once: if allowed, scheduled downloads start on their own; otherwise a "tap to start" notification appears.
     */
    @SuppressLint("BatteryLife")
    private fun offerBackgroundStart(activity: AppCompatActivity) {
        val pm = activity.getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        val prefs = activity.getSharedPreferences("settings", AppCompatActivity.MODE_PRIVATE)
        if (prefs.getBoolean("batteryAsked", false)) return
        prefs.edit().putBoolean("batteryAsked", true).apply()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Start downloads on time")
            .setMessage(
                "So scheduled downloads can start by themselves at night while the phone is idle, " +
                    "allow VidSaver to run in the background.\n\nIf you don't, VidSaver shows a notification " +
                    "when the time comes and you tap it to start."
            )
            .setPositiveButton("Allow") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
