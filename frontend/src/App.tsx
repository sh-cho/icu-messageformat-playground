import { useEffect, useMemo, useRef, useState } from "react";
import Editor from "./Editor";
import CopyButton from "./CopyButton";
import {
  type ArgInfo,
  type Engine,
  type FormatError,
  type LocaleInfo,
  type LocaleResult,
  type PluralCheck,
  fetchLocales,
  formatAllLocales,
  formatMessage,
} from "./api";
import { EXAMPLES } from "./examples";
import {
  type Theme,
  applyTheme,
  onSystemThemeChange,
  persistTheme,
  resolveInitialTheme,
  storedTheme,
} from "./theme";

const FALLBACK_LOCALES: LocaleInfo[] = [
  { tag: "en-US", displayName: "English (US)" },
  { tag: "ko-KR", displayName: "Korean (South Korea)" },
  { tag: "ja-JP", displayName: "Japanese (Japan)" },
];

// Unique missing plural categories across a locale's checks.
function pluralMissing(checks: PluralCheck[]): string[] {
  return [...new Set(checks.flatMap((c) => c.missing))];
}

// Build a flag emoji from a locale tag's region subtag (e.g. en-US → 🇺🇸).
function flagEmoji(tag: string): string {
  const region = tag.split(/[-_]/).find((p) => /^[A-Z]{2}$/.test(p));
  if (!region) return "🏳️";
  return String.fromCodePoint(
    ...[...region].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65),
  );
}

export default function App() {
  const initial = EXAMPLES[0];
  const [engine, setEngine] = useState<Engine>(initial.engine);
  const [template, setTemplate] = useState(initial.template);
  const [argsText, setArgsText] = useState(initial.args);
  const [locale, setLocale] = useState(initial.locale);

  const [theme, setTheme] = useState<Theme>(resolveInitialTheme);
  const [locales, setLocales] = useState<LocaleInfo[]>(FALLBACK_LOCALES);
  const [output, setOutput] = useState<string | null>(null);
  const [error, setError] = useState<FormatError | null>(null);
  const [argsParseError, setArgsParseError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [compareLocales, setCompareLocales] = useState(false);
  const [allResults, setAllResults] = useState<LocaleResult[]>([]);
  const [detectedArgs, setDetectedArgs] = useState<ArgInfo[]>([]);
  const [pluralChecks, setPluralChecks] = useState<PluralCheck[]>([]);

  useEffect(() => {
    fetchLocales().then((list) => {
      if (list.length) setLocales(list);
    });
  }, []);

  // Reflect the active theme onto <html> (an inline script in index.html sets
  // the initial value pre-paint; this keeps it in sync afterwards).
  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  // While the user hasn't made an explicit choice, follow the OS preference live.
  useEffect(() => {
    if (storedTheme() !== null) return;
    return onSystemThemeChange(setTheme);
  }, [theme]);

  function toggleTheme() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    persistTheme(next);
    setTheme(next);
  }

  // Parse the args JSON locally; surface parse errors without a round-trip.
  const parsedArgs = useMemo(() => {
    const text = argsText.trim();
    if (text === "") return { ok: true as const, value: {} };
    try {
      const v = JSON.parse(text);
      if (typeof v !== "object" || v === null || Array.isArray(v)) {
        return { ok: false as const, message: "Arguments must be a JSON object" };
      }
      return { ok: true as const, value: v as Record<string, unknown> };
    } catch (e) {
      return { ok: false as const, message: (e as Error).message };
    }
  }, [argsText]);

  // Debounced auto-render on any input change.
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    if (!parsedArgs.ok) {
      setArgsParseError(parsedArgs.message);
      return;
    }
    setArgsParseError(null);

    const handle = setTimeout(() => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      const req = { engine, template, locale, args: parsedArgs.value };
      setPending(true);

      const single = formatMessage(req, ctrl.signal)
        .then((res) => {
          setOutput(res.output);
          setError(res.error);
          setDetectedArgs(res.detectedArgs ?? []);
          setPluralChecks(res.pluralChecks ?? []);
        })
        .catch((e) => {
          if (e.name !== "AbortError") {
            setError({ type: "INTERNAL", message: String(e), offset: null });
          }
        });

      const multi = compareLocales
        ? formatAllLocales(req, ctrl.signal)
            .then(setAllResults)
            .catch((e) => {
              if (e.name !== "AbortError") setAllResults([]);
            })
        : Promise.resolve();

      Promise.all([single, multi]).finally(() => setPending(false));
    }, 300);

    return () => clearTimeout(handle);
  }, [engine, template, locale, parsedArgs, compareLocales]);

  function loadExample(name: string) {
    const ex = EXAMPLES.find((e) => e.name === name);
    if (!ex) return;
    setEngine(ex.engine);
    setLocale(ex.locale);
    setTemplate(ex.template);
    setArgsText(ex.args);
  }

  function defaultForType(type: ArgInfo["type"]): unknown {
    switch (type) {
      case "number":
        return 1;
      case "date":
      case "time":
        return { "@type": "date", value: "2025-01-01T00:00:00Z" };
      default:
        return "";
    }
  }

  // Insert any of `which` args not already present into the args JSON.
  function addArgs(which: ArgInfo[]) {
    let base: Record<string, unknown> = {};
    try {
      const v = JSON.parse(argsText.trim() || "{}");
      if (v && typeof v === "object" && !Array.isArray(v)) base = v;
    } catch {
      // start from empty if the current JSON is unparseable
    }
    let changed = false;
    for (const a of which) {
      if (!(a.name in base)) {
        base[a.name] = defaultForType(a.type);
        changed = true;
      }
    }
    if (changed) setArgsText(JSON.stringify(base, null, 2));
  }

  const presentKeys = parsedArgs.ok
    ? new Set(Object.keys(parsedArgs.value))
    : new Set<string>();
  const missingArgs = detectedArgs.filter((a) => !presentKeys.has(a.name));

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <h1>ICU MessageFormat Playground</h1>
          <span className="sub">rendered by icu4j — same output as your JVM backend</span>
        </div>
        <div className="controls">
          <button
            className="theme-toggle"
            onClick={toggleTheme}
            title={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
            aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
          >
            {theme === "dark" ? "☀️" : "🌙"}
          </button>

          <div className="engine-toggle" role="group" aria-label="Engine">
            <button
              className={engine === "mf1" ? "active" : ""}
              onClick={() => setEngine("mf1")}
            >
              MF1
            </button>
            <button
              className={engine === "mf2" ? "active" : ""}
              onClick={() => setEngine("mf2")}
            >
              MF2 <span className="preview">Preview</span>
            </button>
          </div>

          <label className="field">
            <span>Locale</span>
            <input
              list="locale-list"
              value={locale}
              onChange={(e) => setLocale(e.target.value)}
              spellCheck={false}
            />
            <datalist id="locale-list">
              {locales.map((l) => (
                <option key={l.tag} value={l.tag}>
                  {flagEmoji(l.tag)} {l.displayName}
                </option>
              ))}
            </datalist>
          </label>

          <label className="field">
            <span>Example</span>
            <select
              defaultValue=""
              onChange={(e) => {
                loadExample(e.target.value);
                e.target.value = "";
              }}
            >
              <option value="" disabled>
                Load…
              </option>
              {EXAMPLES.map((ex) => (
                <option key={ex.name} value={ex.name}>
                  {ex.name}
                </option>
              ))}
            </select>
          </label>
        </div>
      </header>

      <main className="grid">
        <section className="pane pane-template">
          <div className="pane-head">
            <h2>Template</h2>
            <div className="pane-head-right">
              <span className="hint">
                {engine === "mf1" ? "ICU MessageFormat (MF1)" : "MessageFormat 2.0 — Technical Preview"}
              </span>
              <CopyButton value={template} />
            </div>
          </div>
          <Editor
            value={template}
            onChange={setTemplate}
            language="icu"
            errorOffset={error?.offset ?? null}
          />
        </section>

        <section className="pane pane-args">
          <div className="pane-head">
            <h2>Arguments</h2>
            <div className="pane-head-right">
              <span className="hint">
                JSON · dates: {'{ "@type": "date", "value": "…" }'}
              </span>
              <CopyButton value={argsText} />
            </div>
          </div>
          <Editor value={argsText} onChange={setArgsText} language="json" />
          {argsParseError && (
            <div className="inline-error">Invalid JSON: {argsParseError}</div>
          )}
          {detectedArgs.length > 0 && (
            <div className="detected">
              <span className="detected-label">Detected:</span>
              <div className="chips">
                {detectedArgs.map((a) => {
                  const present = presentKeys.has(a.name);
                  return (
                    <button
                      key={a.name}
                      className={`arg-chip${present ? " present" : ""}`}
                      onClick={() => addArgs([a])}
                      disabled={present}
                      title={present ? "Already in args" : `Add ${a.name} to args`}
                    >
                      {a.name}
                      <span className="arg-type">{a.type}</span>
                    </button>
                  );
                })}
              </div>
              <button
                className="scaffold-btn"
                onClick={() => addArgs(detectedArgs)}
                disabled={missingArgs.length === 0}
                title="Add all missing arguments"
              >
                Scaffold{missingArgs.length ? ` (${missingArgs.length})` : ""}
              </button>
            </div>
          )}
        </section>

        <section className="pane output-pane pane-output">
          <div className="pane-head">
            <h2>Output</h2>
            <div className="pane-head-right">
              {pending && <span className="hint">rendering…</span>}
              <label className="compare-toggle" title="Render across all locales">
                <input
                  type="checkbox"
                  checked={compareLocales}
                  onChange={(e) => setCompareLocales(e.target.checked)}
                />
                Compare locales
              </label>
              <CopyButton value={error ? error.message : output ?? ""} />
            </div>
          </div>
          {compareLocales ? (
            <div className="locale-list">
              {allResults.map((r) => (
                <div
                  key={r.tag}
                  className={`locale-row${r.tag === locale ? " current" : ""}`}
                >
                  <span className="locale-flag">{flagEmoji(r.tag)}</span>
                  <div className="locale-meta">
                    <span className="locale-tag">{r.tag}</span>
                    <span className="locale-name">{r.displayName}</span>
                  </div>
                  {r.error ? (
                    <div className="locale-output is-error">
                      {r.error.type}: {r.error.message}
                    </div>
                  ) : (
                    <div className="locale-output">{r.output}</div>
                  )}
                  {pluralMissing(r.pluralChecks).length > 0 && (
                    <span
                      className="miss-badge"
                      title={`Plural categories missing for ${r.tag}`}
                    >
                      ⚠ {pluralMissing(r.pluralChecks).join(", ")}
                    </span>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <>
              {pluralChecks
                .filter((c) => c.missing.length > 0)
                .map((c) => (
                  <div className="plural-warn" key={c.argName}>
                    ⚠ {c.type} <code>{`{${c.argName}}`}</code> — {locale} is missing
                    category: <b>{c.missing.join(", ")}</b>
                    <span className="plural-req">
                      {" "}
                      (requires: {c.required.join(", ")})
                    </span>
                  </div>
                ))}
              {error ? (
                <div className={`error error-${error.type}`}>
                  <div className="error-type">{error.type}</div>
                  <div className="error-message">{error.message}</div>
                  {error.offset != null && (
                    <div className="error-offset">at offset {error.offset}</div>
                  )}
                </div>
              ) : (
                <pre className="output">{output ?? ""}</pre>
              )}
            </>
          )}
        </section>
      </main>
    </div>
  );
}
