/*
 * MyTube-Zusatzfunktionen, die m.youtube.com im Vergleich zur YouTube-App fehlen:
 *  1. Neuladen durch Herunterziehen am Seitenanfang (Pull-to-refresh)
 *  2. Mikrofon-Knopf in der Kopfzeile für die Sprachsuche
 *  3. Miniplayer-Unterstützung:
 *     - Wiedergabe-Zustand und -Position an die App melden (beide Tabs)
 *     - Video-Tab: im Hintergrund pausiert bereithalten ("standby"), auf Kommando an einer
 *       Stelle weiterspielen ("resumeAt"), im Mini-Modus nur das Videobild zeigen,
 *       Nicht-Video-Links an den Stöber-Tab abgeben
 *
 * Ablauf Miniplayer (siehe MainActivity): Videos laufen ganz normal im Stöber-Tab. Läuft eins,
 * lädt die App es pausiert im versteckten Video-Tab vor. Beim Verkleinern hält der Stöber-Tab
 * an, und der Video-Tab spielt an derselben Stelle in der Karte weiter.
 *
 * Die Kommunikation mit der App läuft über eine Verbindung (Port) pro geladener Seite;
 * auf der App-Seite gewinnt jeweils die neueste Verbindung eines Tabs (MainActivity.Tab).
 *
 * Protokoll:
 *   App → Seite  { cmd: "role", role: "browse" | "player" }   als Erstes nach dem Verbinden
 *                { cmd: "mini", on: true | false }            Miniplayer-Darstellung an/aus
 *                { cmd: "standby", on: true | false }         Video-Tab: nichts abspielen
 *                { cmd: "resumeAt", id, time }                Video-Tab: Video id ab time (s) spielen
 *                { cmd: "togglePlay" }                        Play/Pause aus der Karte
 *                { cmd: "play" } / { cmd: "pause" }           Kopfhörer-Tasten
 *                { cmd: "stop" }                              anhalten
 *                { cmd: "navigate", url }                     Seitenwechsel ohne Neuladen
 *                { cmd: "diag" }                              Größen melden (Fehlersuche)
 *   Seite → App  { type: "openInBrowse", url }                Seite im Stöber-Tab öffnen
 *                { type: "voiceSearch" }                      Android-Spracheingabe starten
 *                { type: "playState", playing, videoId }      Video läuft / pausiert
 *                { type: "progress", fraction, time, videoId } Position (höchstens 1×/s)
 *                { type: "diag", text }                       Antwort auf "diag"
 *
 * Läuft nach content.js (Reihenfolge in manifest.json), damit dessen Shorts-Sperre bei
 * Klicks zuerst greift.
 */
(() => {
  "use strict";

  const NATIVE_APP = "mytube"; // muss zu NATIVE_APP in MainActivity.kt passen

  let port = null;
  let role = null; // "browse" | "player" | null (= noch unbekannt oder nicht in der App)

  try {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onMessage.addListener(onAppMessage);
    // Ohne Verbindung keine Klicks mehr abfangen – sonst gingen sie ins Leere
    port.onDisconnect.addListener(() => { port = null; role = null; });
  } catch (_) {
    // Außerhalb der App (z. B. normaler Firefox) gibt es keine App – alles bleibt normal
  }

  function sendToApp(message) {
    if (port) port.postMessage(message);
  }

  function onAppMessage(msg) {
    switch (msg.cmd) {
      case "role":
        role = msg.role;
        watchPlayback();
        break;
      case "mini":
        document.documentElement.classList.toggle("mytube-mini", !!msg.on);
        break;
      case "standby":
        standby = !!msg.on;
        if (standby) setPlaying(false);
        break;
      case "resumeAt":
        standby = false;
        resumeAt(msg.id, msg.time || 0);
        break;
      case "togglePlay":
        setPlaying(!isPlaying());
        break;
      case "play":
      case "pause":
        setPlaying(msg.cmd === "play");
        break;
      case "stop":
        setPlaying(false);
        break;
      case "navigate":
        navigateInPage(msg.url);
        break;
      case "diag":
        sendToApp({ type: "diag", text: diagnostics() });
        break;
    }
  }

  /** Größen der Player-Ebenen – für die Fehlersuche (langes Drücken auf die Karte). */
  function diagnostics() {
    const vv = window.visualViewport;
    const lines = [
      `Fenster ${innerWidth}×${innerHeight}, DPR ${devicePixelRatio}` +
        (vv ? `, sichtbar ${Math.round(vv.width)}×${Math.round(vv.height)}` : ""),
      `100vh-Test: ${(() => {
        const d = document.createElement("div");
        d.style.cssText = "position:fixed;top:0;height:100vh;width:1px;visibility:hidden";
        document.documentElement.appendChild(d);
        const h = d.getBoundingClientRect().height;
        d.remove();
        return Math.round(h);
      })()}px`,
      `Mini-Klasse: ${document.documentElement.classList.contains("mytube-mini")}`,
    ];
    let el = document.querySelector("video");
    while (el && el !== document.documentElement) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      lines.push(
        `${el.tagName.toLowerCase()}${el.id ? "#" + el.id : ""}` +
          `${el.classList.length ? "." + el.classList[0] : ""}: ` +
          `${Math.round(r.width)}×${Math.round(r.height)} @${Math.round(r.left)},${Math.round(r.top)} ` +
          `${cs.position}${cs.transform !== "none" ? " tf" : ""}${cs.overflow !== "visible" ? " ov:" + cs.overflow : ""}`
      );
      el = el.parentElement;
    }
    return lines.join("\n");
  }

  function isOnWatchPage() {
    return location.pathname === "/watch";
  }

  function currentVideoId() {
    return isOnWatchPage() ? new URLSearchParams(location.search).get("v") : null;
  }

  // -----------------------------------------------------------------------------------------
  // 1. Pull-to-refresh
  // -----------------------------------------------------------------------------------------
  const TRIGGER_PX = 110; // so weit muss man ziehen, damit neu geladen wird

  let startY = null;
  let pulled = 0;
  let indicator = null;

  function makeIndicator() {
    const el = document.createElement("div");
    el.textContent = "↻";
    el.style.cssText =
      "position:fixed;top:0;left:50%;z-index:2147483646;width:40px;height:40px;margin-left:-20px;" +
      "border-radius:50%;background:#fff;color:#c00;font:bold 24px/40px sans-serif;text-align:center;" +
      "box-shadow:0 2px 6px rgba(0,0,0,.35);pointer-events:none;opacity:0;transform:translateY(-48px)";
    document.documentElement.appendChild(el);
    return el;
  }

  function showPull(distance) {
    indicator = indicator || makeIndicator();
    const progress = Math.min(distance / TRIGGER_PX, 1);
    indicator.style.transition = "none";
    indicator.style.opacity = String(progress);
    indicator.style.transform = `translateY(${progress * 70 - 48}px) rotate(${progress * 270}deg)`;
  }

  function hidePull() {
    if (!indicator) return;
    indicator.style.transition = "transform .2s, opacity .2s";
    indicator.style.opacity = "0";
    indicator.style.transform = "translateY(-48px)";
  }

  document.addEventListener("touchstart", (e) => {
    // Nur ganz oben auf der Seite, mit einem Finger, und nicht auf der Videoseite
    // (dort gehört das Wischen dem Player bzw. dem Verkleinern)
    startY = window.scrollY <= 0 && e.touches.length === 1 && !isOnWatchPage() ? e.touches[0].clientY : null;
    pulled = 0;
  }, { passive: true, capture: true });

  document.addEventListener("touchmove", (e) => {
    if (startY === null) return;
    pulled = e.touches[0].clientY - startY;
    if (pulled > 10 && window.scrollY <= 0) showPull(pulled);
    else if (pulled < 0) startY = null; // nach oben gewischt = normales Scrollen
  }, { passive: true, capture: true });

  document.addEventListener("touchend", () => {
    if (startY !== null && pulled >= TRIGGER_PX) {
      showPull(TRIGGER_PX);
      location.reload();
    } else {
      hidePull();
    }
    startY = null;
  }, { passive: true, capture: true });

  // -----------------------------------------------------------------------------------------
  // 2. Mikrofon-Knopf für die Sprachsuche
  // -----------------------------------------------------------------------------------------
  const MIC_ID = "mytube-voice-search";
  const MIC_SVG =
    '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true">' +
    '<path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 ' +
    '6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/></svg>';

  function addMicButton() {
    if (document.getElementById(MIC_ID)) return;
    // Kopfzeile im normalen Modus: dort sitzt die Lupe; der Mikrofon-Knopf kommt davor
    const searchButton = document.querySelector("header .topbar-button-search-button");
    if (!searchButton) return;
    const mic = document.createElement("button");
    mic.id = MIC_ID;
    mic.className = searchButton.className; // gleiches Aussehen wie die Lupe
    mic.setAttribute("aria-label", "Sprachsuche");
    mic.innerHTML = MIC_SVG;
    mic.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      sendToApp({ type: "voiceSearch" });
    });
    searchButton.parentNode.insertBefore(mic, searchButton);
  }

  // YouTube baut die Kopfzeile bei Seitenwechseln neu → bei Änderungen nachsehen,
  // gebündelt auf höchstens einmal pro Frame
  let micScheduled = false;
  new MutationObserver(() => {
    if (micScheduled) return;
    micScheduled = true;
    requestAnimationFrame(() => {
      micScheduled = false;
      addMicButton();
    });
  }).observe(document, { childList: true, subtree: true });

  // -----------------------------------------------------------------------------------------
  // 3. Miniplayer
  // -----------------------------------------------------------------------------------------

  // Im Video-Tab öffnen alle Nicht-Video-Seiten (Kanal, Suche …) im Stöber-Tab.
  // Shorts-Klicks hat content.js vorher schon gestoppt.
  const YT_PAGE_HOSTS = ["youtube.com", "www.youtube.com", "m.youtube.com"];

  document.addEventListener("click", (e) => {
    if (role !== "player" || !port || !e.isTrusted) return; // nur echte Klicks, nur mit App-Verbindung
    const link = e.target.closest && e.target.closest("a[href]");
    if (!link) return;
    let url;
    try { url = new URL(link.getAttribute("href"), location.href); } catch (_) { return; }
    if (!YT_PAGE_HOSTS.includes(url.hostname) || url.pathname === "/watch") return;
    e.preventDefault();
    e.stopImmediatePropagation();
    sendToApp({ type: "openInBrowse", url: url.href });
  }, true);

  // Mini-Modus: alles außer dem Videobild ausblenden; das Bild füllt die Fläche.
  // YouTube verschachtelt den Player in mehrere Ebenen mit eigener Größe
  // (#player-container-id > #player > #movie_player > .html5-video-container > video).
  // Alle Ebenen auf die ganze Fläche ziehen – mit inset/100 % statt vh, weil vh beim
  // Verkleinern der Ansicht nicht zuverlässig mitgeht.
  const MINI_CSS = `
    html.mytube-mini, html.mytube-mini body { overflow: hidden !important; background: #000 !important; }
    html.mytube-mini body * { visibility: hidden !important; }
    html.mytube-mini #player-container-id,
    html.mytube-mini #player-container-id * { visibility: visible !important; }
    html.mytube-mini #player-container-id {
      position: fixed !important; inset: 0 !important;
      width: auto !important; height: auto !important; margin: 0 !important; padding: 0 !important;
      z-index: 2147483647 !important; transform: none !important;
    }
    html.mytube-mini #player-container-id #player,
    html.mytube-mini #player-container-id #movie_player,
    html.mytube-mini #player-container-id .html5-video-container {
      position: absolute !important; inset: 0 !important;
      width: 100% !important; height: 100% !important; max-height: none !important;
      padding: 0 !important; margin: 0 !important; transform: none !important;
    }
    html.mytube-mini #player-container-id video {
      position: absolute !important; inset: 0 !important;
      width: 100% !important; height: 100% !important;
      object-fit: contain !important; transform: none !important;
    }
    html.mytube-mini #player-control-container,
    html.mytube-mini .ytp-unmute, html.mytube-mini .ytp-paid-content-overlay { display: none !important; }
  `;
  function addMiniStyle() {
    const style = document.createElement("style");
    style.textContent = MINI_CSS;
    (document.head || document.documentElement).appendChild(style);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", addMiniStyle, { once: true });
  } else {
    addMiniStyle();
  }

  // --- Steuerung über YouTubes eigenen Player ---
  // WICHTIG: Nicht das <video>-Element direkt steuern. YouTubes Player führt einen eigenen
  // Zustand; ein video.play() an ihm vorbei startet das Bild nur kurz, dann pausiert YouTube
  // wieder. Das Element #movie_player bietet playVideo()/pauseVideo()/seekTo()/getPlayerState().
  // Es gehört zum Seiten-JavaScript; ein Content-Script erreicht es über wrappedJSObject.
  const YT_PLAYING = 1;
  const YT_BUFFERING = 3;

  function ytPlayer() {
    const el = document.getElementById("movie_player");
    const api = el && el.wrappedJSObject;
    return api && typeof api.playVideo === "function" ? api : null;
  }

  function isPlaying() {
    const api = ytPlayer();
    if (api && typeof api.getPlayerState === "function") {
      const state = api.getPlayerState();
      return state === YT_PLAYING || state === YT_BUFFERING;
    }
    const v = document.querySelector("video");
    return !!v && !v.paused;
  }

  function setPlaying(play) {
    const api = ytPlayer();
    if (api) {
      play ? api.playVideo() : api.pauseVideo();
      return;
    }
    // Rückfall, falls YouTube den Player umbenennt
    const v = document.querySelector("video");
    if (v) play ? v.play().catch(() => {}) : v.pause();
  }

  // --- Standby: Der versteckte Video-Tab lädt das Video vor, darf es aber nicht abspielen.
  // YouTube startet Videos auf der Videoseite selbst; das wird hier sofort wieder angehalten.
  let standby = false;

  // --- Weiterspielen beim Verkleinern: warten, bis YouTube das richtige Video geladen hat,
  // dann an die Stelle springen und abspielen (höchstens 10 s lang versuchen).
  let resumeTimer = 0;
  function resumeAt(id, time) {
    clearInterval(resumeTimer);
    const started = Date.now();
    const tryResume = () => {
      if (Date.now() - started > 10000) { clearInterval(resumeTimer); return; }
      const api = ytPlayer();
      if (!api || currentVideoId() !== id || typeof api.seekTo !== "function") return;
      clearInterval(resumeTimer);
      api.seekTo(time, true);
      api.playVideo();
    };
    resumeTimer = setInterval(tryResume, 200);
    tryResume();
  }

  /**
   * Seitenwechsel innerhalb der geladenen Seite – wie bei einem Klick, ohne Neuladen.
   * YouTubes Router reagiert auf "popstate" und zeigt dann die Seite zur aktuellen Adresse.
   * Beides muss im Seiten-Kontext passieren (wrappedJSObject/cloneInto), damit YouTubes Code
   * das Ereignis und seinen Inhalt lesen darf.
   */
  function navigateInPage(path) {
    try {
      const page = window.wrappedJSObject;
      page.history.pushState(cloneInto({}, page), "", path);
      page.dispatchEvent(new page.PopStateEvent("popstate", cloneInto({ state: {} }, page)));
    } catch (e) {
      console.warn("MyTube: Seitenwechsel per Skript fehlgeschlagen", e);
      location.href = path;
    }
  }

  // --- Wiedergabe beobachten und an die App melden (beide Tabs) ---
  // Media-Events "bubbeln" nicht, deshalb in der Capture-Phase am Dokument lauschen.
  let watching = false;
  function watchPlayback() {
    if (watching) return;
    watching = true;

    document.addEventListener("play", (e) => {
      if (!(e.target instanceof HTMLVideoElement)) return;
      if (standby) { setPlaying(false); return; } // Video-Tab im Hintergrund: still bleiben
      sendToApp({ type: "playState", playing: true, videoId: currentVideoId() });
    }, true);
    document.addEventListener("pause", (e) => {
      if (!(e.target instanceof HTMLVideoElement) || standby) return;
      sendToApp({ type: "playState", playing: false, videoId: currentVideoId() });
    }, true);

    let lastProgress = 0;
    const reportProgress = (e) => {
      const v = e.target;
      if (!(v instanceof HTMLVideoElement) || standby) return;
      const now = Date.now();
      if (e.type === "timeupdate" && now - lastProgress < 1000) return;
      lastProgress = now;
      const fraction = v.duration > 0 && isFinite(v.duration) ? v.currentTime / v.duration : 0;
      // Position laut YouTubes Player (bezieht sich aufs eigentliche Video, nicht auf Werbung)
      const api = ytPlayer();
      const time = api && typeof api.getCurrentTime === "function" ? api.getCurrentTime() : v.currentTime;
      sendToApp({ type: "progress", fraction, time, videoId: currentVideoId() });
    };
    document.addEventListener("timeupdate", reportProgress, true);
    document.addEventListener("loadstart", reportProgress, true); // neues Video → Balken auf 0
  }
})();
