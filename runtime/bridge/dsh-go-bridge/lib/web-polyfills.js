// Page-side polyfills for browser engines older than the DSH web client targets.
//
// The client uses a handful of recent built-ins (AbortSignal.any, Promise.withResolvers, the Set
// methods, RegExp.escape, ...). An app WebView that is not updated with the system — the one inside
// 卓易通 on HarmonyOS is Huawei's own — lacks some of them, and the webserver's own boot-ready tail
// calls Promise.withResolvers, so one missing built-in stalls the whole page. The bridge places this
// script first in <head>; every entry is installed only where the engine has none.
//
// Zero dependencies and self-contained: the function's source is what ships to the page.
export function installWebPolyfills(g) {
  const define = (target, key, value) => {
    if (target == null || key in target) return;
    Object.defineProperty(target, key, { value, writable: true, configurable: true, enumerable: false });
  };

  define(g.Promise, "withResolvers", function withResolvers() {
    let resolve, reject;
    const promise = new this((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
  });
  define(g.Promise, "try", function tryCall(fn, ...args) {
    return new this((resolve) => resolve(fn(...args)));
  });

  if (g.AbortSignal && g.AbortController) {
    define(g.AbortSignal, "abort", function abort(reason) {
      const controller = new g.AbortController();
      controller.abort(reason);
      return controller.signal;
    });
    define(g.AbortSignal, "timeout", function timeout(ms) {
      const controller = new g.AbortController();
      setTimeout(() => controller.abort(new g.DOMException("signal timed out", "TimeoutError")), ms);
      return controller.signal;
    });
    define(g.AbortSignal, "any", function any(signals) {
      const controller = new g.AbortController();
      const list = Array.from(signals);
      const done = list.find((signal) => signal.aborted);
      if (done) {
        controller.abort(done.reason);
        return controller.signal;
      }
      const onAbort = (event) => {
        for (const signal of list) signal.removeEventListener("abort", onAbort);
        controller.abort(event.target.reason);
      };
      for (const signal of list) signal.addEventListener("abort", onAbort);
      return controller.signal;
    });
  }

  define(g.Object, "hasOwn", (object, key) => Object.prototype.hasOwnProperty.call(Object(object), key));

  const A = g.Array.prototype;
  define(A, "at", function at(index) {
    const n = Math.trunc(index) || 0;
    return this[n < 0 ? n + this.length : n];
  });
  define(A, "findLast", function findLast(fn, self) {
    for (let i = this.length - 1; i >= 0; i--) if (fn.call(self, this[i], i, this)) return this[i];
    return undefined;
  });
  define(A, "findLastIndex", function findLastIndex(fn, self) {
    for (let i = this.length - 1; i >= 0; i--) if (fn.call(self, this[i], i, this)) return i;
    return -1;
  });
  define(A, "toSorted", function toSorted(compare) { return Array.from(this).sort(compare); });
  define(A, "toReversed", function toReversed() { return Array.from(this).reverse(); });
  define(A, "toSpliced", function toSpliced(...args) {
    const copy = Array.from(this);
    copy.splice(...args);
    return copy;
  });
  define(A, "with", function withAt(index, value) {
    const copy = Array.from(this);
    const n = Math.trunc(index) || 0;
    const at = n < 0 ? n + copy.length : n;
    if (at < 0 || at >= copy.length) throw new RangeError("Invalid index");
    copy[at] = value;
    return copy;
  });

  const S = g.String.prototype;
  define(S, "at", function at(index) {
    const s = String(this);
    const n = Math.trunc(index) || 0;
    return s[n < 0 ? n + s.length : n];
  });
  const literal = (value) => String(value).replace(/[\\^$.*+?()[\]{}|]/g, "\\$&");
  define(S, "replaceAll", function replaceAll(search, replacement) {
    if (search instanceof RegExp && !search.global) {
      throw new TypeError("replaceAll must be called with a global RegExp");
    }
    const pattern = search instanceof RegExp ? search : new RegExp(literal(search), "g");
    return String(this).replace(pattern, replacement);
  });

  define(g.RegExp, "escape", function escape(value) {
    const hex = (c) => `\\x${c.charCodeAt(0).toString(16).padStart(2, "0")}`;
    return String(value)
      .replace(/[\\^$.*+?()[\]{}|/]/g, "\\$&")
      .replace(/[-,=<>#&!%:;@~'`"\s]/g, hex)
      .replace(/^[0-9A-Za-z]/, hex);
  });

  if (g.URL) {
    define(g.URL, "canParse", function canParse(url, base) {
      try { new g.URL(url, base); return true; } catch { return false; }
    });
  }

  define(g.Symbol, "dispose", g.Symbol.for("Symbol.dispose"));
  define(g.Symbol, "asyncDispose", g.Symbol.for("Symbol.asyncDispose"));

  const P = g.Set.prototype;
  const keysOf = (other) => {
    if (other == null || typeof other.has !== "function" || typeof other.keys !== "function") {
      throw new TypeError("expected a set-like object");
    }
    return other;
  };
  define(P, "union", function union(other) {
    const out = new g.Set(this);
    for (const v of keysOf(other).keys()) out.add(v);
    return out;
  });
  define(P, "intersection", function intersection(other) {
    const o = keysOf(other);
    const out = new g.Set();
    for (const v of this) if (o.has(v)) out.add(v);
    return out;
  });
  define(P, "difference", function difference(other) {
    const o = keysOf(other);
    const out = new g.Set();
    for (const v of this) if (!o.has(v)) out.add(v);
    return out;
  });
  define(P, "symmetricDifference", function symmetricDifference(other) {
    const out = new g.Set(this);
    for (const v of keysOf(other).keys()) {
      if (this.has(v)) out.delete(v);
      else out.add(v);
    }
    return out;
  });
  define(P, "isSubsetOf", function isSubsetOf(other) {
    const o = keysOf(other);
    for (const v of this) if (!o.has(v)) return false;
    return true;
  });
  define(P, "isSupersetOf", function isSupersetOf(other) {
    for (const v of keysOf(other).keys()) if (!this.has(v)) return false;
    return true;
  });
  define(P, "isDisjointFrom", function isDisjointFrom(other) {
    const o = keysOf(other);
    for (const v of this) if (o.has(v)) return false;
    return true;
  });
}

/** The inline <script> body: runs the installer against the page's global object. */
export const WEB_POLYFILLS_SCRIPT = `(${installWebPolyfills.toString()})(globalThis);`;
