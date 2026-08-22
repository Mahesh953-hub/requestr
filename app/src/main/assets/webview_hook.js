(function () {
  // W3 MAIN-world capture hook, injected via WebViewCompat.addDocumentStartJavaScript.
  // Patches window.fetch and XMLHttpRequest so requestr sees request/response bodies and
  // same-document XHR that WebViewClient.shouldInterceptRequest cannot reconstruct on its own.
  // Payload shape is kept diff-able against the browser-extension MV3 SDK's /api/observe body
  // ({"url","method","headers","statusCode","bodySize","durationMs","timestamp"}), plus the
  // fields this bridge can uniquely provide (requestBody/responseBody/initiator) -- posted to
  // the in-process WebMessageListener bridge named "requestrBridge", not over HTTP.
  if (window.__requestrBridgeInstalled) return;
  window.__requestrBridgeInstalled = true;

  function post(payload) {
    try {
      if (window.requestrBridge && typeof window.requestrBridge.postMessage === 'function') {
        window.requestrBridge.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* never let capture break the page */ }
  }

  function initiator() {
    try {
      throw new Error('requestr-initiator');
    } catch (e) {
      return (e && e.stack) ? e.stack.split('\n').slice(2, 4).join(' | ') : null;
    }
  }

  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function (input, init) {
      var startedAt = Date.now();
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var reqHeaders = {};
      try {
        var h = (init && init.headers) || (input && input.headers);
        if (h && typeof h.forEach === 'function') {
          h.forEach(function (v, k) { reqHeaders[k] = v; });
        } else if (h && typeof h === 'object') {
          for (var k in h) reqHeaders[k] = h[k];
        }
      } catch (e) { /* ignore */ }
      var reqBody = (init && typeof init.body === 'string') ? init.body : null;

      return originalFetch.apply(this, arguments).then(function (response) {
        try {
          var cloned = response.clone();
          cloned.text().then(function (text) {
            post({
              url: url,
              method: method,
              requestHeaders: reqHeaders,
              status: response.status,
              requestBody: reqBody,
              responseBody: text,
              durationMs: Date.now() - startedAt,
              initiator: initiator(),
              via: 'fetch',
            });
          }).catch(function () {
            post({
              url: url, method: method, requestHeaders: reqHeaders, status: response.status,
              requestBody: reqBody, responseBody: null, durationMs: Date.now() - startedAt,
              initiator: initiator(), via: 'fetch',
            });
          });
        } catch (e) { /* ignore */ }
        return response;
      }).catch(function (err) {
        post({
          url: url, method: method, requestHeaders: reqHeaders, status: null,
          requestBody: reqBody, responseBody: null, durationMs: Date.now() - startedAt,
          initiator: initiator(), via: 'fetch', error: String(err),
        });
        throw err;
      });
    };
  }

  var OriginalXhr = window.XMLHttpRequest;
  if (OriginalXhr) {
    var origOpen = OriginalXhr.prototype.open;
    var origSend = OriginalXhr.prototype.send;
    var origSetHeader = OriginalXhr.prototype.setRequestHeader;

    OriginalXhr.prototype.open = function (method, url) {
      this.__requestrMethod = method;
      this.__requestrUrl = url;
      this.__requestrHeaders = {};
      this.__requestrStart = Date.now();
      return origOpen.apply(this, arguments);
    };

    OriginalXhr.prototype.setRequestHeader = function (name, value) {
      if (this.__requestrHeaders) this.__requestrHeaders[name] = value;
      return origSetHeader.apply(this, arguments);
    };

    OriginalXhr.prototype.send = function (body) {
      var xhr = this;
      xhr.addEventListener('loadend', function () {
        post({
          url: xhr.__requestrUrl,
          method: xhr.__requestrMethod,
          requestHeaders: xhr.__requestrHeaders,
          status: xhr.status,
          requestBody: (typeof body === 'string') ? body : null,
          responseBody: (function () {
            try { return xhr.responseText; } catch (e) { return null; }
          })(),
          durationMs: Date.now() - xhr.__requestrStart,
          initiator: initiator(),
          via: 'xhr',
        });
      });
      return origSend.apply(this, arguments);
    };
  }
})();
