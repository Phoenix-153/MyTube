package io.github.phoenix153.mytube

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prüft höchstens einmal täglich, ob es auf GitHub ein neueres Release gibt, und bietet es an.
 *
 * Ablauf für ein neues Release: versionCode/versionName in app/build.gradle.kts erhöhen,
 * Release-APK bauen und als GitHub Release mit Tag "v<versionName>" (z. B. v1.1.0) hochladen.
 * Der Download läuft über den Browser; Android fragt dann nach der Installation.
 */
object Updater {
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/Phoenix-153/MyTube/releases/latest"

    fun checkInBackground(activity: Activity) {
        if (!Prefs.dueOnce(activity, "update_check", App.DAY_MS)) return
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: return

        Thread {
            try {
                val conn = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val json = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }

                val latest = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }
                    ?.getString("browser_download_url")

                if (apkUrl != null && isNewer(latest, installed)) {
                    activity.runOnUiThread { offer(activity, latest, apkUrl) }
                }
            } catch (e: Exception) {
                Log.w(App.TAG, "Update-Prüfung fehlgeschlagen", e) // offline o. Ä. – morgen wieder
            }
        }.start()
    }

    private fun offer(activity: Activity, version: String, apkUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Update verfügbar")
            .setMessage("MyTube $version ist da. Jetzt herunterladen und installieren?\n\n" +
                "Du bleibst dabei angemeldet.")
            .setPositiveButton("Herunterladen") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) }
            }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Vergleicht Versionen wie "1.10.0" > "1.9.2" Zahl für Zahl. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
