package com.example.runtime.frida

/**
 * The actual Frida JS payload for the reflection-only OkHttp `Builder.build()` hook.
 *
 * Design notes (see also [FridaOkHttpAdapter]'s kdoc for the Kotlin-side half):
 *  - **Class/classloader detection**: rather than hardcoding `okhttp3.OkHttpClient${'$'}Builder`
 *    and hoping the target app didn't shade/relocate OkHttp, [SOURCE] enumerates every loaded
 *    class (`Java.enumerateLoadedClassesSync()`) and every loaded classloader
 *    (`Java.enumerateClassLoadersSync()`), structurally testing each candidate class -- "does it
 *    declare a zero-arg `build()` whose return type itself declares `newCall(1 arg)` and
 *    `interceptors()`" -- instead of trusting the (possibly obfuscated) class name. The canonical
 *    name is still tried directly as a fast path/fallback.
 *  - **Reflection-only interceptor install**: once a compatible Builder is found, the hook does
 *    NOT call the public `addInterceptor(Interceptor)` method (which, like any method name, can
 *    be renamed by R8/ProGuard obfuscation of the *app's* bundled copy of the library). Instead
 *    it walks the Builder instance's own declared fields (via `java.lang.reflect.Field`, made
 *    accessible) looking for a `java.util.List` whose current elements structurally look like
 *    `okhttp3.Interceptor`s (a zero-... one-arg `intercept` method), falling back to a field
 *    literally named `interceptors`, then the first declared `List` field -- and appends our
 *    interceptor to THAT list directly. Every other detail of the hook (finding the mutable
 *    list, reading it, writing back to it) is java.lang.reflect end to end.
 *  - **Post-reassembly bodies**: the installed interceptor is an *application* interceptor
 *    (added to `interceptors`, not `networkInterceptors`), so by the time it runs OkHttp has
 *    already undone chunked transfer-encoding and content-encoding (gzip/etc) -- `chain.proceed`
 *    hands it a [okhttp3.Response] whose body, read via the public, non-destructive
 *    `peekBody(long)`, is the fully reassembled plaintext, not a network-encoded fragment. The
 *    same logic applies to the request body via a scratch `okio.Buffer` + `RequestBody.writeTo`
 *    (the same technique OkHttp's own `HttpLoggingInterceptor` uses).
 *  - **No TLS bypass here**: this hook does nothing to certificate validation or trust managers
 *    -- it only observes already-decrypted, already-reassembled application-layer traffic AFTER
 *    OkHttp itself terminated TLS, which is exactly the "after-TLS OkHttp interception" contract
 *    this feature is built to (as opposed to a `TrustAllCerts`-style bypass pretending to be a
 *    capture mechanism).
 */
internal object OkHttpBuilderHookScript {

    val SOURCE: String = buildScript()

    private fun buildScript(): String {
        val d = "${'$'}" // literal '$' for Java nested-class names inside the JS source below
        return """
        (function () {
          var MAX_BODY_BYTES = 262144; // 256 KiB cap per direction, matches other capture paths' sane defaults

          function send_(type, obj) {
            try {
              obj.type = type;
              obj.hookVersion = 'okhttp-builder-build-v1';
              send(obj);
            } catch (e) {}
          }

          function safeToString(x) {
            if (x === null || x === undefined) return null;
            try { return x.toString(); } catch (e) { return null; }
          }

          function headersToObject(headers) {
            var result = {};
            try {
              var size = headers.size();
              for (var i = 0; i < size; i++) {
                result[headers.name(i)] = headers.value(i);
              }
            } catch (e) {}
            return result;
          }

          function readRequestBody(request) {
            try {
              var body = request.body();
              if (body === null) return null;
              var Buffer = Java.use('okio.Buffer');
              var buffer = Buffer.${d}new();
              body.writeTo(buffer);
              var text = buffer.readUtf8();
              buffer.close();
              return text;
            } catch (e) { return null; }
          }

          /** Non-destructive: peekBody buffers a copy, the real response body is untouched for the app. */
          function readResponseBodyReassembled(response) {
            try {
              var peeked = response.peekBody(MAX_BODY_BYTES);
              return peeked.string();
            } catch (e) { return null; }
          }

          // ── Structural (reflection-only) class detection ──────────────────────────────

          function methodsOf(clazz) {
            try { return clazz.getDeclaredMethods(); } catch (e) { return []; }
          }

          function hasMethod(clazz, name, paramCount) {
            var methods = methodsOf(clazz);
            for (var i = 0; i < methods.length; i++) {
              if (methods[i].getName() === name && methods[i].getParameterTypes().length === paramCount) return true;
            }
            return false;
          }

          function looksLikeOkHttpClientClass(clazz) {
            return hasMethod(clazz, 'newCall', 1) && hasMethod(clazz, 'interceptors', 0);
          }

          function looksLikeOkHttpBuilderClass(clazz) {
            var methods = methodsOf(clazz);
            for (var i = 0; i < methods.length; i++) {
              if (methods[i].getName() === 'build' && methods[i].getParameterTypes().length === 0) {
                try {
                  if (looksLikeOkHttpClientClass(methods[i].getReturnType())) return true;
                } catch (e) {}
              }
            }
            return false;
          }

          function discoverBuilderClassNames() {
            var found = {};
            found['okhttp3.OkHttpClient${d}Builder'] = true; // fast path: try the real, unshaded name first
            try {
              var loaded = Java.enumerateLoadedClassesSync();
              for (var i = 0; i < loaded.length; i++) {
                var name = loaded[i];
                if (name.indexOf('Builder') === -1) continue; // cheap prefilter before any reflection
                if (found[name]) continue;
                try {
                  var clazz = Java.use(name).class;
                  if (looksLikeOkHttpBuilderClass(clazz)) found[name] = true;
                } catch (e) { /* not resolvable from the default loader -- tried per-classloader below */ }
              }
            } catch (e) {}
            return Object.keys(found);
          }

          function forEachClassLoader(fn) {
            try {
              var loaders = Java.enumerateClassLoadersSync();
              for (var i = 0; i < loaders.length; i++) {
                try { fn(loaders[i]); } catch (e) {}
              }
            } catch (e) {}
          }

          // ── Reflection-only interceptor-list discovery/mutation ────────────────────────

          function isInterceptorShaped(obj) {
            try { return hasMethod(obj.getClass(), 'intercept', 1); } catch (e) { return false; }
          }

          function findMutableInterceptorListField(builderInstance) {
            var candidates = [];
            var current = builderInstance.getClass();
            while (current !== null) {
              var fields = [];
              try { fields = current.getDeclaredFields(); } catch (e) {}
              for (var i = 0; i < fields.length; i++) {
                try {
                  if (fields[i].getType().getName() === 'java.util.List') {
                    fields[i].setAccessible(true);
                    candidates.push(fields[i]);
                  }
                } catch (e) {}
              }
              try { current = current.getSuperclass(); } catch (e) { current = null; }
            }
            if (candidates.length === 0) return null;

            var ListClass = Java.use('java.util.List');
            for (var c = 0; c < candidates.length; c++) {
              try {
                var value = candidates[c].get(builderInstance);
                if (value !== null) {
                  var list = Java.cast(value, ListClass);
                  if (list.size() > 0 && isInterceptorShaped(list.get(0))) return candidates[c];
                }
              } catch (e) {}
            }
            for (var n = 0; n < candidates.length; n++) {
              try { if (candidates[n].getName() === 'interceptors') return candidates[n]; } catch (e) {}
            }
            return candidates[0];
          }

          // ── The capturing interceptor itself ───────────────────────────────────────────

          var CapturingInterceptorClass = null;
          function capturingInterceptorClass() {
            if (CapturingInterceptorClass !== null) return CapturingInterceptorClass;
            var Interceptor = Java.use('okhttp3.Interceptor');
            CapturingInterceptorClass = Java.registerClass({
              name: 'com.requestr.frida.CapturingInterceptor',
              implements: [Interceptor],
              methods: {
                intercept: function (chain) {
                  var request = chain.request();
                  var startedAt = Date.now();
                  var url = safeToString(request.url());
                  var method = safeToString(request.method());
                  var reqHeaders = headersToObject(request.headers());
                  var reqBody = readRequestBody(request);

                  var response;
                  try {
                    response = chain.proceed(request);
                  } catch (e) {
                    send_('exception', {
                      url: url, method: method, errorMessage: safeToString(e), timestamp: startedAt,
                    });
                    throw e;
                  }

                  var latencyMs = Date.now() - startedAt;
                  send_('response_ready', {
                    url: url,
                    method: method,
                    statusCode: response.code(),
                    protocol: safeToString(response.protocol()),
                    requestHeaders: reqHeaders,
                    responseHeaders: headersToObject(response.headers()),
                    requestBody: reqBody,
                    responseBody: readResponseBodyReassembled(response),
                    latencyMs: latencyMs,
                    timestamp: startedAt,
                  });
                  return response;
                }
              }
            });
            return CapturingInterceptorClass;
          }

          var armedBuilders = {}; // identityHashCode -> true, so a re-called build() on the same builder doesn't double-add

          function installOnBuilder(builderInstance) {
            var Integer = Java.use('java.lang.System');
            var identity = Integer.identityHashCode(builderInstance);
            if (armedBuilders[identity]) return;
            var field = findMutableInterceptorListField(builderInstance);
            if (field === null) {
              send_('hook_error', { message: 'no mutable interceptor list field found on builder' });
              return;
            }
            var ListClass = Java.use('java.util.List');
            var list = Java.cast(field.get(builderInstance), ListClass);
            var interceptor = capturingInterceptorClass().${d}new();
            list.add(interceptor);
            armedBuilders[identity] = true;
          }

          function hookBuilderClass(className, loader) {
            try {
              var factory = loader ? Java.ClassFactory.get(loader) : Java.classFactory;
              var Builder = factory.use(className);
              if (!Builder.build) return false;
              var overloads = Builder.build.overloads;
              for (var i = 0; i < overloads.length; i++) {
                (function (overload) {
                  overload.implementation = function () {
                    try { installOnBuilder(this); } catch (e) {
                      send_('hook_error', { message: 'install failed: ' + e, className: className });
                    }
                    return overload.call(this);
                  };
                })(overloads[i]);
              }
              send_('hook_installed', { className: className });
              return true;
            } catch (e) {
              return false;
            }
          }

          function armAll() {
            var names = discoverBuilderClassNames();
            var anyHooked = false;
            for (var i = 0; i < names.length; i++) {
              if (hookBuilderClass(names[i], null)) anyHooked = true;
            }
            // Also try every non-default classloader directly for the canonical name, covering
            // apps that load a private copy of OkHttp under a child classloader (dynamic
            // feature modules, plugin/DEX-loading frameworks) where enumerateLoadedClassesSync's
            // default-loader `Java.use` above may not resolve it.
            forEachClassLoader(function (loader) {
              if (hookBuilderClass('okhttp3.OkHttpClient${d}Builder', loader)) anyHooked = true;
            });
            send_('armed', { hooked: anyHooked });
          }

          if (Java.available) {
            Java.perform(armAll);
          } else {
            Java.perform(armAll); // Frida guarantees the VM is up by the time perform's callback runs
          }
        })();
        """.trimIndent()
    }
}
