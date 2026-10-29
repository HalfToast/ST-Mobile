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
      'margin:0;padding:0;border:0;background:transparent;overflow:hidden;color:' + TEXT + ';' +
      'font-family:' + FONT + ';font-size:var(--mainFontSize, 15px);text-align:left;text-shadow:none;}',
    '#' + ROOT_ID + ':not([open]){display:none;}',
    '#' + ROOT_ID + '::backdrop{background:transparent;}',
    S + '[hidden]{display:none !important;}',
    S + '.stm-dim{position:absolute;inset:0;background:rgba(0,0,0,0.55);}',
    S + '.stm-panel{position:absolute;left:0;right:0;bottom:0;box-sizing:border-box;display:flex;' +
      'flex-direction:column;max-height:75vh;max-height:75dvh;background:' + BG + ';' +
      'backdrop-filter:blur(var(--SmartThemeBlurStrength, 10px));' +
      '-webkit-backdrop-filter:blur(var(--SmartThemeBlurStrength, 10px));' +
      'border-top:1px solid ' + BORDER + ';border-radius:18px 18px 0 0;overflow:hidden;' +
      'padding:8px 0 max(8px, env(safe-area-inset-bottom));transition:transform .15s ease-out;}',
    S + '.stm-head{flex:none;touch-action:none;outline:none;}',
    S + '.stm-handle{width:38px;height:4px;border-radius:2px;background:' + TEXT + ';opacity:.25;margin:0 auto 10px;}',
    S + '.stm-title{font-weight:600;font-size:1.07em;padding:4px 16px 10px;overflow-wrap:anywhere;}',
    S + '.stm-search{flex:none;display:block;box-sizing:border-box;width:calc(100% - 28px);margin:0 14px 8px;' +
      'padding:8px 10px;border:1px solid ' + BORDER + ';border-radius:8px;background:rgba(255,255,255,0.03);' +
      'color:' + TEXT + ';font:inherit;font-size:.93em;outline:none;-webkit-appearance:none;appearance:none;}',
    S + '.stm-search::placeholder{color:' + MUTED + ';}',
    S + '.stm-list{position:relative;flex:0 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;}',
    S + '.stm-row{display:flex;justify-content:space-between;align-items:center;padding:11px 16px;' +
      'line-height:1.3;cursor:pointer;user-select:none;-webkit-user-select:none;' +
      '-webkit-tap-highlight-color:transparent;overflow-wrap:anywhere;}',
    S + '.stm-row:active:not(.stm-disabled){background:rgba(255,255,255,0.04);}',
    S + '.stm-row.stm-selected{color:' + ACCENT + ';background:' + TINT_FALLBACK + ';box-shadow:inset 3px 0 0 ' + ACCENT + ';}',
    S + '.stm-row.stm-selected::after{content:"\\2713";color:' + ACCENT + ';margin-left:12px;flex:none;}',
    S + '.stm-row.stm-disabled{opacity:.4;cursor:default;}',
    S + '.stm-divider{height:1px;background:' + BORDER + ';margin:4px 16px;}',
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
      '<div class="stm-list" role="listbox"></div>' +
      '<div class="stm-empty" hidden>No matches</div>' +
      '<div class="stm-foot"><button type="button" class="stm-done">Done</button></div>' +
    '</div>';

  var root = null, panel, head, titleEl, search, list, empty, foot;
  var current = null;   // the open <select>
  var observer = null;
  var picked = null;    // multiple: per-option selected state while open
  var armed = false, openedAt = 0;
  var announced = false; // the app was told "open" and not yet "closed"
  var dragStart = null, dragDy = 0;

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
    empty = dlg.querySelector('.stm-empty');
    foot = dlg.querySelector('.stm-foot');

    // Taps inside the sheet stay inside it: ST's page-level outside-click handlers
    // ($('html').on('touchstart mousedown')) would otherwise collapse the drawer behind the sheet.
    // The native dialog sends the page no events at all. Our own handlers sit on this root or below.
    ['touchstart', 'touchmove', 'touchend', 'mousedown', 'mouseup', 'pointerdown', 'pointerup', 'click']
      .forEach(function (type) {
        dlg.addEventListener(type, function (e) { e.stopPropagation(); });
      });
    dlg.addEventListener('pointerdown', function () { armed = true; }, true);
    dlg.addEventListener('click', onClick);
    dlg.addEventListener('cancel', function (e) { e.preventDefault(); close(); });
    search.addEventListener('input', filter);
    head.addEventListener('touchstart', onDragStart, { passive: true });
    head.addEventListener('touchmove', onDragMove, { passive: true });
    head.addEventListener('touchend', function () { endDrag(true); });
    head.addEventListener('touchcancel', function () { endDrag(false); });
    root = dlg;
  }

  function cleanText(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  function titleFor(select) {
    var t = '';
    if (select.id) {
      var labels = document.querySelectorAll('label[for]');
      for (var i = 0; i < labels.length && !t; i++) {
        if (labels[i].htmlFor === select.id) t = cleanText(labels[i].textContent);
      }
    }
    if (!t) t = cleanText(select.getAttribute('aria-label'));
    if (!t) t = cleanText(select.getAttribute('title'));
    for (var el = select.previousElementSibling; el && !t; el = el.previousElementSibling) {
      if (el.matches('h3, h4, h5, label, small')) t = cleanText(el.textContent);
    }
    return t;
  }

  function isHidden(opt) {
    if (opt.hidden) return true;
    var g = opt.parentNode;
    if (g && g.tagName === 'OPTGROUP' && g.hidden) return true;
    return getComputedStyle(opt).display === 'none';
  }

  function buildRows(select) {
    list.textContent = '';
    var opts = select.options, lastParent = null, rows = 0;
    for (var i = 0; i < opts.length; i++) {
      var opt = opts[i];
      if (isHidden(opt)) continue;
      var parent = opt.parentNode;
      if (parent !== lastParent) {
        var label = parent.tagName === 'OPTGROUP' ? cleanText(parent.label) : '';
        if (label) {
          var g = document.createElement('div');
          g.className = 'stm-group';
          g.textContent = label;
          list.appendChild(g);
        } else if (rows > 0) {
          var d = document.createElement('div');
          d.className = 'stm-divider';
          list.appendChild(d);
        }
        lastParent = parent;
      }
      var row = document.createElement('div');
      row.className = 'stm-row';
      row.setAttribute('role', 'option');
      row.setAttribute('data-index', String(opt.index));
      row.textContent = opt.label;
      if (opt.selected) row.classList.add('stm-selected');
      if (opt.matches(':disabled')) {
        row.classList.add('stm-disabled');
        row.setAttribute('aria-disabled', 'true');
      }
      row.setAttribute('aria-selected', opt.selected ? 'true' : 'false');
      list.appendChild(row);
      rows++;
    }
    return rows;
  }

  function filter() {
    var q = search.value.trim().toLowerCase();
    var kids = list.children, shown = 0;
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      if (el.classList.contains('stm-row')) {
        el.hidden = !!q && el.textContent.toLowerCase().indexOf(q) < 0;
        if (!el.hidden) shown++;
      } else {
        el.hidden = !!q;
      }
    }
    empty.hidden = !q || shown > 0;
  }

  function open(select) {
    try {
      if (current) close();
      if (!select || select.tagName !== 'SELECT' || select.disabled) return false;
      injectCss();
      ensureRoot();
      var rows = buildRows(select);
      if (rows === 0) return false;
      var title = titleFor(select);
      titleEl.textContent = title;
      titleEl.hidden = !title;
      search.value = '';
      search.hidden = rows <= SEARCH_ABOVE;
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
      observer = new MutationObserver(function () { close(); });
      observer.observe(select, { childList: true, subtree: true, attributes: true });
      armed = false;
      openedAt = Date.now();
      // Like a native pick, focus ends up on the select (the dialog restores it on close).
      select.focus({ preventScroll: true });
      root.classList.add('stm-open');
      root.showModal();
      announced = true;
      tellApp('open');
      var sel = list.querySelector('.stm-selected');
      if (sel) list.scrollTop = sel.offsetTop - (list.clientHeight - sel.offsetHeight) / 2;
      return true;
    } catch (e) {
      abort();
      return false;
    }
  }

  /** A failed open leaves nothing open; the native list takes over. */
  function abort() {
    try { close(); } catch (e) { current = null; observer = null; }
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
    dragStart = null;
    if (!root) return;
    root.classList.remove('stm-open');
    if (root.open) root.close();
    if (search) search.value = '';
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
      row.classList.toggle('stm-selected', picked[index]);
      row.setAttribute('aria-selected', picked[index] ? 'true' : 'false');
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
