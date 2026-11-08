/*
 * MyTube Shorts-Blocker – läuft auf jeder YouTube-Seite.
 *
 * Aufgaben:
 *  1. Klicks auf Shorts-Links abfangen, bevor YouTube sie öffnet.
 *  2. Alles ausblenden, was auf Shorts verlinkt oder "Shorts" heißt – auch das, was
 *     content.css (noch) nicht kennt.
 * Das harte Sperren von Shorts-Adressen übernimmt die App selbst (MainActivity.kt).
 */
(() => {
  "use strict";

  // /shorts/ID, /shorts sowie Kanal-Tabs wie /@name/shorts
  const SHORTS_PATH = /^\/(shorts(\/|$)|(@[^/]+|c\/[^/]+|channel\/[^/]+|user\/[^/]+)\/shorts\/?$)/;

  // Container, die als Ganzes verschwinden sollen, wenn sie einen Shorts-Link enthalten
  const CARD_SELECTOR = [
    "ytm-video-with-context-renderer",
    "ytm-compact-video-renderer",
    "ytm-rich-item-renderer",
    "ytm-reel-item-renderer",
    "ytm-shorts-lockup-view-model",
    "ytm-shorts-lockup-view-model-v2",
    "ytm-reel-shelf-renderer",
    "ytm-rich-section-renderer",
    "grid-shelf-view-model",
    "ytm-pivot-bar-item-renderer",
    "ytm-media-item",
    "yt-tab-shape",
  ].join(",");

  // Elemente, die nur über ihren Text als Shorts erkennbar sind (Navigation, Tabs, Filter-Chips)
  const LABELLED_SELECTOR = "ytm-pivot-bar-item-renderer, yt-tab-shape, ytm-chip-cloud-chip-renderer, yt-chip-cloud-chip-renderer";

  function isShortsHref(href) {
    try {
      return SHORTS_PATH.test(new URL(href, location.href).pathname);
    } catch (_) {
      return false;
    }
  }

  // --- 1. Klicks abfangen (Capture-Phase: wir sind vor YouTubes eigenen Handlern dran) ---
  function onClick(event) {
    const link = event.target.closest && event.target.closest("a[href]");
    if (link && isShortsHref(link.getAttribute("href"))) {
      event.preventDefault();
      event.stopImmediatePropagation();
      toast("Shorts sind gesperrt");
    }
  }
  document.addEventListener("click", onClick, true);

  // --- 2. Ausblenden ---
  function hide(el) {
    if (el && el.style.display !== "none") el.style.setProperty("display", "none", "important");
  }

  function sweep(root) {
    for (const a of root.querySelectorAll("a[href]")) {
      if (isShortsHref(a.getAttribute("href"))) hide(a.closest(CARD_SELECTOR) || a);
    }
    for (const el of root.querySelectorAll(LABELLED_SELECTOR)) {
      if (el.textContent.trim() === "Shorts") hide(el);
    }
  }

  // YouTube lädt Inhalte laufend nach (Scrollen, Seitenwechsel) → bei Änderungen erneut prüfen,
  // gebündelt auf einmal pro Frame, damit die Seite flüssig bleibt.
  let scheduled = false;
  const observer = new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      sweep(document);
    });
  });

  function start() {
    sweep(document);
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }

  // --- Kleiner Hinweis unten am Bildschirm ---
  let toastEl = null;
  let toastTimer = 0;
  function toast(message) {
    if (!toastEl) {
      toastEl = document.createElement("div");
      toastEl.style.cssText =
        "position:fixed;left:50%;bottom:80px;transform:translateX(-50%);z-index:2147483647;" +
        "background:rgba(30,30,30,.92);color:#fff;padding:10px 18px;border-radius:20px;" +
        "font:14px sans-serif;pointer-events:none;transition:opacity .3s";
      document.documentElement.appendChild(toastEl);
    }
    toastEl.textContent = message;
    toastEl.style.opacity = "1";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.style.opacity = "0"), 1800);
  }
})();
