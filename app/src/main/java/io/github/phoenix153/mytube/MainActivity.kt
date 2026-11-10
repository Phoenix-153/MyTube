package io.github.phoenix153.mytube

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Der Hauptbildschirm. Er besteht – wie die YouTube-App – aus zwei Ebenen:
 *
 *  - Stöber-Tab ("browse"): Startseite, Suche, Abos, Kanäle – und dort laufen auch die Videos,
 *    ganz normal wie auf m.youtube.com (sofortiger Start, YouTubes eigenes Autoplay).
 *  - Video-Tab ("player"): nur für den Miniplayer. Er liegt versteckt hinter dem Stöber-Tab,
 *    ist ab App-Start mit YouTube vorgeladen und lädt das gerade laufende Video pausiert vor.
 *
 * Verkleinern (nach unten wischen über dem Video oder Zurück): Der Stöber-Tab hält das Video
 * an und geht zurück zur vorherigen Seite; der Video-Tab spielt an derselben Stelle in der
 * schwebenden Karte weiter (PlayerLayout). Antippen der Karte vergrößert den Video-Tab.
 * Kanal-/Suchlinks aus dem Video-Tab öffnen im Stöber-Tab. Es läuft immer nur ein Video:
 * Öffnet der Stöber-Tab ein neues, wird der Miniplayer geschlossen.
 *
 * Jeder Tab ist eine eigene GeckoSession, verbunden mit der Erweiterung (features.js).
 */
class MainActivity : ComponentActivity() {

    private lateinit var browse: Tab
    private lateinit var player: Tab
    private lateinit var playerLayout: PlayerLayout

    /** Kopfhörer-Tasten → Play/Pause im Tab, in dem gerade das Video läuft. */
    private lateinit var mediaKeys: MediaKeys

    /** Hülle um den Stöber-Tab, erkennt das Wischen nach unten über dem Video. */
    private lateinit var browseContainer: SwipeDownLayout

    // Was im Stöber-Tab gerade läuft (gemeldet von features.js)
    private var browseVideoId: String? = null
    private var browsePlaying = false
    private var browseTime = 0.0
    private var browseTimeFor: String? = null

    /**
     * Der Stöber-Tab geht gerade wegen Verkleinerns zurück. Landet er dabei wieder auf einer
     * Videoseite (vorher Video A, dann Video B geöffnet), geht er weiter zurück, statt dort
     * ein zweites Video zu starten.
     */
    private var leavingVideo = false

    /** Weiterspielen, sobald sich die (neu geladene) Seite des Video-Tabs verbindet. */
    private var pendingResume: JSONObject? = null

    /** Session, die gerade im HTML-Vollbild ist (null = keine). */
    private var fullScreenSession: GeckoSession? = null
    private var lastBlockToast = 0L

    /** Für Arbeit, die erst nach dem aktuellen GeckoView-Callback laufen darf (siehe onLocationChange). */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        browse = Tab(isPlayer = false)
        player = Tab(isPlayer = true)
        setUpWindow()
        mediaKeys = MediaKeys(this) { command -> activeVideoTab().post(JSONObject().put("cmd", command)) }
        browse.open()
        player.open()

        // Erst laden, wenn die eigene Erweiterung bereit ist: Ihre Content-Scripts werden erst
        // danach in Seiten eingefügt – eine früher geladene Seite hätte keine Shorts-Sperre
        // und keine Klick-Verteilung.
        // Den Start-Link nur beim echten Start auswerten, nicht nach einer Wiederherstellung
        // oder beim Öffnen aus "Zuletzt verwendet" (sonst käme ein alter Link erneut).
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val start = if (savedInstanceState == null && !fromHistory) urlFromIntent(intent) else null
        (application as App).withOwnExtension {
            browse.session.loadUri(HOME)
            // Den Video-Tab schon jetzt mit YouTube laden und versteckt bereithalten: Beim
            // Vorladen eines Videos wechselt YouTube dann nur innerhalb der Seite (schnell).
            player.session.loadUri(HOME)
            if (start != null) openUrl(start)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                fullScreenSession != null -> fullScreenSession?.exitFullScreen()
                playerLayout.mode == PlayerLayout.Mode.FULL -> playerLayout.setMode(PlayerLayout.Mode.MINI)
                // Wie in der YouTube-App: Zurück auf einem laufenden Video verkleinert es
                browseVideoId != null && browsePlaying -> minimizeFromBrowse()
                browse.canGoBack -> browse.session.goBack()
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
        setIntent(intent)
        urlFromIntent(intent)?.let { openUrl(it) }
    }

    /**
     * App nicht mehr sichtbar (Home-Taste, Zurück auf der Startseite, App-Wechsel, Bildschirm
     * aus) → Video pausieren. Hintergrundwiedergabe ist bewusst nicht vorgesehen. Beim
     * Zurückkommen startet das Video nicht von selbst.
     */
    override fun onStop() {
        super.onStop()
        browse.post(JSONObject().put("cmd", "pause"))
        if (playerLayout.mode != PlayerLayout.Mode.HIDDEN) player.post(JSONObject().put("cmd", "pause"))
    }

    override fun onDestroy() {
        mediaKeys.release() // eine neue Activity legt ihre eigene Session an
        if (isFinishing) {
            browse.session.close()
            player.session.close()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Fenster
    // ---------------------------------------------------------------------------------------

    private fun setUpWindow() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        browseContainer = SwipeDownLayout(
            this,
            enabled = { browseVideoId != null && fullScreenSession == null },
            onSwipedDown = { minimizeFromBrowse() },
        )
        browseContainer.addView(browse.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Die Videoebene liegt über der Startseite und wird beim Wischen verschoben. Das geht
        // laut GeckoView nur mit dem TextureView-Backend (SurfaceView lässt sich weder
        // animieren noch zuverlässig über eine andere GeckoView legen).
        player.view.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        playerLayout = PlayerLayout(this, player.view).apply {
            onModeChanged = { mode -> onPlayerModeChanged(mode) }
            onPlayPause = { player.post(JSONObject().put("cmd", "togglePlay")) }
            onClose = { closeVideo() }
            onLongPress = { player.post(JSONObject().put("cmd", "diag")) }
        }
        // Reihenfolge: Video-Tab unten (versteckt, aber in voller Größe – sonst richtet YouTube
        // den Player für 0×0 Pixel ein), Stöber-Tab darüber. Wird ein Video verkleinert, kommt
        // der Video-Tab nach vorn (onPlayerModeChanged).
        root.addView(playerLayout)
        root.addView(browseContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Inhalt nicht unter Statusleiste, Navigationsleiste und Tastatur zeichnen
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    // ---------------------------------------------------------------------------------------
    // Video-Tab steuern
    // ---------------------------------------------------------------------------------------

    /** Öffnet eine Adresse von außen (Link, Teilen, Deep Link) – alles im Stöber-Tab. */
    private fun openUrl(url: String) {
        if (UrlRules.isShorts(Uri.parse(url))) showBlocked() else openInBrowse(url)
    }

    /** Tab, in dem gerade das Video läuft: der Miniplayer, sonst der Stöber-Tab. */
    private fun activeVideoTab() = if (playerLayout.mode != PlayerLayout.Mode.HIDDEN) player else browse

    /**
     * Lädt das Video des Stöber-Tabs pausiert im versteckten Video-Tab vor, damit das
     * Verkleinern später ohne Ladezeit geht.
     */
    private fun preloadInPlayer(videoId: String) {
        if (playerLayout.mode != PlayerLayout.Mode.HIDDEN) return
        if (player.currentUrl?.let { UrlRules.videoId(it) } == videoId) return
        val path = "/watch?v=${Uri.encode(videoId)}"
        if (player.isConnected) {
            player.post(JSONObject().put("cmd", "navigate").put("url", path))
        } else {
            player.session.loadUri("https://m.youtube.com$path")
        }
    }

    /**
     * Verkleinern: Das Video des Stöber-Tabs zieht in die Karte um. Der Stöber-Tab hält an
     * und geht zurück, der (vorgeladene) Video-Tab spielt an derselben Stelle weiter.
     */
    private fun minimizeFromBrowse() {
        val id = browseVideoId ?: return
        val time = if (browseTimeFor == id) browseTime else 0.0
        if (fullScreenSession === browse.session) browse.session.exitFullScreen()
        browse.post(JSONObject().put("cmd", "stop"))
        leavingVideo = true
        stepBack(browse)
        browseVideoId = null
        browsePlaying = false

        showPosterFor(id)
        playerLayout.setMode(PlayerLayout.Mode.MINI)
        val resume = JSONObject().put("cmd", "resumeAt").put("id", id).put("time", time)
        when {
            // Schon vorgeladen (der Normalfall) oder zumindest eine YouTube-Seite geladen:
            // das Skript wartet selbst, bis das richtige Video bereit ist
            player.isConnected -> {
                if (player.currentUrl?.let { UrlRules.videoId(it) } != id) {
                    player.post(JSONObject().put("cmd", "navigate").put("url", "/watch?v=${Uri.encode(id)}"))
                }
                player.post(resume)
            }
            // Keine Verbindung (z. B. Seite lädt noch): neu laden, beim Verbinden fortsetzen
            else -> {
                pendingResume = resume
                player.session.loadUri("https://m.youtube.com/watch?v=${Uri.encode(id)}")
            }
        }
    }

    private fun openInBrowse(url: String) {
        browse.session.loadUri(url)
        if (playerLayout.mode == PlayerLayout.Mode.FULL) {
            // Ein Video im HTML-Vollbild erst daraus holen, sonst bliebe es im Miniplayer im Vollbild
            if (fullScreenSession === player.session) player.session.exitFullScreen()
            playerLayout.setMode(PlayerLayout.Mode.MINI)
        }
    }

    private fun closeVideo() {
        pendingResume = null
        playerLayout.setMode(PlayerLayout.Mode.HIDDEN)
        // Der Tab bleibt vorgeladen für das nächste Video: anhalten und innerhalb der Seite
        // zurück zur Startseite (das beendet das Video). Ohne Verbindung: neu laden.
        if (player.isConnected) {
            player.post(JSONObject().put("cmd", "stop"))
            player.post(JSONObject().put("cmd", "navigate").put("url", "/"))
        } else {
            player.session.loadUri(HOME)
        }
        playerLayout.setTitle(null)
        playerLayout.hidePoster()
        posterVideoId = null
        mediaKeys.stop()
    }

    /** Video-ID, deren Vorschaubild gerade gezeigt wird bzw. lädt. */
    private var posterVideoId: String? = null

    /**
     * Zeigt das Vorschaubild des Videos, bis es läuft (statt mehrere Sekunden Schwarz, solange
     * die Videoseite lädt) – wie die YouTube-App. Ausgeblendet wird es, sobald die Seite
     * "läuft" meldet (PlayerLayout.setPlaying), spätestens nach 15 s.
     */
    private fun showPosterFor(videoId: String) {
        posterVideoId = videoId
        playerLayout.showPoster(null)
        Thread {
            val image = try {
                // mqdefault: 320×180, gibt es für jedes Video und ist schon 16:9
                java.net.URL("https://i.ytimg.com/vi/${Uri.encode(videoId)}/mqdefault.jpg")
                    .openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null // ohne Netz o. Ä.: dann eben schwarz
            }
            runOnUiThread { if (posterVideoId == videoId && image != null) playerLayout.showPoster(image) }
        }.start()
        mainHandler.postDelayed({ if (posterVideoId == videoId) playerLayout.hidePoster() }, 15_000)
    }

    private fun onPlayerModeChanged(mode: PlayerLayout.Mode) {
        // Im Miniplayer zeigt die Videoseite nur noch das Videobild (CSS in features.js)
        player.post(JSONObject().put("cmd", "mini").put("on", mode == PlayerLayout.Mode.MINI))
        // Versteckt darf der Video-Tab nichts abspielen (er lädt nur vor)
        player.post(JSONObject().put("cmd", "standby").put("on", mode == PlayerLayout.Mode.HIDDEN))
        // Versteckt liegt er hinter dem Stöber-Tab, sonst davor
        if (mode == PlayerLayout.Mode.HIDDEN) browseContainer.bringToFront() else playerLayout.bringToFront()
    }

    // ---------------------------------------------------------------------------------------
    // Ein Tab = GeckoView + GeckoSession + Verbindung zur Erweiterung
    // ---------------------------------------------------------------------------------------

    private inner class Tab(val isPlayer: Boolean) : WebExtension.MessageDelegate, WebExtension.PortDelegate {
        val view = GeckoView(this@MainActivity)
        lateinit var session: GeckoSession
        var canGoBack = false
        var currentUrl: String? = null

        /** Offene Verbindung zum Content-Script der aktuell geladenen Seite (null = keine). */
        private var port: WebExtension.Port? = null

        fun open() {
            session = GeckoSession().apply {
                navigationDelegate = this@MainActivity.navigationDelegate
                contentDelegate = this@MainActivity.contentDelegate
                promptDelegate = this@MainActivity.promptDelegate
                permissionDelegate = this@MainActivity.permissionDelegate
                open((application as App).runtime)
            }
            view.setSession(session)
            val s = session
            (application as App).withOwnExtension { ext ->
                ext?.let { s.webExtensionController.setMessageDelegate(it, this, NATIVE_APP) }
            }
        }

        /** Falls der Inhaltsprozess abstürzt oder vom System beendet wird: neu starten. */
        fun restart() {
            if (fullScreenSession === session) applyFullScreen(null) // sonst bliebe die App im Vollbild hängen
            port = null
            canGoBack = false
            session.close()
            open()
            val url = currentUrl?.takeUnless { UrlRules.isShorts(Uri.parse(it)) }
            if (isPlayer) {
                session.loadUri(if (url != null && playerLayout.mode != PlayerLayout.Mode.HIDDEN) url else HOME)
            } else {
                session.loadUri(url ?: HOME)
            }
        }

        fun post(message: JSONObject) {
            port?.postMessage(message)
        }

        /** Ist die aktuelle Seite mit der App verbunden (Erweiterung geladen und bereit)? */
        val isConnected get() = port != null

        // --- Nachrichten von features.js ---

        /** Das Content-Script jeder Seite baut beim Laden eine Verbindung auf. */
        override fun onConnect(port: WebExtension.Port) {
            this.port = port
            port.setDelegate(this)
            // Dem Skript sagen, in welchem Tab es läuft – davon hängt ab, welche Klicks es abfängt
            port.postMessage(JSONObject().put("cmd", "role").put("role", if (isPlayer) "player" else "browse"))
            if (isPlayer) {
                port.postMessage(JSONObject().put("cmd", "mini").put("on", playerLayout.mode == PlayerLayout.Mode.MINI))
                port.postMessage(JSONObject().put("cmd", "standby").put("on", playerLayout.mode == PlayerLayout.Mode.HIDDEN))
                pendingResume?.let { port.postMessage(it); pendingResume = null }
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (this.port === port) this.port = null
        }

        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val msg = message as? JSONObject ?: return
            val videoId = msg.optString("videoId").ifEmpty { null }
            when (msg.optString("type")) {
                "openInBrowse" -> openInBrowse(msg.getString("url"))
                "voiceSearch" -> startVoiceSearch()
                "diag" -> showDiagnostics(msg.optString("text"))
                "playState" -> {
                    val playing = msg.optBoolean("playing")
                    if (isPlayer) {
                        if (playerLayout.mode == PlayerLayout.Mode.HIDDEN) return
                        if (playing) posterVideoId = null // ein spät geladenes Vorschaubild nicht mehr zeigen
                        playerLayout.setPlaying(playing)
                    } else {
                        browsePlaying = playing
                        // Sobald im Stöber-Tab ein Video läuft, es im Video-Tab vorladen
                        if (playing && videoId != null) preloadInPlayer(videoId)
                        if (playerLayout.mode != PlayerLayout.Mode.HIDDEN) return
                    }
                    mediaKeys.setPlaying(playing)
                }
                "progress" -> if (isPlayer) {
                    playerLayout.setProgress(msg.optDouble("fraction", 0.0).toFloat())
                } else if (videoId != null) {
                    browseTime = msg.optDouble("time", 0.0)
                    browseTimeFor = videoId
                }
            }
        }
    }

    private fun tabOf(session: GeckoSession) = if (session === player.session) player else browse

    // ---------------------------------------------------------------------------------------
    // Teilen: YouTubes Teilen-Knopf ruft navigator.share() auf → Android-Teilen-Menü
    // ---------------------------------------------------------------------------------------

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onSharePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.SharePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            // text enthält oft schon den Titel, uri den Link – beides zusammen teilen
            val text = listOfNotNull(prompt.text, prompt.uri)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, prompt.title)
            startActivity(Intent.createChooser(send, "Teilen"))
            return GeckoResult.fromValue(
                prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS)
            )
        }

        /**
         * "Seite wirklich verlassen?" immer mit Ja beantworten. Ohne Antwort bliebe GeckoView
         * auf der Seite – dann würde z. B. ✕ im Miniplayer das Video nicht stoppen.
         */
        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    // ---------------------------------------------------------------------------------------
    // Seiten-Berechtigungen: Videos dürfen automatisch (mit Ton) starten, DRM ist erlaubt.
    // Ohne diesen Delegate lehnt GeckoView beides still ab – Videos starteten dann nicht von
    // selbst und der Play-Knopf im Miniplayer wäre wirkungslos. Alles andere (Standort,
    // Benachrichtigungen …) wird abgelehnt.
    // ---------------------------------------------------------------------------------------

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val allowed = perm.permission in setOf(
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
            )
            return GeckoResult.fromValue(
                if (allowed) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sprachsuche (Mikrofon-Knopf aus features.js)
    // ---------------------------------------------------------------------------------------

    /** Ergebnis der Android-Spracheingabe → YouTube-Suche im Stöber-Tab öffnen. */
    private val voiceSearch =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?: return@registerForActivityResult
            openInBrowse("${HOME}results?search_query=${Uri.encode(spoken)}")
        }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Wonach suchst du?")
        try {
            voiceSearch.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Keine Spracheingabe auf diesem Gerät", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation: Shorts sperren, Videos/Seiten auf die Tabs verteilen, Fremdes extern öffnen
    // ---------------------------------------------------------------------------------------

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {

        /** Wird vor jedem "echten" Seitenaufruf gefragt (nicht bei YouTubes internen Wechseln). */
        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> {
            val tab = tabOf(session)
            val uri = Uri.parse(request.uri)
            return when {
                UrlRules.isInternal(uri) -> GeckoResult.allow()
                !UrlRules.isWeb(uri) -> { openExternally(uri); GeckoResult.deny() }
                UrlRules.isShorts(uri) -> {
                    showBlocked()
                    if (!tab.isPlayer && tab.currentUrl == null) session.loadUri(HOME)
                    GeckoResult.deny()
                }
                !UrlRules.isAllowedInApp(uri) -> { openExternally(uri); GeckoResult.deny() }
                // Andere YouTube-Seite im Video-Tab → im Stöber-Tab öffnen.
                // (Google-Seiten bleiben, z. B. eine Anmeldung, die vom Video aus startet.)
                tab.isPlayer && playerLayout.mode != PlayerLayout.Mode.HIDDEN &&
                    UrlRules.isYouTubePage(uri) && !UrlRules.isVideo(uri) -> {
                    openInBrowse(request.uri)
                    GeckoResult.deny()
                }
                // "In neuem Fenster öffnen" gibt es hier nicht – im selben Tab laden
                request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW -> {
                    session.loadUri(request.uri); GeckoResult.deny()
                }
                else -> GeckoResult.allow()
            }
        }

        /**
         * Wird bei JEDEM Adresswechsel gemeldet – auch bei YouTubes internen Seitenwechseln
         * (pushState), die onLoadRequest nicht sieht. Dann ist die Seite aber schon gewechselt,
         * also zurückgehen und die Adresse dort öffnen, wo sie hingehört.
         */
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabOf(session)
            val uri = url?.let { Uri.parse(it) }
            // GeckoView ruft onCanGoBack erst NACH onLocationChange mit dem neuen Wert auf.
            // stepBack() braucht aber den neuen Wert – deshalb die Umleitung in die
            // Warteschlange stellen; wenn sie läuft, ist onCanGoBack schon erledigt.
            if (url != null && uri != null && tab.needsRerouting(uri)) {
                mainHandler.post { reroute(tab, url, uri) }
                return
            }
            tab.currentUrl = url
            if (!tab.isPlayer) onBrowseLocation(url)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabOf(session).canGoBack = canGoBack
        }
    }

    /** Der Stöber-Tab hat die Seite gewechselt. */
    private fun onBrowseLocation(url: String?) {
        val id = url?.let { UrlRules.videoId(it) }
        if (leavingVideo) {
            if (id != null) {
                // Noch eine Videoseite: anhalten und weiter zurück (nach onCanGoBack, s. o.)
                browse.post(JSONObject().put("cmd", "stop"))
                mainHandler.post { stepBack(browse) }
                return
            }
            leavingVideo = false
        }
        if (id != browseVideoId) browsePlaying = false
        browseVideoId = id
        // Immer nur ein Video: Öffnet der Stöber-Tab ein neues, Miniplayer schließen
        if (id != null && playerLayout.mode != PlayerLayout.Mode.HIDDEN) closeVideo()
        if (id == null && playerLayout.mode == PlayerLayout.Mode.HIDDEN) mediaKeys.stop()
    }

    /** Gehört diese Adresse nicht in diesen Tab? (Shorts, Seite im sichtbaren Video-Tab) */
    private fun Tab.needsRerouting(uri: Uri) =
        UrlRules.isShorts(uri) ||
            // Video-Tab auf anderer Seite – nur, solange ein Video offen ist. Versteckt wartet
            // der Tab vorgeladen auf der Startseite.
            (isPlayer && playerLayout.mode != PlayerLayout.Mode.HIDDEN &&
                UrlRules.isYouTubePage(uri) && !UrlRules.isVideo(uri))

    /** Öffnet die Adresse dort, wo sie hingehört, und macht den Wechsel in diesem Tab rückgängig. */
    private fun reroute(tab: Tab, url: String, uri: Uri) {
        if (UrlRules.isShorts(uri)) showBlocked() else openInBrowse(url)
        stepBack(tab)
    }

    /** Macht einen Seitenwechsel rückgängig, der in diesem Tab nichts zu suchen hat. */
    private fun stepBack(tab: Tab) {
        when {
            tab.canGoBack -> tab.session.goBack()
            tab.isPlayer -> closeVideo()
            else -> tab.session.loadUri(HOME)
        }
    }

    /** Größen der Player-Ebenen anzeigen (langes Drücken auf die Karte) – für die Fehlersuche. */
    private fun showDiagnostics(pageInfo: String) {
        val v = player.view
        val text = "Karte: ${playerLayout.width}×${playerLayout.height}, Faktor ${"%.2f".format(playerLayout.scaleX)}\n" +
            "Video-Ansicht: ${v.width}×${v.height} px (Dichte ${resources.displayMetrics.density})\n\n" + pageInfo
        android.app.AlertDialog.Builder(this)
            .setTitle("Miniplayer-Diagnose")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
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
    // Vollbild, Titel, Abstürze
    // ---------------------------------------------------------------------------------------

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (fullScreen && session === player.session && playerLayout.mode != PlayerLayout.Mode.HIDDEN) {
                playerLayout.setMode(PlayerLayout.Mode.FULL)
            }
            applyFullScreen(if (fullScreen) session else null)
        }

        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (session === player.session) {
                playerLayout.setTitle(title)
                if (playerLayout.mode != PlayerLayout.Mode.HIDDEN) mediaKeys.setTitle(title)
            } else if (playerLayout.mode == PlayerLayout.Mode.HIDDEN) {
                mediaKeys.setTitle(title)
            }
        }

        override fun onCrash(session: GeckoSession) = tabOf(session).restart()
        override fun onKill(session: GeckoSession) = tabOf(session).restart()
    }

    /** Fenster in den Vollbild-Zustand bringen ([session] != null) oder wieder heraus. */
    private fun applyFullScreen(session: GeckoSession?) {
        fullScreenSession = session
        playerLayout.gesturesEnabled = session == null
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (session != null) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
                if (data.scheme == "mytube") data.getQueryParameter("url") ?: return null
                else data.toString()
            }
            // Beim Teilen kommt oft Text wie "Schau mal: https://youtu.be/..." an
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?.let { Regex("""https?://[^\s<>"]+""").find(it)?.value?.trimEnd('.', ',', '!', '?', ')') }
            else -> null
        }
        raw ?: return null
        // Von außen nur YouTube-Adressen annehmen – sonst könnte jede Webseite über den
        // Deep Link z. B. eine (gefälschte) Anmeldeseite in der App öffnen.
        return if (UrlRules.isYouTube(Uri.parse(raw))) UrlRules.normalize(raw) else null
    }

    companion object {
        const val HOME = "https://m.youtube.com/"

        /** Name, unter dem die Erweiterung die App anspricht (browser.runtime.connectNative). */
        private const val NATIVE_APP = "mytube"
    }
}

/** Regeln, welche Adressen wie behandelt werden. */
object UrlRules {
    private val youtubeHost = Regex("""(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$""")

    // Nur die Google-Seiten, die die Anmeldung braucht (accounts.google.com, auch mit
    // Länder-Domain wie accounts.google.de). Bewusst KEINE allgemeine google.*-Regel: sonst
    // ließen sich fremde Seiten wie google.evil.com in der App öffnen (Phishing-Gefahr).
    private val googleLoginHost = Regex("""^(accounts|myaccount|consent|gds)\.google\.(com|[a-z]{2}|com?\.[a-z]{2})$""")

    // /shorts/ID sowie der Shorts-Tab eines Kanals (/@name/shorts, /channel/ID/shorts …).
    // Gleiche Regel steht als SHORTS_PATH in assets/shorts-blocker/content.js – beide gleich halten!
    private val shortsPath = Regex("""^/(shorts(/|$)|(@[^/]+|c/[^/]+|channel/[^/]+|user/[^/]+)/shorts/?$)""")

    fun isWeb(uri: Uri) = uri.scheme == "https" || uri.scheme == "http"

    /** Interne Adressen der Engine (leere Seite, Erweiterungen …) immer erlauben. */
    fun isInternal(uri: Uri) = uri.scheme in setOf("about", "data", "blob", "resource", "moz-extension")

    fun isYouTube(uri: Uri) = isWeb(uri) && youtubeHost.containsMatchIn(uri.host.orEmpty().lowercase())

    /**
     * Eine normale YouTube-Seite (Start, Kanal, Suche …) – nicht Hilfsseiten wie
     * consent.youtube.com oder accounts.youtube.com, die im jeweiligen Tab bleiben müssen.
     */
    fun isYouTubePage(uri: Uri) =
        isWeb(uri) && uri.host.orEmpty().lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com")

    fun isShorts(uri: Uri) = isYouTube(uri) && shortsPath.containsMatchIn(uri.path.orEmpty())

    /**
     * Eine Videoseite (youtube.com/watch?v=… oder youtu.be/ID).
     * features.js kennt dieselbe Regel (nur /watch, weil Seiten intern nie youtu.be verlinken).
     */
    fun isVideo(uri: Uri): Boolean {
        if (!isYouTube(uri)) return false
        if (uri.host.orEmpty().lowercase().endsWith("youtu.be")) return uri.path.orEmpty().length > 1
        return uri.path == "/watch"
    }

    /**
     * youtu.be/ID?t=42 → m.youtube.com/watch?v=ID&t=42, damit Videos direkt im Video-Tab
     * landen. Startzeit, Playlist usw. bleiben erhalten.
     */
    fun normalize(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        if (host.endsWith("youtu.be")) {
            val id = uri.path.orEmpty().trim('/')
            if (id.isNotEmpty()) {
                val query = uri.encodedQuery?.let { "&$it" }.orEmpty()
                return "https://m.youtube.com/watch?v=${Uri.encode(id)}$query"
            }
        }
        return url
    }

    /** Video-ID einer Videoadresse, sonst null. */
    fun videoId(url: String): String? {
        val uri = Uri.parse(url)
        if (!isVideo(uri)) return null
        return if (uri.host.orEmpty().lowercase().endsWith("youtu.be")) uri.path?.trim('/')
        else uri.getQueryParameter("v")
    }

    fun isAllowedInApp(uri: Uri): Boolean {
        if (!isWeb(uri)) return false
        val host = uri.host.orEmpty().lowercase()
        return youtubeHost.containsMatchIn(host) || googleLoginHost.matches(host)
    }
}
