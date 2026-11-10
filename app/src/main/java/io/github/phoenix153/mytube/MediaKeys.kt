package io.github.phoenix153.mytube

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Play/Pause über Kopfhörer-Tasten (kabelgebunden und Bluetooth).
 *
 * Android schickt Medientasten an die App mit der aktiven MediaSession. Diese Klasse hält so
 * eine Session, meldet ihr, ob das Video gerade läuft, und gibt Tastendrücke als "play" bzw.
 * "pause" weiter (MainActivity schickt das an die Videoseite, siehe features.js).
 *
 * Bewusst ohne Benachrichtigung und ohne Hintergrunddienst: Die Tasten wirken, solange
 * MyTube sichtbar ist. Verlässt man die App, pausiert MainActivity.onStop das Video.
 */
class MediaKeys(context: Context, private val send: (command: String) -> Unit) {

    private val session = MediaSession(context, "MyTube").apply {
        setCallback(object : MediaSession.Callback() {
            // Die Standard-Behandlung von MediaSession wertet die Taste aus (Play/Pause,
            // Kopfhörer-Knopf) und ruft je nach gemeldetem Zustand onPlay oder onPause auf.
            override fun onPlay() = send("play")
            override fun onPause() = send("pause")
        })
    }

    /** Video läuft oder ist pausiert → Session aktiv, damit die Tasten hier ankommen. */
    fun setPlaying(playing: Boolean) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.isActive = true
    }

    /** Titel für Geräte, die ihn anzeigen (z. B. Bluetooth im Auto). */
    fun setTitle(title: String?) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title?.removeSuffix(" - YouTube").orEmpty())
                .build()
        )
    }

    /** Kein Video mehr offen → Tasten nicht mehr abfangen (z. B. für Musik-Apps freigeben). */
    fun stop() {
        session.setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build()
        )
        session.isActive = false
    }

    fun release() = session.release()
}
