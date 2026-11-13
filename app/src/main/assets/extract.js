// Injected at document start on the program's exact origins. Reads only the text of the configured
// balance element, whether a password or other entry field is visible, and the short labels of sign-in
// controls; never form values, cookies, or storage. Types the user's saved sign-in or a texted code only
// when the app sends a fill or code command for this page, and presses a "Sign in" control only when the app
// allows its own sign-in.
(function () {
  'use strict';
  var bridge = window.MileStoneBridge;
  if (!bridge || window.top !== window || window.__mileStoneArmed) return;
  window.__mileStoneArmed = true;

  var STABLE_MS = 1500;
  // Balance widgets often show 0 until the account loads (Aeroplan), so a zero must hold much longer to count.
  var ZERO_STABLE_MS = 8000;
  var LOGIN_SCAN_MS = 500;
  var FRAME_SCAN_MS = 1000;
  var REFUSAL_MS = 4000;
  // Pages built by a framework can replace their sign-in form after it first appears (Capital One); a form typed
  // into before that is discarded, so MileStone waits for the form to settle and pauses again before submitting.
  var LOGIN_SETTLE_MS = 1500;
  var SUBMIT_DELAY_MS = 500;
  // A submitted form can stay on screen while the site checks it (Amex leaves after about 5 s), so a form that never
  // leaves counts as refused only after this long.
  var SAME_PAGE_REFUSAL_MS = 15000;
  // A page still loading its own scripts can ignore a typed form and its button (Capital One while other programs
  // refresh), so the first fill waits for the page to finish loading, up to this long.
  var PAGE_READY_CAP_MS = 10000;
  var MAX_TEXT = 200;
  var PLACEHOLDER = /^(-+|n\/a|loading\W*|\.{3}|\u2026)$/i;
  var run = null;

  function post(message) {
    bridge.postMessage(JSON.stringify(message));
  }

  function isVisible(el) {
    if (!el.getClientRects().length) return false;
    var view = el.ownerDocument.defaultView || window;
    var style = view.getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }

  // Matches in the document, every open shadow root, and every same-origin frame, where component-based sites
  // and framed sign-in boxes (Chase) put their fields. Cross-origin frames are unreadable and skipped.
  function deepQuery(selector) {
    var found = [];
    (function visit(root) {
      found.push.apply(found, root.querySelectorAll(selector));
      var all = root.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        if (all[i].shadowRoot) visit(all[i].shadowRoot);
        if (all[i].tagName === 'IFRAME') {
          var frameDoc = null;
          try { frameDoc = all[i].contentDocument; } catch (e) { frameDoc = null; }
          if (frameDoc) visit(frameDoc);
        }
      }
    })(document);
    return found;
  }

  function anyVisible(fields) {
    for (var i = 0; i < fields.length; i++) if (!fields[i].disabled && isVisible(fields[i])) return true;
    return false;
  }

  var PASSWORD_FIELDS = '[type="password"]';
  // Includes custom input components whose inner field may sit in a closed shadow root.
  var ENTRY_FIELDS = 'input:not([type]), input[type="text"], input[type="tel"], input[type="number"], input[type="email"], ' +
    PASSWORD_FIELDS + ', [autocomplete="one-time-code"], mds-text-input, mds-password-input';

  // Password boxes, leaving out masked one-time-code boxes.
  function passwordFields() {
    return deepQuery(PASSWORD_FIELDS).filter(function (el) {
      return !el.disabled && isVisible(el) && !isCodeField(el);
    });
  }

  function hasVisiblePasswordField() {
    return passwordFields().length > 0;
  }

  // A visible field the user could be typing into, such as a one-time code.
  function hasVisibleEntryField() {
    return anyVisible(deepQuery(ENTRY_FIELDS));
  }

  var TEXT_INPUTS = 'input:not([type]), input[type="text"], input[type="tel"], input[type="number"], input[type="email"]';
  var CODE_HINT = /(otp|one.?time|passcode|verification|security.?code|auth.?code|^code$|[-_ ]code$|^code[-_ ])/i;
  var NOT_CODE = /(zip|postal|promo|country|area|card|account|user|email|phone)/i;
  // A label may say where the code went ("sent to your phone"), so only other kinds of code rule it out.
  var LABEL_CODE = /(otp|one.?time|passcode|verification|security code|\bcode\b)/i;
  var NOT_CODE_LABEL = /(zip|postal|promo|coupon|discount|country|area code|card|confirmation code|booking|record locator)/i;
  var REMEMBER = /(remember|trust|keep me signed|save (this )?(device|browser)|don.t (ask|require)\b.* again|recognize)/i;
  var SUBMIT = /^(sign in|sign on|log in|log on|login|continue|next|submit|verify|confirm|done)$/i;
  var REVEAL = /^(show|hide|reveal|view)( (the |your )?password)?$|^toggle .*password/i;
  var CHOOSE_TEXT = /\b(text( message)?|sms)\b/i;
  // A delivery option names the masked phone number or the code it sends.
  var MASKED_OR_CODE = /([*\u2022x]{2,}|\d{4}\b|\bcode\b)/i;
  var SEND_CODE = /^(continue|next|send( (the |my )?code)?|get (a )?code|text me( the code)?|send text)$/i;
  var SAVE_DEVICE = /^(yes,? )?(save|trust|remember)( this)? (device|browser|computer)$/i;
  // An account-type choice after sign-in (Delta); the personal account is the one MileStone reads.
  var PERSONAL = /^personal\b/i;
  var BUSINESS = /^business\b/i;
  // A page with no sign-in form whose sign-in opens from a control (Aeroplan's header); pressed once per page, and
  // only after OPEN_SIGN_IN_MS so a still-valid session can show its balance first.
  var OPEN_SIGN_IN = /^(sign in|log in|login|sign in ?(\/|or) ?join|log in ?(\/|or) ?join)$/i;
  var OPEN_SIGN_IN_MS = 3000;
  // A balance shown only in a panel (Marriott's account drawer) is revealed by pressing the catalog's control once.
  var REVEAL_MS = 2500;
  // A sign-in that offers an emailed link first and a password on request (Hyatt); pressed once per page.
  var PASSWORD_ROUTE = /^((sign|log) ?in (with|using) (a |your )?password|use (a |your )?password( instead)?|enter (a |your )?password( instead)?)$/i;
  var LAST_NAME = /(last.?name|surname|family.?name|lname)/i;
  // The first step of a sign-in that asks for the username alone and the password on a later page (Hyatt).
  var IDENTITY = /(user|e-?mail|login|member|account.?(id|number)|sign.?in.?id)/i;

  function visibleOnly(list) {
    return list.filter(function (el) { return !el.disabled && isVisible(el); });
  }

  function attr(el, name) {
    return (el.getAttribute && el.getAttribute(name)) || '';
  }

  // The short label of a control; long text is never a control label and is not read further.
  function label(el) {
    var text = attr(el, 'aria-label') || (el.labels && el.labels[0] && el.labels[0].textContent) ||
      (el.closest && el.closest('label') && el.closest('label').textContent) || el.textContent || attr(el, 'value');
    text = (text || '').replace(/\s+/g, ' ').trim();
    return text.length <= 60 ? text : '';
  }

  // The visible label tied to a field (United names its code box only there); short text only.
  function fieldLabel(el) {
    var parts = [];
    if (el.labels) for (var i = 0; i < el.labels.length; i++) parts.push(el.labels[i].textContent || '');
    attr(el, 'aria-labelledby').split(/\s+/).forEach(function (id) {
      var named = id && el.ownerDocument.getElementById(id);
      if (named) parts.push(named.textContent || '');
    });
    var text = parts.join(' ').replace(/\s+/g, ' ').trim();
    return text.length <= 120 ? text : '';
  }

  function isCodeField(el) {
    if (attr(el, 'autocomplete') === 'one-time-code') return true;
    var hint = [attr(el, 'name'), el.id, attr(el, 'aria-label'), attr(el, 'placeholder'), attr(el, 'data-testid')].join(' ');
    if (NOT_CODE.test(hint)) return false;
    if (CODE_HINT.test(hint)) return true;
    var text = fieldLabel(el);
    return LABEL_CODE.test(text) && !NOT_CODE_LABEL.test(text);
  }

  // A sign-in error the site shows on screen, by its accessible markers. A screen-reader-only announcer (a 1 px live
  // region, as Angular pages such as Capital One keep) is never on screen and is left out. Message text is only
  // compared inside the page, against what the same marker showed before MileStone typed; it is never sent. Only a
  // password box marked invalid counts without a message: another box marked invalid with nothing shown (Capital One's
  // username) is the site's own form check before anything was sent, not a judged password.
  function errorMarkers() {
    return visibleOnly(deepQuery('[role="alert"], [aria-live="assertive"], input[type="password"][aria-invalid="true"]')).filter(function (el) {
      if (attr(el, 'aria-invalid') === 'true') return true;
      var box = el.getBoundingClientRect();
      return box.width > 1 && box.height > 1 && (el.textContent || '').trim().length > 0;
    }).map(function (el) {
      return { el: el, text: attr(el, 'aria-invalid') === 'true' ? '' : (el.textContent || '').trim() };
    });
  }

  // A marker already showing the same thing before the sign-in was typed (a standing notice) is not about this
  // sign-in, so only a marker that appeared or changed since [before] counts. Without [before], every marker counts.
  function hasVisibleError(before) {
    return errorMarkers().some(function (now) {
      return !(before || []).some(function (old) { return old.el === now.el && old.text === now.text; });
    });
  }

  // A code typed one digit per box: four to eight single-character boxes in one form or container.
  function digitBoxes() {
    var boxes = visibleOnly(deepQuery(TEXT_INPUTS + ', [type="password"]')).filter(function (el) {
      return el.maxLength === 1 && !el.readOnly && !NOT_CODE.test([attr(el, 'name'), el.id, attr(el, 'aria-label')].join(' '));
    });
    var groups = [];
    boxes.forEach(function (el) {
      var home = el.form || (el.parentElement && el.parentElement.parentElement) || el.parentElement;
      var group = groups.filter(function (g) { return g.home === home; })[0];
      if (group) group.boxes.push(el); else groups.push({ home: home, boxes: [el] });
    });
    var found = groups.filter(function (g) { return g.boxes.length >= 4 && g.boxes.length <= 8; })[0];
    return found ? found.boxes : [];
  }

  function codeFields() {
    var single = visibleOnly(deepQuery('[autocomplete="one-time-code"], ' + TEXT_INPUTS + ', [type="password"]')).filter(isCodeField);
    return single.length ? single : digitBoxes();
  }

  // Sets a value the way typing does, so framework-managed fields notice it.
  function type(el, value) {
    var view = el.ownerDocument.defaultView || window;
    var setter = Object.getOwnPropertyDescriptor(view.HTMLInputElement.prototype, 'value').set;
    el.focus();
    setter.call(el, value);
    el.dispatchEvent(new view.Event('input', { bubbles: true, composed: true }));
    el.dispatchEvent(new view.Event('change', { bubbles: true, composed: true }));
    el.dispatchEvent(new view.FocusEvent('blur', { composed: true }));
    el.dispatchEvent(new view.FocusEvent('focusout', { bubbles: true, composed: true }));
  }

  function tickRememberBoxes() {
    deepQuery('input[type="checkbox"]').forEach(function (box) {
      if (box.checked || box.disabled || !REMEMBER.test(label(box))) return;
      box.click();
    });
  }

  function clickable(pattern) {
    var controls = visibleOnly(deepQuery('button, input[type="submit"], input[type="button"], [role="button"], a[href]'));
    for (var i = 0; i < controls.length; i++) if (pattern.test(label(controls[i]))) return controls[i];
    return null;
  }

  // A button without a type submits its form, so a show-password toggle can come first among the form's buttons.
  // Hyatt's "Sign in with password" is not a button or a link with an address, so any visible element labeled
  // exactly as the route counts. The innermost match is pressed, since a wrapper with the same text sits above
  // the element that listens for the click.
  function passwordRoute() {
    var matches = visibleOnly(deepQuery('button, a, [role="button"], [role="link"], [role="tab"], [tabindex], span, div, p, label'))
      .filter(function (el) { return PASSWORD_ROUTE.test(label(el)); });
    return matches.filter(function (el) {
      return !matches.some(function (other) { return other !== el && el.contains(other); });
    })[0] || null;
  }

  function submitFrom(field) {
    var form = field.form || (field.closest && field.closest('form'));
    var buttons = form ? visibleOnly(Array.prototype.slice.call(form.querySelectorAll('button[type="submit"], input[type="submit"], button:not([type])')))
      .filter(function (el) { return !REVEAL.test(label(el)); }) : [];
    var button = buttons.filter(function (el) { return SUBMIT.test(label(el)); })[0] || clickable(SUBMIT) || buttons[0];
    if (button) {
      button.click();
      return true;
    }
    if (form && form.requestSubmit) {
      form.requestSubmit();
      return true;
    }
    return false;
  }

  function isLastNameField(el) {
    if (attr(el, 'autocomplete') === 'family-name') return true;
    return LAST_NAME.test([attr(el, 'name'), el.id, attr(el, 'aria-label'), attr(el, 'placeholder'), label(el)].join(' '));
  }

  function identityFields() {
    return visibleOnly(deepQuery(TEXT_INPUTS)).filter(function (el) {
      var hint = [el.type, attr(el, 'name'), el.id, attr(el, 'autocomplete'), attr(el, 'aria-label'), attr(el, 'placeholder')].join(' ');
      return !el.readOnly && !isCodeField(el) && !isLastNameField(el) && IDENTITY.test(hint);
    });
  }

  // A username-only step: types the username (and a last name the same page asks for), then submits.
  function fillIdentity(username, lastName, done) {
    var field = identityFields()[0];
    if (!field || !username) return done(false);
    var lastNameField = visibleOnly(deepQuery(TEXT_INPUTS)).filter(function (el) {
      return el.ownerDocument === field.ownerDocument && !el.readOnly && isLastNameField(el);
    })[0];
    var fields = ['username'];
    type(field, username);
    if (lastNameField && lastName) { type(lastNameField, lastName); fields.push('lastName'); }
    tickRememberBoxes();
    setTimeout(function () { done(field.isConnected && submitFrom(field), fields.join(',')); }, SUBMIT_DELAY_MS);
  }

  // Types the sign-in, then submits after SUBMIT_DELAY_MS and reports through done(submitted).
  function fillSignIn(username, password, lastName, done) {
    var passwordField = passwordFields()[0];
    if (!passwordField) return fillIdentity(username, lastName, done);
    var texts = visibleOnly(deepQuery(TEXT_INPUTS)).filter(function (el) {
      return el.ownerDocument === passwordField.ownerDocument && !el.readOnly;
    });
    var lastNameField = texts.filter(isLastNameField)[0];
    var before = texts.filter(function (el) {
      return el !== lastNameField && (el.compareDocumentPosition(passwordField) & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    var usernameField = before[before.length - 1];
    var fields = [];
    if (usernameField && username) { type(usernameField, username); fields.push('username'); }
    if (lastNameField && lastName) { type(lastNameField, lastName); fields.push('lastName'); }
    type(passwordField, password);
    fields.push('password');
    tickRememberBoxes();
    setTimeout(function () { done(passwordField.isConnected && submitFrom(passwordField), fields.join(',')); }, SUBMIT_DELAY_MS);
  }

  function fillCode(code) {
    var fields = codeFields();
    if (!fields.length) return false;
    var boxes = fields[0].maxLength === 1 && fields.length > 1 ? fields : [fields[0]];
    if (boxes.length > 1) {
      if (code.length !== boxes.length) return false;
      boxes.forEach(function (box, i) { type(box, code.charAt(i)); });
    } else {
      type(boxes[0], code);
    }
    tickRememberBoxes();
    var last = boxes[boxes.length - 1];
    return last.isConnected ? submitFrom(last) : true;
  }

  // After MileStone's own sign-in: prefer a texted code over app approval, and accept "remember this device".
  function stepAfterSignIn(state) {
    var save = clickable(SAVE_DEVICE);
    if (save && !state.savedDevice) {
      state.savedDevice = true;
      tickRememberBoxes();
      save.click();
      return;
    }
    var personal = clickable(PERSONAL);
    if (personal && !state.choseAccount && clickable(BUSINESS)) {
      state.choseAccount = true;
      personal.click();
      return;
    }
    if (state.choseText || codeFields().length || hasVisiblePasswordField()) return;
    var options = visibleOnly(deepQuery('input[type="radio"], button, [role="radio"], [role="button"], label'));
    for (var i = 0; i < options.length; i++) {
      var text = label(options[i]);
      if (!CHOOSE_TEXT.test(text) || !MASKED_OR_CODE.test(text) || /e-?mail|call|voice|alert/i.test(text)) continue;
      state.choseText = true;
      options[i].click();
      var send = clickable(SEND_CODE);
      if (send && send !== options[i]) setTimeout(function () { send.click(); }, 300);
      return;
    }
  }

  function findMatches(config) {
    if (config.strategy === 'XPATH') {
      var snapshot = document.evaluate(config.expression, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
      var nodes = [];
      for (var i = 0; i < snapshot.snapshotLength; i++) nodes.push(snapshot.snapshotItem(i));
      return nodes;
    }
    return Array.prototype.slice.call(document.querySelectorAll(config.expression));
  }

  function start(config) {
    var done = false;
    var loginSent = false;
    var candidate = null;
    var stableTimer = null;
    var observer = new MutationObserver(check);

    var loginScanTimer = null;

    function finish(message) {
      if (done) return;
      done = true;
      observer.disconnect();
      clearTimeout(stableTimer);
      clearTimeout(timeoutTimer);
      clearTimeout(loginScanTimer);
      clearInterval(frameScanTimer);
      if (message) post(message);
    }

    var codeSent = false;
    var passwordSince = null;
    // Set once this page submitted the saved sign-in without leaving, as single-page sign-in forms do.
    var submittedHere = false;
    var submittedField = null;
    // The error markers on screen just before the saved sign-in was typed on this page.
    var markersBeforeFill = null;
    var pressedAgain = false;
    var afterState = { savedDevice: false, choseText: false, choseAccount: false };
    var startedAt = Date.now();
    var openedSignIn = false;
    var revealed = false;
    var choseRoute = false;
    var identitySince = null;
    var identitySent = false;

    // The deep scan walks every element, so it runs at most every LOGIN_SCAN_MS rather than on each mutation.
    function scheduleLoginScan() {
      if (loginScanTimer) return;
      loginScanTimer = setTimeout(function () {
        loginScanTimer = null;
        if (done) return;
        var passwordVisible = hasVisiblePasswordField();
        if (!passwordVisible) passwordSince = null;
        if (passwordVisible && !loginSent) {
          // After MileStone submitted a sign-in, a password box still showing seconds later means it was refused.
          passwordSince = passwordSince || Date.now();
          var wait = submittedHere ? SAME_PAGE_REFUSAL_MS : config.afterSignIn ? REFUSAL_MS : LOGIN_SETTLE_MS;
          if (Date.now() - passwordSince < wait) return;
          if (!submittedHere && !config.afterSignIn && document.readyState !== 'complete' && Date.now() - passwordSince < PAGE_READY_CAP_MS) return;
          // A refusal shows an error; the same form with none may never have reached the site, so its button is pressed
          // once more before the sign-in counts as refused. A form the site emptied submits nothing on that press.
          if (submittedHere && !pressedAgain && submittedField && submittedField.isConnected && !hasVisibleError(markersBeforeFill)) {
            pressedAgain = true;
            passwordSince = null;
            submitFrom(submittedField);
            armTimeout();
            post({ type: 'pressedAgain', nonce: config.nonce });
            return;
          }
          loginSent = true;
          // Only a form showing the site's error counts as a refused password; one without could be the site
          // sending the sign-in back for another reason, which a later attempt may get past.
          var refused = submittedHere ? hasVisibleError(markersBeforeFill) : config.afterSignIn && hasVisibleError();
          post({ type: refused ? 'refused' : 'login', nonce: config.nonce });
          return;
        }
        if (config.watchCodes && !codeSent && !passwordVisible && codeFields().length) {
          codeSent = true;
          post({ type: 'code', nonce: config.nonce });
        }
        var route = config.openSignIn && !choseRoute && !passwordVisible ? passwordRoute() : null;
        if (route) {
          choseRoute = true;
          route.click();
        } else if (config.openSignIn && !passwordVisible && !codeFields().length && identityFields().length) {
          // A username-only step: asked for once per page, and never answered by pressing its "Sign in" button empty.
          identitySince = identitySince || Date.now();
          if (!identitySent && Date.now() - identitySince >= LOGIN_SETTLE_MS) {
            identitySent = true;
            post({ type: 'login', nonce: config.nonce });
          }
        } else if (config.openSignIn && !openedSignIn && !passwordVisible && Date.now() - startedAt >= OPEN_SIGN_IN_MS && !codeFields().length) {
          var opener = clickable(OPEN_SIGN_IN);
          if (opener) {
            openedSignIn = true;
            opener.click();
          }
        }
        if (config.afterSignIn) stepAfterSignIn(afterState);
        if (config.reveal && !revealed && !passwordVisible && Date.now() - startedAt >= REVEAL_MS && !read()) {
          var revealer = visibleOnly(deepQuery(config.reveal))[0];
          if (revealer) {
            revealed = true;
            revealer.click();
          }
        }
      }, LOGIN_SCAN_MS);
    }

    function reportFilled(submitted, fields) {
      if (done) return;
      var message = { type: 'filled', submitted: submitted, nonce: config.nonce };
      if (fields) message.fields = fields;
      post(message);
    }

    function command(message) {
      if (message.command === 'fill' && typeof message.password === 'string') {
        markersBeforeFill = errorMarkers();
        fillSignIn(typeof message.username === 'string' ? message.username : '', message.password,
          typeof message.lastName === 'string' ? message.lastName : '', function (submitted, fields) {
          if (submitted) {
            // A form that stays on screen after the submit was refused or discarded; report it like a new sign-in page.
            // A username-only step is not a refusal, so its password page is filled without the refusal wait.
            if ((fields || '').split(',').indexOf('password') >= 0) {
              submittedHere = true;
              submittedField = passwordFields()[0] || null;
            }
            loginSent = false;
            passwordSince = null;
          }
          reportFilled(submitted, fields);
        });
      } else if (message.command === 'code' && typeof message.code === 'string') {
        reportFilled(fillCode(message.code));
      }
    }

    function read() {
      var matches = findMatches(config);
      if (!matches.length) return null;
      var raw = (matches[0].textContent || '').trim().slice(0, MAX_TEXT);
      // Every balance has a digit; text without one is the page still loading (United), so keep waiting.
      if (!raw || PLACEHOLDER.test(raw) || !/\d/.test(raw)) return null;
      return { raw: raw, matchCount: matches.length };
    }

    function check() {
      if (done) return;
      scheduleLoginScan();
      var current = read();
      if (current && candidate && current.raw === candidate.raw && current.matchCount === candidate.matchCount) return;
      candidate = current;
      clearTimeout(stableTimer);
      if (!current) return;
      stableTimer = setTimeout(function () {
        var settled = read();
        if (settled && settled.raw === candidate.raw && settled.matchCount === candidate.matchCount) {
          finish({ type: 'balance', raw: settled.raw, matchCount: settled.matchCount, nonce: config.nonce });
        }
      }, /^0+$/.test(current.raw.replace(/\D/g, '')) ? ZERO_STABLE_MS : STABLE_MS);
    }

    var timeoutTimer = null;
    // Restarted when the script presses a sign-in button again, so the second press gets a whole page's time.
    function armTimeout() {
      clearTimeout(timeoutTimer);
      timeoutTimer = setTimeout(function () {
        // A late fill can leave less time than the refusal settle; keep an already visible new error.
        if (submittedHere && hasVisiblePasswordField() && hasVisibleError(markersBeforeFill)) {
          finish({ type: 'refused', nonce: config.nonce });
          return;
        }
        finish({ type: 'timeout', awaitingInput: hasVisibleEntryField(), nonce: config.nonce });
      }, config.timeoutMs);
    }
    armTimeout();
    // Changes inside a frame do not reach this document's observer, so framed sign-in boxes are polled.
    var frameScanTimer = setInterval(scheduleLoginScan, FRAME_SCAN_MS);
    observer.observe(document, { childList: true, subtree: true, characterData: true, attributes: true });
    check();
    return {
      nonce: config.nonce,
      stop: function () { finish(null); },
      command: function (message) { if (!done) command(message); },
    };
  }

  // ---- Benefits read mode (ADR-0008) ----------------------------------------------------------------------
  // Reads the card switcher's names and last digits, tracker containers, and benefits-list entries, each from a
  // named element given in the config. Presses only existing controls (the switcher, a card option, the page's own
  // benefits-activity or view-all link). Never reads input values, cookies, storage, or link query strings, and strips
  // identifier-like text before posting.
  var BENEFITS_STABLE_MS = 2000;
  var BENEFITS_SCAN_MS = 400;
  var MAX_CARDS = 20;
  var MAX_TRACKERS = 60;
  var MAX_LISTED = 150;
  var DIGIT_RUN = /\d+(?:[ .\- ]+\d+)*/g;
  var LONG_TOKEN = /[^\s"'<>]{8,}/g;
  var KEY_RUN = /(^|[^A-Za-z0-9])([A-Z0-9]{10,})(?=$|[^A-Za-z0-9])/g;
  // A dollar amount such as "$1,234.56" or "$150,000" is never an identifier, so the long-token rule skips it.
  var MONEY = /^\(?[-+]?\$\d{1,3}(?:,\d{3})*(?:\.\d{2})?[).,;:]?$/;
  var EMPTY_STABLE_MS = 6000;
  // ADR-0008, 2026-11-12 clarification: caps per page kind and for a card switch.
  var KIND_CAP_MS = { rewards: 15000, activity: 20000, viewAll: 20000 };
  var SWITCH_CAP_MS = 12000;
  var HANDOFF_MAX_AGE_MS = 30000;
  // Diagnostics only: the time on this document is measured from when this script started on it.
  var DOCUMENT_STARTED_AT = Date.now();
  var MAX_WAITED_MS = 120000;
  // What the last run on this document pressed, so the run that starts after the address change knows.
  // Lives only in this document's script; a new document starts without it.
  var handoff = null;

  function stripIdentifiers(text) {
    return text
      .replace(DIGIT_RUN, function (run) { return run.replace(/\D/g, '').length >= 6 ? '#' : run; })
      .replace(LONG_TOKEN, function (token) { return !MONEY.test(token) && token.replace(/\D/g, '').length >= 4 ? '#' : token; })
      .replace(KEY_RUN, function (all, before, run) { return /\d/.test(run) ? before + '#' : all; });
  }

  function clean(el, max) {
    if (!el) return null;
    var text = stripIdentifiers((el.textContent || '').replace(/\s+/g, ' ').trim());
    return text ? text.slice(0, max) : null;
  }

  function lastDigitsOf(el) {
    var text = el ? (el.textContent || '').replace(/\D/g, '') : '';
    return /^\d{4,5}$/.test(text) ? text : null;
  }

  function startBenefits(config) {
    var s = config.selectors || {};
    var done = false;
    var startedAt = Date.now();
    var cardsSent = !config.listCards;
    var switchStartedAt = null;
    var fingerprintBefore = null;
    var viaSwitch = false;
    var listedBefore = null;
    var followedActivity = false;
    var signature = null;
    var stableSince = 0;
    var scrolledTo = 0;
    var lastCards = null;

    function finish(message) {
      if (done) return;
      done = true;
      clearInterval(timer);
      if (message) post(message);
    }

    function selectedDigits() {
      var display = document.querySelector(s.selected);
      return lastDigitsOf(display && display.querySelector(s.optionDigits));
    }

    function options() {
      return Array.prototype.slice.call(document.querySelectorAll(s.option)).filter(function (el) {
        return el.querySelector(s.optionDigits);
      });
    }

    function readCards() {
      var list = options();
      var cards = [];
      var complete = list.length > 0 && list.length <= MAX_CARDS;
      list.slice(0, MAX_CARDS).forEach(function (el) {
        var digits = lastDigitsOf(el.querySelector(s.optionDigits));
        var name = clean(el.querySelector(s.optionName), 80);
        if (!digits || !name) { complete = false; return; }
        cards.push({ name: name, lastDigits: digits });
      });
      return { cards: cards, complete: complete };
    }

    function openSwitcher() {
      var box = document.querySelector(s.combobox);
      if (box && !options().length) box.click();
    }

    function closeSwitcher() {
      var box = document.querySelector(s.combobox);
      if (box && options().length) box.click();
    }

    function groupOf(el) {
      var heading = null;
      var all = document.querySelectorAll('h2');
      for (var i = 0; i < all.length; i++) {
        if (all[i].compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) heading = all[i];
      }
      return clean(heading, 60);
    }

    function readTracker(el) {
      var spentSection = el.querySelector(s.trackerSpentSection);
      var period = null;
      var label = null;
      if (spentSection) {
        var spans = spentSection.querySelectorAll('span');
        for (var i = 0; i < spans.length; i++) {
          if (spans[i].matches(s.trackerSpent)) continue;
          var text = clean(spans[i], 40);
          if (!text) continue;
          if (/^[A-Za-z]{3,9}(\s*[-–]\s*[A-Za-z]{3,9})?$/.test(text) && /^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)/i.test(text)) period = text;
          else if (!label && /^[A-Za-z ]{2,20}$/.test(text)) label = text;
        }
      }
      var progress = el.querySelector('progress');
      var max = progress ? parseFloat(progress.getAttribute('max')) : NaN;
      return {
        group: groupOf(el),
        title: clean(el.querySelector(s.trackerHeading), 120),
        spent: clean(el.querySelector(s.trackerSpent), 40),
        remaining: clean(el.querySelector(s.trackerRemaining), 60),
        target: isFinite(max) && max > 0 && max < 1e7 ? max : null,
        period: period,
        label: label,
        done: !!el.querySelector(s.trackerDone),
        description: clean(el.querySelector(s.trackerDescription), 300),
      };
    }

    function readListed() {
      var seen = {};
      var entries = [];
      Array.prototype.slice.call(document.querySelectorAll(s.listLink)).forEach(function (link) {
        var heading = link.querySelector('h4');
        if (!heading || entries.length >= MAX_LISTED) return;
        var parts = (link.pathname || '').split('/');
        var at = parts.indexOf('detail');
        var slug = at >= 0 && /^[A-Za-z0-9+-]{1,80}$/.test(parts[at + 1] || '') ? parts[at + 1] : null;
        if (!slug || seen[slug]) return;
        seen[slug] = true;
        // The entry is the largest ancestor holding exactly one "Learn more" link: the ribbon sits in its picture branch.
        var root = link;
        while (root.parentElement && root.parentElement.querySelectorAll(s.listMore).length <= 1) root = root.parentElement;
        if (!root.querySelector(s.listMore)) return;
        var ribbon = null;
        var marks = root.querySelectorAll('div, span');
        for (var i = 0; i < marks.length && !ribbon; i++) {
          if (marks[i].children.length) continue;
          var mark = (marks[i].textContent || '').trim();
          if (mark === 'Enrolled' || mark === 'Enrollment Required') ribbon = mark;
        }
        entries.push({
          key: stripIdentifiers(slug),
          title: clean(heading, 120),
          description: clean(root.querySelector(s.listDescription), 300),
          enrollment: ribbon,
        });
      });
      return entries;
    }

    function count(selector) {
      return selector ? document.querySelectorAll(selector).length : 0;
    }

    // The page kind by path prefix (ADR-0008, 2026-11-12 clarification); null for any other accepted path.
    function pageKind() {
      var path = location.pathname;
      if (s.activityPath && path.indexOf(s.activityPath) === 0) return 'activity';
      if (s.viewAllPath && path.indexOf(s.viewAllPath) === 0) return 'viewAll';
      if (s.rewardsPath && path.indexOf(s.rewardsPath) === 0) return 'rewards';
      return null;
    }

    // Titles and amounts of the trackers on screen; compared only, never posted.
    function fingerprint() {
      return Array.prototype.map.call(document.querySelectorAll(s.tracker), function (el) {
        return [s.trackerHeading, s.trackerSpent, s.trackerRemaining].map(function (part) {
          var found = el.querySelector(part);
          return found ? (found.textContent || '').trim() : '';
        }).join('/');
      }).join('|');
    }

    function atBottom() {
      return (window.pageYOffset || 0) + (window.innerHeight || 0) >= document.body.scrollHeight - 2;
    }

    // Rewards content shows a view-all or activity link; an activity page never shows an activity link.
    function markerHolds() {
      var activity = !!document.querySelector(s.activityLink);
      var viewAll = !!(s.viewAllLink && document.querySelector(s.viewAllLink));
      var listed = count(s.listLink);
      if (kind === 'rewards') return activity || viewAll;
      if (kind === 'activity') return !activity && (count(s.tracker) > 0 || listed > 0);
      return listed > 0 && (listedBefore === null || listed !== listedBefore);
    }

    // Count-only facts about this page for the app's diagnostics log: fixed words, booleans, and capped counts.
    function ready(reason) {
      return {
        kind: kind,
        trackers: Math.min(count(s.tracker), MAX_TRACKERS),
        listed: Math.min(count(s.listLink), MAX_LISTED),
        waitedMs: Math.min(Math.max(Date.now() - DOCUMENT_STARTED_AT, 0), MAX_WAITED_MS),
        bottom: atBottom(),
        activityLink: !!document.querySelector(s.activityLink),
        viewAllLink: !!(s.viewAllLink && document.querySelector(s.viewAllLink)),
        switched: viaSwitch || switchStartedAt !== null,
        reason: reason,
      };
    }

    // [next] is 'viewAll' when this Rewards page is about to open the card's view-all page.
    function postBenefits(current, reason, next) {
      var trackers = Array.prototype.slice.call(document.querySelectorAll(s.tracker));
      finish({
        type: 'benefits',
        lastDigits: current,
        page: kind,
        trackers: trackers.slice(0, MAX_TRACKERS).map(readTracker),
        listed: kind === 'rewards' ? [] : readListed(),
        ready: ready(reason),
        next: next,
        nonce: config.nonce,
      });
    }

    function unreadable(why, reason) {
      finish({
        type: 'benefitsUnreadable',
        why: why,
        trackers: count(s.tracker),
        switcher: document.querySelector(s.selected) ? 1 : 0,
        ready: kind ? ready(reason) : undefined,
        nonce: config.nonce,
      });
    }

    // Takes over a note left by the run that pressed a link or a switcher option on this same document.
    function adopt(note) {
      if (!note || Date.now() - note.at > HANDOFF_MAX_AGE_MS || note.target !== (config.targetDigits || null)) return;
      viaSwitch = viaSwitch || note.switched;
      if (note.action === 'switch') {
        switchStartedAt = note.at;
        fingerprintBefore = note.fingerprint;
      }
      if (note.action === 'link') listedBefore = note.listed;
    }

    function leaveNote(action) {
      handoff = {
        action: action,
        target: config.targetDigits || null,
        fingerprint: fingerprint(),
        listed: count(s.listLink),
        switched: viaSwitch || action === 'switch',
        at: Date.now(),
      };
    }

    function tick() {
      if (done) return;
      if (hasVisiblePasswordField()) return finish({ type: 'login', nonce: config.nonce });
      var nowKind = pageKind();
      if (!nowKind) { kind = null; return unreadable('page', null); }
      if (nowKind !== kind) {
        // The address changed without a restart: treat it as a new page that this run's own note led to.
        kind = nowKind;
        startedAt = Date.now();
        cap = Math.min(KIND_CAP_MS[kind] || config.timeoutMs, config.timeoutMs);
        signature = null;
        var note = handoff;
        handoff = null;
        adopt(note);
      }
      // Sections load as the page scrolls (Amex marks them scroll-aware), so walk down the page while waiting.
      if (scrolledTo < document.body.scrollHeight) {
        scrolledTo += window.innerHeight || 800;
        window.scrollTo(0, scrolledTo);
      }
      var shape = count(s.tracker) + ':' + count(s.listLink) + ':' + document.body.scrollHeight;
      if (shape !== signature) { signature = shape; stableSince = Date.now(); }
      var steadyMs = Date.now() - stableSince;
      var current = selectedDigits();
      if (!current) return;
      if (!cardsSent) {
        if (!options().length) return openSwitcher();
        // A list still rendering would read short, so it counts only after two identical reads in a row that also
        // include the card the page shows.
        var read = readCards();
        var key = JSON.stringify(read.cards);
        if (key !== lastCards) { lastCards = key; return; }
        cardsSent = true;
        closeSwitcher();
        var includesCurrent = read.cards.some(function (card) { return card.lastDigits === current; });
        post({ type: 'cards', cards: read.cards, complete: read.complete && includesCurrent, nonce: config.nonce });
        return;
      }
      var target = config.targetDigits;
      if (target && current !== target && switchStartedAt === null) {
        if (!options().length) return openSwitcher();
        var option = options().filter(function (el) { return lastDigitsOf(el.querySelector(s.optionDigits)) === target; })[0];
        if (!option) return unreadable('noSuchCard', 'switchMismatch');
        leaveNote('switch');
        switchStartedAt = handoff.at;
        fingerprintBefore = handoff.fingerprint;
        option.click();
        return;
      }
      // Switch invariant: the selected card is the target and its trackers replaced the old card's. An emptied
      // tracker area is not ready; after the switch cap an empty steady area is an empty read, the old trackers a mismatch.
      if (switchStartedAt !== null) {
        var overSwitchCap = Date.now() - switchStartedAt > SWITCH_CAP_MS;
        if (current !== target) return overSwitchCap ? unreadable('switch', 'timeout') : undefined;
        var print = fingerprint();
        if (!print || print === fingerprintBefore) {
          if (!overSwitchCap) return;
          if (print) return unreadable('switch', 'switchMismatch');
          if (markerHolds() && atBottom() && steadyMs >= EMPTY_STABLE_MS) return postBenefits(current, 'cap');
          return;
        }
        switchStartedAt = null;
        viaSwitch = true;
        if (handoff && handoff.action === 'switch') handoff = null;
      }
      var overPageCap = Date.now() - startedAt > cap;
      var markers = markerHolds();
      if (kind === 'rewards') {
        var trackerCount = count(s.tracker);
        var settled = steadyMs >= (trackerCount ? BENEFITS_STABLE_MS : EMPTY_STABLE_MS) &&
          (trackerCount > 0 || document.readyState === 'complete');
        var isReady = markers && atBottom() && settled;
        if (!isReady && !overPageCap) return;
        var activity = document.querySelector(s.activityLink);
        if (activity && config.secondPage !== false && !followedActivity) {
          followedActivity = true;
          leaveNote('link');
          activity.click();
          return;
        }
        // A card without an activity page lists its benefits on its view-all page (ADR-0008, 2026-11-12 amendment):
        // post the trackers first, then open that page through the page's own link.
        var viewAll = s.viewAllLink && document.querySelector(s.viewAllLink);
        if (isReady && !activity && viewAll && config.secondPage !== false && !followedActivity) {
          leaveNote('link');
          postBenefits(current, trackerCount ? 'stable' : 'empty', 'viewAll');
          viewAll.click();
          return;
        }
        // A late lower section can reveal the activity link after the readiness cap. Wait within the app's
        // page budget rather than replace complete activity trackers with this page's partial set.
        if (followedActivity || (!markers && Date.now() - startedAt < Math.min(20000, config.timeoutMs))) return;
        return postBenefits(current, isReady ? (trackerCount ? 'stable' : 'empty') : 'cap');
      }
      if (markers && atBottom() && steadyMs >= BENEFITS_STABLE_MS) return postBenefits(current, 'stable');
      // At the cap only a page whose marker holds is read, so Rewards content is never stored as this page.
      if (overPageCap) return markers ? postBenefits(current, 'cap') : unreadable('timeout', 'timeout');
    }

    var kind = pageKind();
    var cap = Math.min(KIND_CAP_MS[kind] || config.timeoutMs, config.timeoutMs);
    var noteForThisRun = handoff;
    handoff = null;
    adopt(noteForThisRun);

    var timer = setInterval(function () {
      tick();
      // Rewards may still be waiting for its scroll-loaded links after its readiness cap.
      var timeout = kind === 'rewards' ? Math.min(20000, config.timeoutMs) : cap;
      if (!done && Date.now() - startedAt > timeout + 2 * BENEFITS_SCAN_MS) unreadable('timeout', 'timeout');
    }, BENEFITS_SCAN_MS);
    tick();
    return {
      nonce: config.nonce,
      stop: function () { finish(null); },
      command: function () {},
    };
  }

  bridge.onmessage = function (event) {
    var config;
    try {
      config = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    if (config && typeof config.command === 'string') {
      if (run && config.nonce === run.nonce) run.command(config);
      return;
    }
    if (config && config.mode === 'benefits' && typeof config.nonce === 'string' && config.selectors) {
      if (run) run.stop();
      run = startBenefits(config);
      return;
    }
    if (!config || typeof config.expression !== 'string' || typeof config.nonce !== 'string') return;
    if (run) run.stop();
    run = start(config);
  };

  function announce() {
    if (run) run.stop();
    run = null;
    post({ type: 'ready' });
  }

  var pushState = history.pushState;
  history.pushState = function () {
    var result = pushState.apply(this, arguments);
    announce();
    return result;
  };
  window.addEventListener('popstate', announce);
  announce();
})();
