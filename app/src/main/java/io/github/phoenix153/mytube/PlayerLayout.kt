package io.github.phoenix153.mytube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

/**
 * Container für den Video-Tab. Kennt drei Zustände wie der Player der YouTube-App:
 *
 *  - HIDDEN: kein Video im Miniplayer; liegt bildschirmgroß hinter dem Stöber-Tab und lädt vor
 *  - FULL:   Videoseite bildschirmfüllend über der Startseite. Im Hochformat verkleinert
 *            Wischen nach unten im oberen Bereich (dort sitzt das Video) zum Miniplayer.
 *            (FULL = aufgeklappt; nicht zu verwechseln mit dem HTML-Vollbild des Videos.)
 *  - MINI:   schwebende Karte in einer Ecke: laufendes Video mit Pause/Schließen darüber,
 *            Titel und Fortschrittsbalken darunter. Antippen vergrößert, Ziehen verschiebt
 *            (die Karte springt in die nächste Ecke), seitlich aus dem Bild wischen schließt.
 *
 * Bis ein neues Video läuft, liegt sein Vorschaubild über dem Player (statt schwarzem Bild).
 *
 * Diese Klasse kümmert sich nur um Darstellung und Gesten. Was dabei mit den Webseiten
 * passiert, entscheidet MainActivity über die Callbacks.
 */
@SuppressLint("ViewConstructor")
class PlayerLayout(context: Context, private val playerView: GeckoView) : FrameLayout(context) {

    enum class Mode { HIDDEN, FULL, MINI }

    var mode = Mode.HIDDEN
        private set

    /** Wird nach jedem Zustandswechsel aufgerufen. */
    var onModeChanged: (Mode) -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var onClose: () -> Unit = {}
    /** Langes Drücken auf die Karte (zeigt Diagnose-Infos für die Fehlersuche). */
    var onLongPress: () -> Unit = {}

    /** Im YouTube-Vollbild keine eigene Wischgeste. */
    var gesturesEnabled = true

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // --- Maße der Karte (so groß, wie sie auf dem Bildschirm erscheint) ---
    private val cardMargin = dp(12)
    /** Abstand unten zusätzlich, damit YouTubes untere Navigationsleiste frei bleibt. */
    private val bottomBarHeight = dp(48)
    /** Abstand oben, damit YouTubes Kopfzeile frei bleibt. */
    private val topBarHeight = dp(56)

    /** Kartenbreite auf dem Bildschirm: 45 % der kürzeren Bildschirmseite. */
    private fun cardWidth(): Int {
        val parent = parent as? View ?: return dp(180)
        return (minOf(parent.width, parent.height) * 0.45f).toInt().coerceAtLeast(dp(160))
    }

    /**
     * So breit rendert die Videoseite in der Karte. YouTube pausiert Videos in Playern unter
     * 200×200 CSS-Pixeln – die Seite bleibt deshalb "normal groß", und die GANZE Karte wird
     * für die Anzeige verkleinert (scaleX/scaleY). Nur das Video zu verkleinern geht nicht:
     * GeckoView zeichnet dann nur den Teil, der unverkleinert in die Karte passen würde.
     */
    private fun renderWidth() = maxOf((parent as? View)?.width ?: 0, dp(400))

    /** In welcher Ecke die Karte sitzt. Startet wie bei YouTube unten rechts. */
    private var cornerRight = true
    private var cornerBottom = true

    // --- Bedienelemente der Karte ---
    private fun roundButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = desc
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(0x88, 0, 0, 0)) // halbtransparent, damit es auf hellen Videos lesbar bleibt
        }
        setOnClickListener { onClick() }
    }

    private val playPause = roundButton(android.R.drawable.ic_media_pause, "Wiedergabe/Pause") { onPlayPause() }
    private val close = roundButton(android.R.drawable.ic_menu_close_clear_cancel, "Video schließen") { onClose() }

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }

    /** Roter Fortschrittsbalken ganz unten; die Breite folgt der Abspielposition. */
    private val progressTrack = View(context).apply { setBackgroundColor(Color.argb(0x55, 0xFF, 0xFF, 0xFF)) }
    private val progressFill = View(context).apply { setBackgroundColor(Color.rgb(0xFF, 0x00, 0x33)) }
    private var progress = 0f

    /** Vorschaubild, bis das Video läuft. */
    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
        visibility = GONE
    }

    /** Eckenradius in Karten-Koordinaten (wird mitverkleinert). */
    private var cornerRadius = dp(12).toFloat()

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(title)
        addView(progressTrack)
        addView(progressFill)
        addView(playPause)
        addView(close)

        // Abgerundete Ecken der Karte (das Video wird mit abgeschnitten)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        applyMode()
    }

    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        applyMode()
        onModeChanged(newMode)
    }

    fun setTitle(text: String?) {
        title.text = text?.removeSuffix(" - YouTube").orEmpty()
    }

    fun setPlaying(playing: Boolean) {
        playPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        if (playing) hidePoster()
    }

    /** Abspielposition 0..1 für den Fortschrittsbalken. */
    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        if (mode == Mode.MINI) layoutProgress()
    }

    /** Vorschaubild zeigen (null = vorerst nur schwarz, das Bild kommt gleich nach). */
    fun showPoster(image: Bitmap?) {
        poster.setImageBitmap(image)
        poster.visibility = VISIBLE
    }

    fun hidePoster() {
        poster.visibility = GONE
        poster.setImageDrawable(null)
    }

    // -----------------------------------------------------------------------------------------
    // Layout der Zustände
    // -----------------------------------------------------------------------------------------

    private fun applyMode() {
        animate().cancel()
        translationX = 0f
        translationY = 0f
        alpha = 1f
        // HIDDEN = sichtbar und in voller Größe, aber HINTER dem Stöber-Tab (die Reihenfolge
        // regelt MainActivity). Nicht GONE und nicht INVISIBLE: Sonst richtet YouTube den
        // vorgeladenen Player für 0×0 ein bzw. zeichnet ihn nicht – später käme nur Ton.
        visibility = VISIBLE
        val mini = mode == Mode.MINI
        listOf(title, progressTrack, progressFill, playPause, close).forEach {
            it.visibility = if (mini) VISIBLE else GONE
        }
        setBackgroundColor(if (mini) Color.rgb(0x21, 0x21, 0x21) else Color.BLACK)
        clipToOutline = mini

        if (mini) layoutCard() else layoutFull()
    }

    private fun layoutFull() {
        scaleX = 1f
        scaleY = 1f
        elevation = 0f
        layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(0, 0)).apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
            gravity = Gravity.NO_GRAVITY
            setMargins(0, 0, 0, 0)
        }
        playerView.layoutParams = (playerView.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
        }
        // Vorschaubild oben in Videogröße (16:9 über die volle Breite)
        poster.layoutParams = (poster.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = ((parent as? View)?.width ?: 0) * 9 / 16
        }
        requestLayout()
    }

    /**
     * Die Karte wird in "Render-Größe" angelegt (Seite mind. 400 dp breit) und dann als
     * Ganzes auf Kartengröße verkleinert. Alle Maße der Bedienelemente werden deshalb durch
     * den Verkleinerungsfaktor geteilt, damit sie auf dem Bildschirm normal groß erscheinen.
     */
    private fun layoutCard() {
        val renderW = renderWidth()
        val videoH = renderW * 9 / 16
        val s = cardWidth().toFloat() / renderW
        fun u(px: Int) = (px / s).toInt() // Bildschirm-Pixel → Karten-Pixel

        val titleH = u(dp(28))
        val progressH = u(dp(3)).coerceAtLeast(1)
        cornerRadius = u(dp(12)).toFloat()
        elevation = u(dp(8)).toFloat()

        layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(0, 0)).apply {
            width = renderW
            height = videoH + titleH + progressH
            gravity = (if (cornerBottom) Gravity.BOTTOM else Gravity.TOP) or
                (if (cornerRight) Gravity.END else Gravity.START)
            setMargins(cardMargin, cardMargin + topBarHeight, cardMargin, cardMargin + bottomBarHeight)
        }
        // Um die Ecke verkleinern, an der die Karte sitzt – so bleibt sie genau dort
        pivotX = if (cornerRight) renderW.toFloat() else 0f
        pivotY = if (cornerBottom) (videoH + titleH + progressH).toFloat() else 0f
        scaleX = s
        scaleY = s

        playerView.layoutParams = (playerView.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = videoH
        }
        poster.layoutParams = (poster.layoutParams as LayoutParams).apply {
            width = LayoutParams.MATCH_PARENT
            height = videoH
        }
        title.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, titleH).apply { topMargin = videoH }
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(dp(13)).toFloat())
        title.setPadding(u(dp(8)), 0, u(dp(8)), 0)
        progressTrack.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, progressH).apply {
            gravity = Gravity.BOTTOM
        }
        val button = u(dp(36))
        val gap = u(dp(6))
        val iconPad = u(dp(6))
        playPause.setPadding(iconPad, iconPad, iconPad, iconPad)
        close.setPadding(iconPad, iconPad, iconPad, iconPad)
        playPause.layoutParams = LayoutParams(button, button).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(gap, gap, 0, 0)
        }
        close.layoutParams = LayoutParams(button, button).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, gap, gap, 0)
        }
        layoutProgress()
        requestLayout()
    }

    private fun layoutProgress() {
        val h = (progressTrack.layoutParams as? LayoutParams)?.height ?: 1
        progressFill.layoutParams = LayoutParams((renderWidth() * progress).toInt(), h).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        if (mode == Mode.FULL) post { if (mode == Mode.FULL) layoutFull() } // Vorschaubild nach Drehen anpassen
    }

    // -----------------------------------------------------------------------------------------
    // Sichtbare Lage der (verkleinerten) Karte in Eltern-Koordinaten
    // -----------------------------------------------------------------------------------------

    private fun visualLeft() = left + translationX + pivotX * (1 - scaleX)
    private fun visualTop() = top + translationY + pivotY * (1 - scaleY)
    private fun visualWidth() = width * scaleX
    private fun visualHeight() = height * scaleY

    // -----------------------------------------------------------------------------------------
    // Gesten
    // -----------------------------------------------------------------------------------------

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val longPress = Runnable {
        if (mode == Mode.MINI && tracking && !dragging) {
            tracking = false // der Finger-Lift danach ist kein Tipp mehr
            onLongPress()
        }
    }
    private var tracking = false   // Finger ist dort aufgesetzt, wo unsere Geste gilt
    private var dragging = false   // wir haben die Geste übernommen

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        when (mode) {
            Mode.FULL -> return interceptFull(ev)
            // In der Karte bekommt die Webseite gar keine Berührungen – nur die beiden Knöpfe
            Mode.MINI -> {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (hits(playPause, ev) || hits(close, ev)) return false
                    startTouch(ev)
                    tracking = true
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return tracking
            }
            Mode.HIDDEN -> return false
        }
    }

    /** FULL: im Videobereich deutlich senkrecht nach unten ziehen → übernehmen. */
    private fun interceptFull(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouch(ev)
                // Der Player sitzt im Hochformat oben auf der Videoseite (16:9 über die ganze
                // Breite). Im Querformat würde diese Zone den ganzen Bildschirm umfassen und
                // jedes Hochscrollen den Player verkleinern – dort deshalb keine Wischgeste.
                tracking = height > width && ev.y < width * 9f / 16f
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dy = ev.y - downY
                val dx = ev.x - downX
                // Antippen, Doppeltippen und seitliches Wischen bleiben beim YouTube-Player.
                if (dy > touchSlop * 2 && dy > abs(dx) * 2) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    private fun startTouch(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        downRawX = ev.rawX
        downRawY = ev.rawY
        dragging = false
    }

    /** ev.x/ev.y sind schon in Karten-Koordinaten (Android rechnet die Verkleinerung heraus). */
    private fun hits(v: View, ev: MotionEvent): Boolean {
        if (v.visibility != VISIBLE) return false
        val r = Rect()
        v.getHitRect(r)
        return r.contains(ev.x.toInt(), ev.y.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = when (mode) {
        Mode.FULL -> touchFull(ev)
        Mode.MINI -> touchCard(ev)
        Mode.HIDDEN -> false
    }

    /** FULL: die Seite folgt dem Finger nach unten; weit genug → Karte. */
    private fun touchFull(ev: MotionEvent): Boolean {
        if (!dragging) return false
        // rawY (Bildschirmkoordinate), weil translationY die eigenen Koordinaten mitverschiebt
        val dy = (ev.rawY - downRawY).coerceAtLeast(0f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationY = dy
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracking = false
                if (dy > height * 0.2f) setMode(Mode.MINI)
                else animate().translationX(0f).translationY(0f).setDuration(150).start()
            }
        }
        return true
    }

    /** MINI: Tippen vergrößert, Ziehen verschiebt, seitlich hinauswischen schließt. */
    private fun touchCard(ev: MotionEvent): Boolean {
        if (!tracking) return false
        // Bildschirm-Koordinaten (rawX/Y), weil die Karte verschoben und verkleinert ist
        val dx = ev.rawX - downRawX
        val dy = ev.rawY - downRawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    translationX = dx
                    translationY = dy
                    // Beim Hinausziehen halb durchsichtig – zeigt "loslassen schließt"
                    alpha = if (isOutside()) 0.5f else 1f
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                tracking = false
                if (!dragging) setMode(Mode.FULL) // einfacher Tipp
                else if (isOutside()) swipeAway(dx)
                else snapToCorner()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                tracking = false
                dragging = false
                snapToCorner()
            }
        }
        return true
    }

    /** Ist die Karte zu mehr als der Hälfte seitlich aus dem Bild gezogen? */
    private fun isOutside(): Boolean {
        val parent = parent as? View ?: return false
        val l = visualLeft()
        val w = visualWidth()
        return l < -w / 2f || l + w > parent.width + w / 2f
    }

    private fun swipeAway(dx: Float) {
        val parentWidth = (parent as View).width
        val l = visualLeft() - translationX // sichtbare linke Kante ohne Verschiebung
        val target = if (dx < 0) -(l + visualWidth()) else parentWidth - l
        animate().translationX(target).alpha(0f).setDuration(150).withEndAction { onClose() }.start()
    }

    /** Karte in die Ecke springen lassen, die ihrer Mitte am nächsten ist. */
    private fun snapToCorner() {
        val parent = parent as? ViewGroup ?: return
        val centerX = visualLeft() + visualWidth() / 2f
        val centerY = visualTop() + visualHeight() / 2f
        cornerRight = centerX > parent.width / 2f
        cornerBottom = centerY > parent.height / 2f

        // Ziel der sichtbaren Karte
        val targetLeft = if (cornerRight) parent.width - parent.paddingRight - cardMargin - visualWidth()
        else (parent.paddingLeft + cardMargin).toFloat()
        val targetTop = if (cornerBottom) parent.height - parent.paddingBottom - cardMargin - bottomBarHeight - visualHeight()
        else (parent.paddingTop + cardMargin + topBarHeight).toFloat()

        // Erst sichtbar dorthin gleiten, dann fest als neue Ecke übernehmen
        val restLeft = visualLeft() - translationX
        val restTop = visualTop() - translationY
        animate()
            .translationX(targetLeft - restLeft)
            .translationY(targetTop - restTop)
            .alpha(1f)
            .setDuration(180)
            .withEndAction { if (mode == Mode.MINI) applyMode() }
            .start()
    }
}
