package io.github.phoenix153.mytube

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Einrichtungsassistent beim ersten Start (3 Schritte). Die Oberfläche wird im Code gebaut,
 * damit keine zusätzlichen Layout-Dateien nötig sind.
 */
class OnboardingActivity : ComponentActivity() {

    private data class Step(
        val title: String,
        val text: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
    )

    private val youtubeInstalled by lazy {
        try {
            packageManager.getPackageInfo(YOUTUBE_PACKAGE, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private val steps by lazy {
        listOfNotNull(
            Step(
                "Willkommen bei MyTube",
                "YouTube – ohne Shorts und ohne Werbung.\n\n" +
                    "Melde dich nach der Einrichtung oben rechts mit deinem Google-Konto an. " +
                    "Du bleibst dauerhaft angemeldet.",
            ),
            Step(
                "YouTube-Links in MyTube öffnen",
                "Damit Links aus WhatsApp, Mail usw. hier statt im Browser landen:\n\n" +
                    "Tippe unten auf den Knopf, dann auf „Link hinzufügen“ (oder „Unterstützte " +
                    "Links öffnen“) und hake alle YouTube-Adressen an.",
                "Einstellungen öffnen",
            ) { openLinkSettings() },
            if (youtubeInstalled) Step(
                "YouTube-App deaktivieren",
                "Damit du nicht aus Gewohnheit doch die Shorts-App öffnest:\n\n" +
                    "Tippe unten auf den Knopf und dann auf „Deaktivieren“. Das lässt sich " +
                    "jederzeit rückgängig machen.",
                "YouTube-App öffnen",
            ) { openAppDetails(YOUTUBE_PACKAGE) } else null,
        )
    }

    private var index = 0
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var action: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        show(0)
        onBackPressedDispatcher.addCallback(this) { if (index > 0) show(index - 1) else finishOnboarding() }
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        title = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE) }
        text = TextView(this).apply {
            textSize = 17f; setTextColor(Color.LTGRAY); setPadding(0, pad, 0, pad)
        }
        action = Button(this)
        next = Button(this).apply { setOnClickListener { if (index < steps.lastIndex) show(index + 1) else finishOnboarding() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(0x1E, 0x3A, 0x5F))
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(text)
            addView(action, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(next, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun show(i: Int) {
        index = i
        val step = steps[i]
        title.text = step.title
        text.text = step.text
        action.visibility = if (step.action != null) Button.VISIBLE else Button.GONE
        action.text = step.actionLabel
        action.setOnClickListener { step.action?.invoke() }
        next.text = if (i < steps.lastIndex) "Weiter (${i + 1}/${steps.size})" else "Los geht's"
    }

    private fun finishOnboarding() {
        Prefs.setBool(this, DONE_KEY, true)
        finish()
    }

    /** Ab Android 12 gibt es eine eigene Seite "Standardmäßig öffnen" – sonst die App-Details. */
    private fun openLinkSettings() {
        val uri = Uri.parse("package:$packageName")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri)
        else
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        runCatching { startActivity(intent) }.onFailure { openAppDetails(packageName) }
    }

    private fun openAppDetails(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    companion object {
        const val DONE_KEY = "onboarding_done"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
