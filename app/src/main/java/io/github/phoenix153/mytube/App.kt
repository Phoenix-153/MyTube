package io.github.phoenix153.mytube

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Hält die GeckoRuntime – die Browser-Engine.
 *
 * Wichtig: Pro App-Prozess darf es nur EINE GeckoRuntime geben. Deshalb lebt sie hier in der
 * Application und nicht in der Activity (die z. B. beim Zurückkehren neu erzeugt werden kann).
 * Das Profil (Cookies, Login, Erweiterungen) liegt dauerhaft im App-Speicher.
 */
class App : Application() {

    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            // JS-Konsole nur in Debug-Builds ins Logcat
            .consoleOutput(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .build()
        GeckoRuntime.create(this, settings).also { setUpExtensions(it) }
    }

    private fun setUpExtensions(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController

        // Installationen ohne Rückfrage erlauben. Es werden nur die zwei Erweiterungen
        // installiert, die die App selbst anstößt – der Nutzer kann keine anderen hinzufügen.
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> =
                GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, false))

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.allow()
        }

        // 1) Eigener Shorts-Blocker, liegt in app/src/main/assets/shorts-blocker/
        controller.ensureBuiltIn(SHORTS_BLOCKER_URI, SHORTS_BLOCKER_ID)
            .accept({}, { Log.e(TAG, "Shorts-Blocker konnte nicht geladen werden", it) })

        // 2) uBlock Origin (Werbeblocker): beim ersten Start aus dem Firefox-Add-on-Store
        //    installieren, danach höchstens einmal täglich aktualisieren (Filterlisten
        //    aktualisiert uBlock selbst).
        controller.list().accept({ installed ->
            val ublock = installed?.firstOrNull { it.id == UBLOCK_ID }
            if (ublock == null) {
                controller.install(UBLOCK_URL)
                    .accept({ Log.i(TAG, "uBlock Origin installiert") },
                        { Log.e(TAG, "uBlock-Installation fehlgeschlagen", it) })
            } else if (Prefs.dueOnce(this, "ublock_update", DAY_MS)) {
                controller.update(ublock)
                    .accept({}, { Log.w(TAG, "uBlock-Update fehlgeschlagen", it) })
            }
        }, { Log.e(TAG, "Erweiterungsliste nicht lesbar", it) })
    }

    companion object {
        const val TAG = "MyTube"
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val SHORTS_BLOCKER_URI = "resource://android/assets/shorts-blocker/"
        private const val SHORTS_BLOCKER_ID = "shorts-blocker@mytube"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    }
}

/** Kleine Hilfsfunktionen für gespeicherte Einstellungen (SharedPreferences). */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mytube", Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String) = prefs(ctx).getBoolean(key, false)
    fun setBool(ctx: Context, key: String, value: Boolean) =
        prefs(ctx).edit().putBoolean(key, value).apply()

    /** true, wenn [key] noch nie oder vor mehr als [intervalMs] ausgelöst wurde – und merkt sich jetzt. */
    fun dueOnce(ctx: Context, key: String, intervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - prefs(ctx).getLong(key, 0L) < intervalMs) return false
        prefs(ctx).edit().putLong(key, now).apply()
        return true
    }
}
