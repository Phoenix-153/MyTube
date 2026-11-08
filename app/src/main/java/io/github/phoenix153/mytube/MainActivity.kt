package io.github.phoenix153.mytube

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoView

/**
 * Der einzige Browser-Bildschirm: zeigt m.youtube.com in GeckoView im Vollbild.
 *
 * Shorts werden zweistufig verhindert:
 *  1. Die Erweiterung assets/shorts-blocker blendet Shorts aus und fängt Klicks darauf ab.
 *  2. Hier in Kotlin wird jede Navigation geprüft – das Sicherheitsnetz, falls Stufe 1
 *     etwas übersieht oder ein Shorts-Link von außen (WhatsApp usw.) geöffnet wird.
 */
class MainActivity : ComponentActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession

    private var canGoBack = false
    private var isFullScreen = false
    private var currentUrl: String? = null
    private var lastBlockToast = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpWindow()

        session = createSession()
        session.loadUri(urlFromIntent(intent) ?: HOME)

        onBackPressedDispatcher.addCallback(this) {
            when {
                isFullScreen -> session.exitFullScreen()
                canGoBack -> session.goBack()
                else -> moveTaskToBack(true) // App bleibt im Hintergrund, wie bei der YouTube-App
            }
        }

        if (!Prefs.getBool(this, OnboardingActivity.DONE_KEY)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        Updater.checkInBackground(this)
    }

    /** Ein geöffneter Link oder Deep Link, während die App schon läuft. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { session.loadUri(it) }
    }

    override fun onDestroy() {
        // Session nur beim echten Beenden schließen (nicht z. B. beim Drehen – das ist per
        // configChanges ohnehin abgeschaltet).
        if (isFinishing) session.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Fenster & Session
    // ---------------------------------------------------------------------------------------

    private fun setUpWindow() {
        // Inhalt nicht unter Statusleiste, Navigationsleiste und Tastatur zeichnen
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        geckoView = GeckoView(this)
        root.addView(geckoView)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    private fun createSession(): GeckoSession {
        val s = GeckoSession()
        s.navigationDelegate = navigationDelegate
        s.contentDelegate = contentDelegate
        s.open((application as App).runtime)
        geckoView.setSession(s)
        return s
    }

    /** Falls der Inhaltsprozess abstürzt oder vom System beendet wird: neue Session starten. */
    private fun restartSession() {
        session.close()
        session = createSession()
        session.loadUri(currentUrl?.takeUnless { UrlRules.isShorts(Uri.parse(it)) } ?: HOME)
    }

    // ---------------------------------------------------------------------------------------
    // Navigation: Shorts sperren, fremde Seiten extern öffnen
    // ---------------------------------------------------------------------------------------

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {

        /** Wird vor jedem "echten" Seitenaufruf gefragt (nicht bei YouTubes internen Wechseln). */
        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = Uri.parse(request.uri)
            return when {
                UrlRules.isInternal(uri) -> GeckoResult.allow()
                !UrlRules.isWeb(uri) -> { openExternally(uri); GeckoResult.deny() }
                UrlRules.isShorts(uri) -> {
                    showBlocked()
                    if (currentUrl == null) session.loadUri(HOME) // z. B. App über Shorts-Link gestartet
                    GeckoResult.deny()
                }
                !UrlRules.isAllowedInApp(uri) -> { openExternally(uri); GeckoResult.deny() }
                // "In neuem Fenster öffnen" gibt es hier nicht – im selben Fenster laden
                request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW -> {
                    session.loadUri(request.uri); GeckoResult.deny()
                }
                else -> GeckoResult.allow()
            }
        }

        /**
         * Wird bei JEDEM Adresswechsel gemeldet – auch bei YouTubes internen Seitenwechseln
         * (pushState), die onLoadRequest nicht sieht. Dann ist die Seite aber schon gewechselt,
         * also zurückgehen.
         */
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            if (url != null && UrlRules.isShorts(Uri.parse(url))) {
                showBlocked()
                if (canGoBack) session.goBack() else session.loadUri(HOME)
                return
            }
            currentUrl = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }
    }

    private fun showBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastBlockToast < 2000) return
        lastBlockToast = now
        Toast.makeText(this, "Shorts sind gesperrt", Toast.LENGTH_SHORT).show()
    }

    private fun openExternally(uri: Uri) {
        if (uri.scheme == "intent" || uri.scheme == "vnd.youtube") return // Aufrufe der YouTube-App ignorieren
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Keine App für diesen Link vorhanden – ignorieren
        }
    }

    // ---------------------------------------------------------------------------------------
    // Vollbild für Videos
    // ---------------------------------------------------------------------------------------

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            isFullScreen = fullScreen
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (fullScreen) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                bars.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        override fun onCrash(session: GeckoSession) = restartSession()
        override fun onKill(session: GeckoSession) = restartSession()
    }

    // ---------------------------------------------------------------------------------------
    // Links von außen
    // ---------------------------------------------------------------------------------------

    /** Liefert die zu öffnende Adresse aus einem Intent oder null (= nichts Besonderes). */
    private fun urlFromIntent(intent: Intent?): String? {
        intent ?: return null
        val raw: String? = when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme == "mytube") data.getQueryParameter("url") ?: HOME
                else data.toString()
            }
            // Beim Teilen kommt oft Text wie "Schau mal: https://youtu.be/..." an
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("""https?://\S+""").find(it)?.value }
            else -> null
        }
        raw ?: return null
        return if (UrlRules.isAllowedInApp(Uri.parse(raw))) raw else HOME
    }

    companion object {
        const val HOME = "https://m.youtube.com/"
    }
}

/** Regeln, welche Adressen wie behandelt werden. */
object UrlRules {
    private val youtubeHost = Regex("""(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$""")

    // Google-Login läuft über accounts.google.com, teils auch über Länder-Domains (google.de …)
    private val googleHost = Regex("""(^|\.)(google\.[a-z.]+|gstatic\.com|googleusercontent\.com)$""")

    // /shorts/ID sowie der Shorts-Tab eines Kanals (/@name/shorts, /channel/ID/shorts …)
    private val shortsPath = Regex("""^/(shorts(/|$)|(@[^/]+|c/[^/]+|channel/[^/]+|user/[^/]+)/shorts/?$)""")

    fun isWeb(uri: Uri) = uri.scheme == "https" || uri.scheme == "http"

    /** Interne Adressen der Engine (leere Seite, Erweiterungen …) immer erlauben. */
    fun isInternal(uri: Uri) = uri.scheme in setOf("about", "data", "blob", "resource", "moz-extension")

    fun isYouTube(uri: Uri) = youtubeHost.containsMatchIn(uri.host.orEmpty().lowercase())

    fun isShorts(uri: Uri) = isWeb(uri) && isYouTube(uri) && shortsPath.containsMatchIn(uri.path.orEmpty())

    fun isAllowedInApp(uri: Uri): Boolean {
        if (!isWeb(uri)) return false
        val host = uri.host.orEmpty().lowercase()
        return youtubeHost.containsMatchIn(host) || googleHost.containsMatchIn(host)
    }
}
