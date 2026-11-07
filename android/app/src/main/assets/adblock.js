/* ZapTV – bloqueo de anuncios dentro del reproductor embebido de YouTube.
   Se inyecta al inicio de cada frame de youtube.com / youtube-nocookie.com. */
(function () {
  if (window.__zapAB) return;
  window.__zapAB = 1;

  var KEYS = ['adPlacements', 'adSlots', 'playerAds'];

  function prune(o) {
    if (!o || typeof o !== 'object') return o;
    for (var i = 0; i < KEYS.length; i++) {
      if (KEYS[i] in o) { try { delete o[KEYS[i]]; } catch (e) {} }
    }
    if (o.playerResponse && typeof o.playerResponse === 'object') prune(o.playerResponse);
    return o;
  }
  function hasAds(r) {
    return r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.adSlots ||
      (r.playerResponse && (r.playerResponse.adPlacements || r.playerResponse.playerAds || r.playerResponse.adSlots)));
  }

  // 1) Quitar los datos de anuncios de las respuestas del reproductor
  var _parse = JSON.parse;
  JSON.parse = function () {
    var r = _parse.apply(this, arguments);
    try { if (hasAds(r)) prune(r); } catch (e) {}
    return r;
  };
  if (window.Response && Response.prototype.json) {
    var _json = Response.prototype.json;
    Response.prototype.json = function () {
      return _json.apply(this, arguments).then(function (r) {
        try { if (hasAds(r)) prune(r); } catch (e) {}
        return r;
      });
    };
  }
  try {
    var _v;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return _v; },
      set: function (v) { try { prune(v); } catch (e) {} _v = v; }
    });
  } catch (e) {}

  // 2) Si igual aparece un anuncio: silenciar, adelantar al final y pulsar "Omitir"
  function skip() {
    try {
      var p = document.querySelector('.html5-video-player');
      var ad = !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
      var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
      if (v) {
        if (ad) {
          if (!v.__zapAd) { v.__zapAd = true; v.__zapPrev = v.muted; }
          v.muted = true;
          try { v.playbackRate = 16; } catch (e) {}
          if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.1) {
            try { v.currentTime = v.duration; } catch (e) {}
          }
        } else if (v.__zapAd) {
          v.__zapAd = false;
          v.muted = !!v.__zapPrev;
          try { v.playbackRate = 1; } catch (e) {}
        }
      }
      var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
      if (b) b.click();
      var c = document.querySelector('.ytp-ad-overlay-close-button');
      if (c) c.click();
    } catch (e) {}
  }
  setInterval(skip, 250);
})();
