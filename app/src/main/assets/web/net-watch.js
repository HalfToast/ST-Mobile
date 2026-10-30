/*
 * ST Mobile: tells the app when one of SillyTavern's own requests to its server fails at the network
 * level (the tablet asleep, the server stopped, Tailscale off), so it can check the connection and
 * cover the chat instead of letting ST show an empty chat and "could not be saved" errors. Injected at
 * document start by NetWatch.kt.
 *
 * - fetch: a rejection with a TypeError (a network error; not an HTTP 4xx/5xx, not an abort) for a
 *   same-origin URL. XHR: a same-origin request that ends with status 0 without being aborted.
 * - Requests are untouched: the wrapped fetch resolves or rejects with exactly what the original gave,
 *   and XHR only gains listeners.
 * - Counts every failure (window.__stmNetWatch.failures) and posts "fail" to the app's STMobileNet
 *   listener at most once per POST_GAP_MS. Any failure here is swallowed. Never logs, never sends a URL.
 */
(function () {
  'use strict';
  if (window.__stmNetWatch) return;
  var state = { failures: 0 };
  window.__stmNetWatch = state;

  var POST_GAP_MS = 1000;
  var lastPost = -Infinity;

  function report() {
    try {
      state.failures++;
      var now = Date.now();
      if (now - lastPost < POST_GAP_MS) return;
      lastPost = now;
      STMobileNet.postMessage('fail');
    } catch (e) { /* no bridge: nothing to tell */ }
  }

  function sameOrigin(url) {
    try {
      return new URL(String(url), location.href).origin === location.origin;
    } catch (e) {
      return false;
    }
  }

  try {
    var originalFetch = window.fetch;
    if (typeof originalFetch === 'function') {
      window.fetch = function (input) {
        var result = originalFetch.apply(this, arguments);
        var url;
        try {
          url = input && typeof input === 'object' && 'url' in input ? input.url : input;
        } catch (e) { url = null; }
        if (!result || typeof result.then !== 'function') return result;
        return result.then(null, function (error) {
          try {
            if (error instanceof TypeError && url != null && sameOrigin(url)) report();
          } catch (e) { /* as if unwatched */ }
          throw error;
        });
      };
    }
  } catch (e) { /* fetch stays as it was */ }

  try {
    var proto = XMLHttpRequest.prototype;
    var originalOpen = proto.open;
    var originalSend = proto.send;
    proto.open = function (method, url) {
      try { this.__stmUrl = url; } catch (e) { /* ignore */ }
      return originalOpen.apply(this, arguments);
    };
    proto.send = function () {
      try {
        var xhr = this, aborted = false;
        if (xhr.__stmUrl != null && sameOrigin(xhr.__stmUrl)) {
          xhr.addEventListener('abort', function () { aborted = true; });
          xhr.addEventListener('loadend', function () {
            try {
              if (!aborted && xhr.status === 0) report();
            } catch (e) { /* ignore */ }
          });
        }
      } catch (e) { /* send as usual */ }
      return originalSend.apply(this, arguments);
    };
  } catch (e) { /* XHR stays as it was */ }
})();
