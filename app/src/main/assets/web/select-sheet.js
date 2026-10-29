/*
 * ST Mobile: SillyTavern's native <select> dropdowns open a themed bottom sheet instead of
 * Android's plain list dialog. Injected at document start by SelectSheet.kt.
 *
 * - The sheet is a modal <dialog id="stm-select-sheet"> so it sits in the top layer, above ST's own
 *   modal popups (a select inside an ST popup would otherwise get a sheet that is covered and inert).
 *   It carries a permanent `closing` attribute so ST's "topmost open dialog" lookups
 *   (`dialog[open]:not([closing])`, used for toasts and the streaming display) skip it.
 * - ST sees what a native pick gives it: the same selectedIndex / option.selected and one input then
 *   one change event, and nothing at all when the value did not change.
 * - Long lists (hundreds of models) stay fast: the options are read once into a plain array, and only
 *   the rows in and near view exist as elements, absolutely placed in a spacer and recycled while
 *   scrolling. Every row has the same measured height (one line, ellipsised), so positions and the
 *   scroll to the current row are arithmetic. Search runs over the array.
 * - It slides up and down (the dim fades) over CLOSE_MS, transform and opacity only. A pick applies
 *   at once; closing takes it away from the page at once (no stm-open, isOpen() false, "closed"
 *   posted) and only dialog.close() waits for the slide. Opening again or a cancel (Back) finishes a
 *   pending close at once. It closes at once, without sliding, when the search field had focus (the
 *   keyboard is going down) or with prefers-reduced-motion.
 * - Any failure while opening returns false, and the native list opens as before.
 * - Tells the app when it opens and closes (STMobileSheet web message listener, "open" / "closed"),
 *   so the app's floating button can step out of the way. "closed" only ever follows an "open".
 * - Never logs: option text is page data.
 */
(function () {
  'use strict';
  if (window.__stmSelectSheet) return;

  var ROOT_ID = 'stm-select-sheet';
  var CSS_ID = 'stm-select-sheet-css';
  var SEARCH_ABOVE = 8;
  var SWIPE_CLOSE_PX = 80;
  var CLOSE_MS = 200;
  var CLIP_SUPPORTED = !!(window.CSS && window.CSS.supports && window.CSS.supports('overflow', 'clip'));
  // Rows rendered beyond each edge of the visible part of the list, in px.
  var BUFFER_PX = 400;
  // The tap that opened the sheet must not also hit it. A click only counts after a pointerdown
  // inside the sheet, or once this long has passed (clicks without pointer events, e.g. TalkBack).
  var CLICK_GUARD_MS = 500;

  var TEXT = 'var(--SmartThemeBodyColor, rgb(195,195,195))';
  var MUTED = 'var(--SmartThemeEmColor, rgb(150,152,158))';
  var ACCENT = 'var(--SmartThemeQuoteColor, rgb(170,219,255))';
  var BG = 'var(--SmartThemeBlurTintColor, rgb(24,24,27))';
  var BORDER = 'var(--SmartThemeBorderColor, rgba(255,255,255,0.08))';
  var FONT = 'var(--mainFontFamily, "Noto Sans", sans-serif)';
  var TINT_FALLBACK = 'rgba(170,219,255,0.07)';
  var TINT = 'color-mix(in srgb, ' + ACCENT + ' 7%, transparent)';
  var S = '#' + ROOT_ID + ' ';
  var S2_OPTION = 'html .select2-results .select2-results__option';

  var CSS = [
    '#' + ROOT_ID + '{position:fixed;inset:0;width:100%;height:100%;max-width:none;max-height:none;' +
      // clip, not just hidden: an overflow:hidden box is still a scroll container, and showModal()
      // focusing the grab area while the panel starts 100% below scrolled it (the panel then rose
      // past its place and dropped back, D4).
      'margin:0;padding:0;border:0;background:transparent;overflow:hidden;overflow:clip;color:' + TEXT + ';' +
      'font-family:' + FONT + ';font-size:var(--mainFontSize, 15px);text-align:left;text-shadow:none;}',
    '#' + ROOT_ID + ':not([open]){display:none;}',
    '#' + ROOT_ID + '::backdrop{background:transparent;}',
    S + '[hidden]{display:none !important;}',
    S + '.stm-dim{position:absolute;inset:0;background:rgba(0,0,0,0.55);opacity:0;transition:opacity .2s ease-out;}',
    '#' + ROOT_ID + '.stm-open .stm-dim{opacity:1;}',
    S + '.stm-panel{position:absolute;left:0;right:0;bottom:0;box-sizing:border-box;display:flex;' +
      'flex-direction:column;max-height:75vh;max-height:75dvh;background:' + BG + ';' +
      'border-top:1px solid ' + BORDER + ';border-radius:18px 18px 0 0;overflow:hidden;overflow:clip;' +
      'padding:8px 0 max(8px, env(safe-area-inset-bottom));transform:translateY(100%);' +
      'transition:transform .2s ease-out;}',
    '#' + ROOT_ID + '.stm-open .stm-panel{transform:none;}',
    // Where an opening sheet comes from (older WebViews without @starting-style just show it).
    '@starting-style{#' + ROOT_ID + '.stm-open .stm-panel{transform:translateY(100%);}' +
      '#' + ROOT_ID + '.stm-open .stm-dim{opacity:0;}}',
    '@media (prefers-reduced-motion: reduce){' + S + '.stm-panel,' + S + '.stm-dim{transition:none;}}',
    S + '.stm-head{flex:none;touch-action:none;outline:none;}',
    S + '.stm-handle{width:38px;height:4px;border-radius:2px;background:' + TEXT + ';opacity:.25;margin:0 auto 10px;}',
    S + '.stm-title{font-weight:600;font-size:1.07em;padding:4px 16px 10px;overflow-wrap:anywhere;}',
    S + '.stm-search{flex:none;display:block;box-sizing:border-box;width:calc(100% - 28px);margin:0 14px 8px;' +
      'padding:8px 10px;border:1px solid ' + BORDER + ';border-radius:8px;background:rgba(255,255,255,0.03);' +
      'color:' + TEXT + ';font:inherit;font-size:.93em;outline:none;-webkit-appearance:none;appearance:none;}',
    S + '.stm-search::placeholder{color:' + MUTED + ';}',
    S + '.stm-list{position:relative;flex:0 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;}',
    S + '.stm-spacer{position:relative;}',
    // Rows, headers and dividers are absolutely placed in the spacer, one line each (fixed heights).
    S + '.stm-row,' + S + '.stm-group,' + S + '.stm-divider{position:absolute;left:0;right:0;top:0;' +
      'box-sizing:border-box;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
    S + '.stm-row{padding:11px 16px;line-height:1.3;cursor:pointer;user-select:none;-webkit-user-select:none;' +
      '-webkit-tap-highlight-color:transparent;}',
    S + '.stm-row:active:not(.stm-disabled){background:rgba(255,255,255,0.04);}',
    S + '.stm-row.stm-selected{color:' + ACCENT + ';background:' + TINT_FALLBACK + ';box-shadow:inset 3px 0 0 ' + ACCENT + ';}',
    S + '.stm-row.stm-selected{padding-right:40px;}',
    S + '.stm-row.stm-selected::after{content:"\\2713";color:' + ACCENT + ';position:absolute;right:16px;top:11px;}',
    S + '.stm-row.stm-disabled{opacity:.4;cursor:default;}',
    S + '.stm-divider{height:9px;padding:4px 16px;background:' + BORDER + ' content-box;}',
    S + '.stm-group{padding:10px 16px 4px;font-size:.8em;font-weight:600;color:' + MUTED + ';}',
    S + '.stm-empty{padding:16px;text-align:center;color:' + MUTED + ';}',
    S + '.stm-foot{flex:none;display:flex;justify-content:flex-end;padding:8px 14px 0;border-top:1px solid ' + BORDER + ';}',
    S + '.stm-done{font:inherit;color:' + ACCENT + ';background:rgba(0,0,0,0.3);border:1px solid ' + BORDER + ';' +
      'border-radius:5px;padding:6px 18px;cursor:pointer;}',
    // select2 (ST's searchable lists) keeps its own dropdown, restyled to match the sheet.
    'html .select2-dropdown{background-color:' + BG + ';border:1px solid ' + BORDER + ' !important;' +
      'border-radius:12px;color:' + TEXT + ';font-family:' + FONT + ';overflow:hidden;}',
    'html .select2-search--dropdown{padding:8px;}',
    'html .select2-search--dropdown .select2-search__field{box-sizing:border-box;padding:8px 10px;' +
      'border:1px solid ' + BORDER + ';border-radius:8px;background:rgba(255,255,255,0.03);color:' + TEXT + ';outline:none;}',
    S2_OPTION + ':not(.select2-results__option--group){padding-top:11px;padding-bottom:11px;padding-right:16px;' +
      'color:' + TEXT + ';background-color:transparent;opacity:1;}',
    S2_OPTION + '--highlighted.select2-results__option--selectable{color:' + ACCENT + ';' +
      'background-color:' + TINT_FALLBACK + ';box-shadow:inset 3px 0 0 ' + ACCENT + ';}',
    '@supports (background:color-mix(in srgb, red 7%, transparent)){' +
      S + '.stm-row.stm-selected{background:' + TINT + ';}' +
      S2_OPTION + '--highlighted.select2-results__option--selectable{background-color:' + TINT + ';}}',
  ].join('\n');

  var MARKUP =
    '<div class="stm-dim"></div>' +
    '<div class="stm-panel">' +
      '<div class="stm-head" tabindex="-1" autofocus><div class="stm-handle"></div><div class="stm-title"></div></div>' +
      '<input class="stm-search" type="search" placeholder="Search" autocomplete="off" spellcheck="false" enterkeyhint="search">' +
      '<div class="stm-list" role="listbox"><div class="stm-spacer"></div></div>' +
      '<div class="stm-empty" hidden>No matches</div>' +
      '<div class="stm-foot"><button type="button" class="stm-done">Done</button></div>' +
    '</div>';

  var root = null, panel, head, titleEl, search, list, spacer, empty, foot;
  // The open list: every shown option once, then what the list shows now (search applied).
  var items = [];       // {index, label, lower, disabled, selected, groupBreak, groupLabel}
  var entries = [];     // {kind: 'row' | 'group' | 'divider', item, top}
  var heights = null;   // measured px height of each entry kind
  var rendered = {};    // entry number -> its element, for the entries in and near view
  var current = null;   // the open <select>
  var observer = null;
  var picked = null;    // multiple: per-option selected state while open
  var armed = false, openedAt = 0;
  var announced = false; // the app was told "open" and not yet "closed"
  var dragStart = null, dragDy = 0;
  var closeTimer = null; // a closed sheet still sliding down; dialog.close() is pending
  var focusAtDown = null; // what had focus when the current tap went down

  function injectCss() {
    if (document.getElementById(CSS_ID)) return;
    var style = document.createElement('style');
    style.id = CSS_ID;
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function ensureRoot() {
    if (root) return;
    var dlg = document.createElement('dialog');
    dlg.id = ROOT_ID;
    dlg.setAttribute('closing', '');
    dlg.innerHTML = MARKUP;
    panel = dlg.querySelector('.stm-panel');
    head = dlg.querySelector('.stm-head');
    titleEl = dlg.querySelector('.stm-title');
    search = dlg.querySelector('.stm-search');
    list = dlg.querySelector('.stm-list');
    spacer = dlg.querySelector('.stm-spacer');
    empty = dlg.querySelector('.stm-empty');
    foot = dlg.querySelector('.stm-foot');

    // Taps inside the sheet stay inside it: ST's page-level outside-click handlers
    // ($('html').on('touchstart mousedown')) would otherwise collapse the drawer behind the sheet.
    // The native dialog sends the page no events at all. Our own handlers sit on this root or below.
    ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mouseup', 'pointerdown', 'pointerup', 'click']
      .forEach(function (type) {
        dlg.addEventListener(type, function (e) { e.stopPropagation(); });
      });
    dlg.addEventListener('pointerdown', function () {
      armed = true;
      // A tap can move focus off the search field before its click closes the sheet.
      focusAtDown = document.activeElement;
    }, true);
    dlg.addEventListener('click', onClick);
    // Back: closes an open sheet (sliding), or finishes one already sliding down, so it's not lost.
    dlg.addEventListener('cancel', function (e) {
      e.preventDefault();
      if (current) close(); else finishClose();
    });
    search.addEventListener('input', filter);
    list.addEventListener('scroll', function () { if (current) render(); }, { passive: true });
    window.addEventListener('resize', function () { if (current) render(); });
    head.addEventListener('touchstart', onDragStart, { passive: true });
    head.addEventListener('touchmove', onDragMove, { passive: true });
    head.addEventListener('touchend', function () { endDrag(true); });
    head.addEventListener('touchcancel', function () { endDrag(false); });
    root = dlg;
  }

  function cleanText(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  function titleFor(select) {
    var t = '';
    var labels = select.labels || [];
    for (var i = 0; i < labels.length && !t; i++) t = cleanText(labels[i].textContent);
    if (!t) t = cleanText(select.getAttribute('aria-label'));
    if (!t) t = cleanText(select.getAttribute('title'));
    for (var el = select.previousElementSibling; el && !t; el = el.previousElementSibling) {
      if (el.matches('h3, h4, h5, label, small')) t = cleanText(el.textContent);
    }
    return t;
  }

  // Hidden the ways ST hides options; no getComputedStyle (it is per option and very slow on ST).
  function isHidden(el) {
    return el.hidden || el.style.display === 'none' || el.classList.contains('displayNone');
  }

  /** Reads the shown options into [items] once. Plain property reads only: no style or layout. */
  function readItems(select) {
    var opts = select.options, out = [], lastParent = null;
    for (var i = 0; i < opts.length; i++) {
      var opt = opts[i], parent = opt.parentNode;
      var group = parent && parent.tagName === 'OPTGROUP' ? parent : null;
      if (isHidden(opt) || (group && isHidden(group))) continue;
      var label = opt.label;
      var item = {
        index: i, label: label, lower: label.toLowerCase(),
        disabled: opt.disabled || !!(group && group.disabled), selected: opt.selected,
        groupBreak: false, groupLabel: '',
      };
      if (parent !== lastParent) {
        var gl = group ? cleanText(group.label) : '';
        if (gl) item.groupLabel = gl;
        else if (out.length > 0) item.groupBreak = true;
        lastParent = parent;
      }
      out.push(item);
    }
    return out;
  }

  /** One element of each kind, measured once per open (font and padding follow ST's theme). */
  function measure() {
    var probes = { row: makeEl('row'), group: makeEl('group'), divider: makeEl('divider') };
    probes.row.textContent = 'Xg';
    probes.group.textContent = 'Xg';
    var h = {};
    for (var k in probes) spacer.appendChild(probes[k]);
    for (var k2 in probes) h[k2] = probes[k2].getBoundingClientRect().height || 1;
    for (var k3 in probes) spacer.removeChild(probes[k3]);
    return h;
  }

  /** Lays out what the list shows for search [q] (lower case; '' for all) and sizes the spacer. */
  function buildEntries(q) {
    entries = [];
    var top = 0;
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (q) {
        if (it.lower.indexOf(q) < 0) continue;
      } else if (it.groupLabel) {
        entries.push({ kind: 'group', item: it, top: top });
        top += heights.group;
      } else if (it.groupBreak) {
        entries.push({ kind: 'divider', item: it, top: top });
        top += heights.divider;
      }
      entries.push({ kind: 'row', item: it, top: top });
      top += heights.row;
    }
    spacer.style.height = top + 'px';
    clearRendered();
  }

  function makeEl(kind) {
    var el = document.createElement('div');
    el.className = 'stm-' + kind;
    if (kind === 'row') el.setAttribute('role', 'option');
    return el;
  }

  function clearRendered() {
    spacer.textContent = '';
    rendered = {};
  }

  function isPicked(it) { return picked ? !!picked[it.index] : it.selected; }

  function paintRow(el, it) {
    var on = isPicked(it);
    el.className = 'stm-row' + (on ? ' stm-selected' : '') + (it.disabled ? ' stm-disabled' : '');
    el.setAttribute('aria-selected', on ? 'true' : 'false');
    if (it.disabled) el.setAttribute('aria-disabled', 'true'); else el.removeAttribute('aria-disabled');
  }

  function fill(el, e) {
    el.style.top = e.top + 'px';
    // Exactly its slot: taller fallback glyphs (emoji, CJK) must not spill onto the next entry.
    el.style.height = heights[e.kind] + 'px';
    if (e.kind === 'divider') return;
    if (el.__stmItem !== e.item) {
      el.__stmItem = e.item;
      // A blank label still needs its line, or the row would be shorter than its slot.
      el.textContent = (e.kind === 'group' ? e.item.groupLabel : e.item.label) || '\u00a0';
      if (e.kind === 'row') el.setAttribute('data-index', String(e.item.index));
    }
    if (e.kind === 'row') paintRow(el, e.item);
  }

  /** First entry whose bottom is below [y]. */
  function firstBelow(y) {
    var lo = 0, hi = entries.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1, e = entries[mid];
      if (e.top + heights[e.kind] <= y) lo = mid + 1; else hi = mid;
    }
    return lo;
  }

  /** Makes the entries in and near view exist (reusing elements that scrolled away), and no others. */
  function render() {
    var top = list.scrollTop, bottom = top + list.clientHeight + BUFFER_PX;
    var first = firstBelow(top - BUFFER_PX), last = first;
    while (last < entries.length && entries[last].top < bottom) last++;
    var spare = { row: [], group: [], divider: [] };
    for (var key in rendered) {
      var n = +key;
      if (n < first || n >= last) {
        spare[entries[n].kind].push(rendered[key]);
        delete rendered[key];
      }
    }
    for (var i = first; i < last; i++) {
      if (rendered[i]) continue;
      var e = entries[i], el = spare[e.kind].pop();
      if (!el) {
        el = makeEl(e.kind);
        spacer.appendChild(el);
      }
      fill(el, e);
      rendered[i] = el;
    }
    for (var kind in spare) {
      for (var j = 0; j < spare[kind].length; j++) spacer.removeChild(spare[kind][j]);
    }
  }

  function filter() {
    var q = search.value.trim().toLowerCase();
    buildEntries(q);
    list.scrollTop = 0;
    empty.hidden = !q || entries.length > 0;
    render();
  }

  function open(select) {
    try {
      if (current) close();
      finishClose();
      if (!select || select.tagName !== 'SELECT' || select.disabled) return false;
      injectCss();
      ensureRoot();
      items = readItems(select);
      if (items.length === 0) return false;
      var title = titleFor(select);
      titleEl.textContent = title;
      titleEl.hidden = !title;
      search.value = '';
      search.hidden = items.length <= SEARCH_ABOVE;
      empty.hidden = true;
      foot.hidden = !select.multiple;
      panel.style.transform = '';
      if (document.body.lastElementChild !== root) document.body.appendChild(root);

      picked = null;
      if (select.multiple) {
        picked = [];
        for (var i = 0; i < select.options.length; i++) picked.push(select.options[i].selected);
      }
      current = select;
      armed = false;
      focusAtDown = null;
      openedAt = Date.now();
      // Like a native pick, focus ends up on the select (the dialog restores it on close).
      select.focus({ preventScroll: true });
      // Only after focus: a focus handler that touches the select must not close the sheet at once.
      observer = new MutationObserver(function () { close(); });
      observer.observe(select, { childList: true, subtree: true, attributes: true });
      root.classList.add('stm-open');
      root.showModal();
      // Where overflow:clip is unsupported, undo the scroll that focusing the grab area did.
      if (!CLIP_SUPPORTED) {
        root.scrollTop = 0;
        panel.scrollTop = 0;
      }
      clearRendered();
      heights = measure();
      buildEntries('');
      list.scrollTop = 0;
      for (var k = 0; k < entries.length; k++) {
        var e = entries[k];
        if (e.kind === 'row' && isPicked(e.item)) {
          list.scrollTop = e.top - (list.clientHeight - heights.row) / 2;
          break;
        }
      }
      render();
      // Last, once nothing can fail any more: a failed open tells the app nothing.
      announced = true;
      tellApp('open');
      return true;
    } catch (e) {
      abort();
      return false;
    }
  }

  /** A failed open leaves nothing open; the native list takes over. */
  function abort() {
    try { close(); finishClose(); } catch (e) { current = null; observer = null; }
  }

  function tellApp(message) {
    try { STMobileSheet.postMessage(message); } catch (e) { /* no bridge: nothing to tell */ }
  }

  function close() {
    if (announced) {
      announced = false;
      tellApp('closed');
    }
    if (observer) observer.disconnect();
    observer = null;
    current = null;
    picked = null;
    items = [];
    entries = [];
    dragStart = null;
    if (!root) return;
    root.classList.remove('stm-open');
    if (search) search.value = '';
    var keyboardUp = document.activeElement === search || focusAtDown === search;
    focusAtDown = null;
    if (!root.open) return;
    // No slide while the keyboard goes down (the search field had it up), or with reduced motion.
    if (keyboardUp || reducedMotion()) finishClose();
    else if (closeTimer === null) closeTimer = setTimeout(finishClose, CLOSE_MS);
  }

  /** Ends a close still sliding down: the dialog leaves the top layer now. */
  function finishClose() {
    if (closeTimer !== null) clearTimeout(closeTimer);
    closeTimer = null;
    if (root && root.open && !current) root.close();
  }

  function reducedMotion() {
    try {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (e) {
      return false;
    }
  }

  function fire(select) {
    select.dispatchEvent(new Event('input', { bubbles: true }));
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function onClick(e) {
    if (!current) return;
    if (!armed && Date.now() - openedAt < CLICK_GUARD_MS) return;
    var t = e.target;
    if (!t || !t.closest) return;
    if (t.closest('.stm-dim')) { close(); return; }
    if (t.closest('.stm-done')) { applyMultiple(); return; }
    var row = t.closest('.stm-row');
    if (!row || row.classList.contains('stm-disabled')) return;
    var index = Number(row.getAttribute('data-index'));
    var select = current;
    if (select.multiple) {
      picked[index] = !picked[index];
      if (row.__stmItem) paintRow(row, row.__stmItem);
      return;
    }
    close();
    if (select.selectedIndex === index) return;
    select.selectedIndex = index;
    fire(select);
  }

  function applyMultiple() {
    var select = current, want = picked, changed = false;
    close();
    for (var i = 0; i < select.options.length && i < want.length; i++) {
      if (select.options[i].selected !== want[i]) changed = true;
    }
    if (!changed) return;
    for (var j = 0; j < select.options.length && j < want.length; j++) select.options[j].selected = want[j];
    fire(select);
  }

  function onDragStart(e) {
    if (!current || e.touches.length !== 1) return;
    dragStart = e.touches[0].clientY;
    dragDy = 0;
    panel.style.transition = 'none';
  }

  function onDragMove(e) {
    if (dragStart === null) return;
    dragDy = Math.max(0, e.touches[0].clientY - dragStart);
    panel.style.transform = dragDy ? 'translateY(' + dragDy + 'px)' : '';
  }

  function endDrag(allowClose) {
    var dy = dragDy;
    var dragging = dragStart !== null;
    dragStart = null;
    dragDy = 0;
    if (!panel) return;
    panel.style.transition = '';
    panel.style.transform = '';
    if (dragging && allowClose && dy > SWIPE_CLOSE_PX) close();
  }

  document.addEventListener('mousedown', function (e) {
    var t = e.target;
    if (e.button !== 0 || !t || t.tagName !== 'SELECT') return;
    if (t.disabled || (!t.multiple && t.size > 1)) return;
    if (t.classList.contains('select2-hidden-accessible')) return;
    if (open(t)) e.preventDefault();
  }, true);

  document.addEventListener('keydown', function (e) {
    if (!current || (e.key !== 'Escape' && e.key !== 'Esc')) return;
    e.preventDefault();
    e.stopPropagation();
    close();
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { try { injectCss(); } catch (e) { /* sheet still works unstyled */ } });
  } else {
    try { injectCss(); } catch (e) { /* as above */ }
  }

  window.__stmSelectSheet = {
    open: open,
    close: function () { try { close(); } catch (e) { /* nothing open */ } },
    isOpen: function () { return current !== null; },
  };
})();
