package com.example.scripting

/**
 * The `pm.*` JavaScript shim executed (via [JsScriptEngine.eval]) before every user script, in
 * the same Rhino scope. Written in real JavaScript rather than built out of Rhino
 * `Scriptable`/`Function` Java classes because a chainable assertion API (`pm.expect(x).to.be...`)
 * is dramatically simpler to express in JS itself than by hand-rolling that chain in Java/Kotlin.
 *
 * Every real side effect (reading/writing an environment or global variable, reading/mutating
 * the in-flight request/response, recording a `pm.test()` result, `console.log`) is delegated to
 * the Kotlin `__bridge` object [JsScriptEngine.eval] binds into the scope -- see [PmBridge] for
 * the exact surface this prelude calls into. That keeps this file pure, portable JS with zero
 * Android/Kotlin-specific syntax, and keeps the *actual* environment/globals storage, request/
 * response representation, and `{{var}}`/`{{$name}}` templating entirely on the Kotlin side
 * where it can be unit-tested without a JS engine at all.
 *
 * Matches ReVoman's Postman-script-shaped `pm.*` surface closely enough to run real
 * pre-request/post-response scripts (master plan §1.8) and real live-MITM rewrite scripts
 * (§1.11) written the way a Postman/Insomnia user already expects:
 * `pm.environment.set("token", pm.response.json().token); pm.test("is 200", function () {
 * pm.expect(pm.response.code).to.equal(200); });`
 */
object PmPrelude {

    val SOURCE: String = """
        (function (global) {
          'use strict';

          function deepEqual(a, b) {
            if (a === b) return true;
            if (a === null || b === null || a === undefined || b === undefined) return a === b;
            if (typeof a !== typeof b) return false;
            if (typeof a !== 'object') return false;
            var ak = Object.keys(a), bk = Object.keys(b);
            if (ak.length !== bk.length) return false;
            for (var i = 0; i < ak.length; i++) {
              var k = ak[i];
              if (!Object.prototype.hasOwnProperty.call(b, k)) return false;
              if (!deepEqual(a[k], b[k])) return false;
            }
            return true;
          }

          function describe(v) {
            try { return JSON.stringify(v); } catch (e) { return String(v); }
          }

          // Like describe(), but leaves plain strings unquoted -- console.log('hi') should
          // print `hi`, not `"hi"`. Used only by console.log/warn/error below.
          function logDescribe(v) {
            return (typeof v === 'string') ? v : describe(v);
          }

          // ---- pm.expect: a small chai-`expect`-shaped assertion chain ------------------
          function makeExpect(actual) {
            var negate = false;
            var self = {};
            function assert(pass, posMsg, negMsg) {
              var ok = negate ? !pass : pass;
              var msg = negate ? negMsg : posMsg;
              negate = false;
              if (!ok) throw new Error(msg);
              return self;
            }
            var chainWords = ['to', 'be', 'been', 'have', 'deep', 'which', 'that', 'and',
              'of', 'same', 'at', 'always', 'still', 'also', 'does'];
            chainWords.forEach(function (word) {
              Object.defineProperty(self, word, { get: function () { return self; } });
            });
            Object.defineProperty(self, 'not', {
              get: function () { negate = !negate; return self; }
            });
            self.equal = function (expected) {
              return assert(actual === expected,
                'expected ' + describe(actual) + ' to equal ' + describe(expected),
                'expected ' + describe(actual) + ' to not equal ' + describe(expected));
            };
            self.eql = function (expected) {
              return assert(deepEqual(actual, expected),
                'expected ' + describe(actual) + ' to deeply equal ' + describe(expected),
                'expected ' + describe(actual) + ' to not deeply equal ' + describe(expected));
            };
            function typeCheck(type) {
              return assert(typeof actual === type,
                'expected ' + describe(actual) + ' to be a ' + type,
                'expected ' + describe(actual) + ' to not be a ' + type);
            }
            self.a = typeCheck;
            self.an = typeCheck;
            self.above = function (n) {
              return assert(actual > n,
                'expected ' + describe(actual) + ' to be above ' + n,
                'expected ' + describe(actual) + ' to not be above ' + n);
            };
            self.below = function (n) {
              return assert(actual < n,
                'expected ' + describe(actual) + ' to be below ' + n,
                'expected ' + describe(actual) + ' to not be below ' + n);
            };
            self.least = function (n) {
              return assert(actual >= n,
                'expected ' + describe(actual) + ' to be at least ' + n,
                'expected ' + describe(actual) + ' to not be at least ' + n);
            };
            self.most = function (n) {
              return assert(actual <= n,
                'expected ' + describe(actual) + ' to be at most ' + n,
                'expected ' + describe(actual) + ' to not be at most ' + n);
            };
            self.include = function (item) {
              var pass;
              if (typeof actual === 'string') pass = actual.indexOf(item) !== -1;
              else if (Array.isArray(actual)) pass = actual.indexOf(item) !== -1;
              else if (actual && typeof actual === 'object') pass = Object.prototype.hasOwnProperty.call(actual, item);
              else pass = false;
              return assert(pass,
                'expected ' + describe(actual) + ' to include ' + describe(item),
                'expected ' + describe(actual) + ' to not include ' + describe(item));
            };
            self.property = function (name, value) {
              var has = !!(actual && Object.prototype.hasOwnProperty.call(actual, name));
              if (arguments.length > 1) {
                return assert(has && deepEqual(actual[name], value),
                  'expected ' + describe(actual) + ' to have property ' + name + ' equal to ' + describe(value),
                  'expected ' + describe(actual) + ' to not have property ' + name + ' equal to ' + describe(value));
              }
              return assert(has,
                'expected ' + describe(actual) + ' to have property ' + name,
                'expected ' + describe(actual) + ' to not have property ' + name);
            };
            self['true'] = function () {
              return assert(actual === true, 'expected ' + describe(actual) + ' to be true', 'expected value to not be true');
            };
            self['false'] = function () {
              return assert(actual === false, 'expected ' + describe(actual) + ' to be false', 'expected value to not be false');
            };
            self['null'] = function () {
              return assert(actual === null, 'expected ' + describe(actual) + ' to be null', 'expected value to not be null');
            };
            self.undefined = function () {
              return assert(actual === undefined, 'expected ' + describe(actual) + ' to be undefined', 'expected value to not be undefined');
            };
            self.exist = function () {
              return assert(actual !== null && actual !== undefined,
                'expected value to exist', 'expected value to not exist');
            };
            self.ok = function () {
              return assert(!!actual, 'expected ' + describe(actual) + ' to be truthy', 'expected ' + describe(actual) + ' to be falsy');
            };
            self.empty = function () {
              var pass;
              if (typeof actual === 'string' || Array.isArray(actual)) pass = actual.length === 0;
              else if (actual && typeof actual === 'object') pass = Object.keys(actual).length === 0;
              else pass = false;
              return assert(pass, 'expected ' + describe(actual) + ' to be empty', 'expected ' + describe(actual) + ' to not be empty');
            };
            self.match = function (re) {
              return assert(re.test(actual),
                'expected ' + describe(actual) + ' to match ' + String(re),
                'expected ' + describe(actual) + ' to not match ' + String(re));
            };
            self.lengthOf = function (n) {
              var len = actual && actual.length;
              return assert(len === n,
                'expected ' + describe(actual) + ' to have length ' + n + ' but got ' + len,
                'expected ' + describe(actual) + ' to not have length ' + n);
            };
            return self;
          }

          // ---- pm.environment / pm.globals / pm.variables --------------------------------
          function makeVarScope(getter, setter, unsetter, haser, keyser) {
            return {
              get: function (key) { return getter(key); },
              set: function (key, value) { setter(key, value === undefined || value === null ? '' : String(value)); },
              unset: function (key) { unsetter(key); },
              has: function (key) { return !!haser(key); },
              toObject: function () {
                var out = {};
                var keys = keyser();
                for (var i = 0; i < keys.length; i++) out[keys[i]] = getter(keys[i]);
                return out;
              }
            };
          }

          var environment = makeVarScope(
            function (k) { return __bridge.envGet(k); },
            function (k, v) { __bridge.envSet(k, v); },
            function (k) { __bridge.envUnset(k); },
            function (k) { return __bridge.envHas(k); },
            function () { return __bridge.envKeys(); }
          );
          var globals = makeVarScope(
            function (k) { return __bridge.globalGet(k); },
            function (k, v) { __bridge.globalSet(k, v); },
            function (k) { __bridge.globalUnset(k); },
            function (k) { return __bridge.globalHas(k); },
            function () { return __bridge.globalKeys(); }
          );
          var variables = {
            get: function (key) {
              var v = __bridge.envGet(key);
              if (v !== null && v !== undefined) return v;
              return __bridge.globalGet(key);
            },
            set: function (key, value) { __bridge.envSet(key, value === undefined || value === null ? '' : String(value)); },
            replaceIn: function (template) { return __bridge.templateString(String(template)); }
          };

          // ---- pm.request / pm.response ---------------------------------------------------
          function makeHeaderScope(getter, setter, remover, nameser) {
            return {
              get: function (name) { return getter(name); },
              set: function (name, value) { setter(name, value === undefined || value === null ? '' : String(value)); },
              remove: function (name) { remover(name); },
              has: function (name) { return getter(name) !== null && getter(name) !== undefined; },
              toObject: function () {
                var out = {};
                var names = nameser();
                for (var i = 0; i < names.length; i++) out[names[i]] = getter(names[i]);
                return out;
              }
            };
          }

          var request = {
            headers: makeHeaderScope(
              function (n) { return __bridge.requestGetHeader(n); },
              function (n, v) { __bridge.requestSetHeader(n, __bridge.templateString(v)); },
              function (n) { __bridge.requestRemoveHeader(n); },
              function () { return __bridge.requestHeaderNames(); }
            ),
            json: function () { return JSON.parse(__bridge.requestGetBody()); }
          };
          Object.defineProperty(request, 'url', {
            get: function () { return __bridge.requestGetUrl(); },
            set: function (v) { __bridge.requestSetUrl(__bridge.templateString(String(v))); }
          });
          Object.defineProperty(request, 'method', {
            get: function () { return __bridge.requestGetMethod(); },
            set: function (v) { __bridge.requestSetMethod(String(v)); }
          });
          Object.defineProperty(request, 'body', {
            get: function () { return __bridge.requestGetBody(); },
            set: function (v) { __bridge.requestSetBody(__bridge.templateString(String(v))); }
          });

          var response = {
            headers: makeHeaderScope(
              function (n) { return __bridge.responseGetHeader(n); },
              function (n, v) { __bridge.responseSetHeader(n, __bridge.templateString(v)); },
              function (n) { __bridge.responseRemoveHeader(n); },
              function () { return __bridge.responseHeaderNames(); }
            ),
            text: function () { return __bridge.responseGetBody(); },
            json: function () {
              var body = __bridge.responseGetBody();
              try {
                return JSON.parse(body);
              } catch (e) {
                throw new Error('Response body is not valid JSON: ' + e.message);
              }
            }
          };
          Object.defineProperty(response, 'code', {
            get: function () { return __bridge.responseGetStatusCode(); },
            set: function (v) { __bridge.responseSetStatusCode(v | 0); }
          });
          Object.defineProperty(response, 'status', {
            get: function () { return __bridge.responseGetStatusMessage(); }
          });
          Object.defineProperty(response, 'responseTime', {
            get: function () { return __bridge.responseGetResponseTimeMs(); }
          });
          Object.defineProperty(response, 'body', {
            get: function () { return __bridge.responseGetBody(); },
            set: function (v) { __bridge.responseSetBody(__bridge.templateString(String(v))); }
          });

          // ---- pm.test ---------------------------------------------------------------------
          function test(name, fn) {
            try {
              fn();
              __bridge.recordTest(String(name), true, '');
            } catch (e) {
              __bridge.recordTest(String(name), false, (e && e.message) ? e.message : String(e));
            }
          }

          global.pm = {
            environment: environment,
            globals: globals,
            variables: variables,
            request: request,
            response: response,
            test: test,
            expect: makeExpect,
            info: {}
          };

          global.console = {
            log: function () { __bridge.consoleLog(Array.prototype.slice.call(arguments).map(logDescribe).join(' ')); },
            warn: function () { __bridge.consoleLog('[warn] ' + Array.prototype.slice.call(arguments).map(logDescribe).join(' ')); },
            error: function () { __bridge.consoleLog('[error] ' + Array.prototype.slice.call(arguments).map(logDescribe).join(' ')); }
          };
        })(this);
    """.trimIndent()
}
