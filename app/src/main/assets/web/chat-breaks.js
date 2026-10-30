/*
 * ST Mobile: chat messages lose the empty lines Chromium draws for line breaks that come before any
 * content. Injected at document start by ChatBreaks.kt.
 *
 * SillyTavern's style.css makes every <br> `display:block` (and `.mes_text br { content:" " }`). In
 * Chromium each such break is a full empty line; in Firefox a leading one takes no room. Templates
 * that start a paragraph or a <details> card with breaks therefore show a gap on the phone only.
 *
 * - Only breaks before any content are hidden (a class plus one CSS rule): leading <br>s of a <p>, of
 *   a <summary>, and of a <details> (whose <summary> is drawn first, so breaks around it lead too).
 *   Whitespace and comments between them still count as leading. Breaks between or after text stay.
 * - Only inside `#chat .mes_text`. One MutationObserver on #chat collects the messages touched by
 *   each batch of mutations and re-checks just those, once per frame (streaming rewrites a message on
 *   every chunk; the whole chat is never rescanned after the first pass).
 * - Any failure leaves the page as SillyTavern renders it. Never logs: messages are page data.
 */
(function () {
  'use strict';
  if (window.__stmChatBreaks) return;
  window.__stmChatBreaks = true;

  var MARK = 'stm-lead-br';
  var CSS_ID = 'stm-chat-breaks-css';
  var CONTAINERS = 'p, summary, details';
  // A timer backs up the frame callback: frames don't run while the page is hidden.
  var FALLBACK_MS = 100;

  var pending = [];   // .mes_text elements to re-check
  var QUEUED = '__stmQueued'; // set on an element while it is in [pending]: a chat of thousands loads in one batch
  var scheduled = false;

  function injectCss() {
    if (document.getElementById(CSS_ID)) return;
    var style = document.createElement('style');
    style.id = CSS_ID;
    style.textContent = '#chat .mes_text br.' + MARK + '{display:none !important;}';
    (document.head || document.documentElement).appendChild(style);
  }

  /** Marks the <br>s that lead [box]: before its first content (a <details>' own <summary> skipped). */
  function markLeading(box) {
    var isDetails = box.tagName === 'DETAILS';
    for (var n = box.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 8) continue; // comment
      if (n.nodeType === 3) {
        if (/\S/.test(n.nodeValue)) return;
        continue;
      }
      if (n.nodeType !== 1) return;
      if (n.tagName === 'BR') {
        n.classList.add(MARK);
        continue;
      }
      if (isDetails && n.tagName === 'SUMMARY') continue;
      return;
    }
  }

  /** Re-checks one message: clears its marks (its text may have changed), then marks again. */
  function check(mesText) {
    var old = mesText.getElementsByClassName(MARK);
    while (old.length) old[0].classList.remove(MARK);
    var boxes = mesText.querySelectorAll(CONTAINERS);
    for (var i = 0; i < boxes.length; i++) markLeading(boxes[i]);
  }

  function flush() {
    scheduled = false;
    var batch = pending;
    pending = [];
    for (var i = 0; i < batch.length; i++) {
      try {
        batch[i][QUEUED] = false;
        if (batch[i].isConnected) check(batch[i]);
      } catch (e) { /* leave this message as ST renders it */ }
    }
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var done = false;
    function run() {
      if (done) return;
      done = true;
      flush();
    }
    requestAnimationFrame(run);
    setTimeout(run, FALLBACK_MS);
  }

  function queue(mesText) {
    if (mesText[QUEUED]) return;
    mesText[QUEUED] = true;
    pending.push(mesText);
  }

  /** The messages a mutation touched: the one around its target, and any added whole. */
  function collect(record) {
    var t = record.target;
    var el = t.nodeType === 1 ? t : t.parentElement;
    var mes = el && el.closest ? el.closest('.mes_text') : null;
    if (mes) {
      queue(mes);
      return;
    }
    var added = record.addedNodes;
    for (var i = 0; i < added.length; i++) {
      var a = added[i];
      if (a.nodeType !== 1) continue;
      if (a.classList.contains('mes_text')) queue(a);
      var inner = a.getElementsByClassName('mes_text');
      for (var j = 0; j < inner.length; j++) queue(inner[j]);
    }
  }

  function start() {
    var chat = document.getElementById('chat');
    if (!chat) return;
    injectCss();
    var all = chat.getElementsByClassName('mes_text');
    for (var i = 0; i < all.length; i++) queue(all[i]);
    schedule();
    new MutationObserver(function (records) {
      try {
        for (var k = 0; k < records.length; k++) collect(records[k]);
        if (pending.length) schedule();
      } catch (e) { /* as ST renders it */ }
    }).observe(chat, { childList: true, subtree: true, characterData: true });
  }

  function safeStart() {
    try { start(); } catch (e) { /* as ST renders it */ }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', safeStart);
  else safeStart();
})();
