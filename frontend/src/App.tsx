import { useEffect, useMemo, useRef, useState } from "react";
import Editor from "./Editor";
import CopyButton from "./CopyButton";
import InfoModal from "./InfoModal";
import {
  type ArgInfo,
  type Engine,
  type FormatError,
  type LocaleInfo,
  type LocaleResult,
  type Meta,
  type PluralCheck,
  fetchLocales,
  fetchMeta,
  formatAllLocales,
  formatMessage,
  prettifyTemplate,
} from "./api";
import { EXAMPLES } from "./examples";
import { useConnectionStatus } from "./useConnectionStatus";
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

function pluralMissing(checks: PluralCheck[]): string[] {
  return [...new Set(checks.flatMap((c) => c.missing))];
}

// Flag emoji from a locale tag's region subtag (e.g. en-US → 🇺🇸).
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

  const conn = useConnectionStatus();
  const disconnected = !conn.online || conn.server === "offline";

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
  const [meta, setMeta] = useState<Meta | null>(null);
  const [showInfo, setShowInfo] = useState(false);

  useEffect(() => {
    fetchLocales().then((list) => {
      if (list.length) setLocales(list);
    });
    fetchMeta().then(setMeta);
  }, []);

  // index.html sets the initial theme pre-paint; this keeps it in sync after.
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

  // Parse locally so JSON errors surface without a round-trip.
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

  async function formatTemplate() {
    const formatted = await prettifyTemplate({
      engine,
      template,
      locale,
      args: {},
    });
    if (formatted !== template) setTemplate(formatted);
  }

  function formatArgs() {
    try {
      const v = JSON.parse(argsText);
      setArgsText(JSON.stringify(v, null, 2));
    } catch {
      // leave as-is; the inline JSON error already explains why
    }
  }

  return (
    <div className="app">
      {showInfo && meta && (
        <InfoModal meta={meta} onClose={() => setShowInfo(false)} />
      )}
      {disconnected && (
        <div className="conn-banner" role="alert">
          <span className="conn-dot" aria-hidden="true" />
          {!conn.online
            ? "You're offline — check your network connection."
            : "Can't reach the server. It'll reconnect automatically on your next change."}
        </div>
      )}
      <header className="topbar">
        <div className="brand">
          <h1>ICU MessageFormat Playground</h1>
          <span className="sub">rendered by icu4j — same output as your JVM backend</span>
        </div>
        <div className="controls">
          <a
            className="icon-btn github-link"
            href="https://github.com/sh-cho/icu-messageformat-playground"
            target="_blank"
            rel="noopener noreferrer"
            title="View source on GitHub"
            aria-label="View source on GitHub"
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 16 16"
              fill="currentColor"
              aria-hidden="true"
            >
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
            </svg>
          </a>
          {meta && (
            <button
              className="icon-btn"
              onClick={() => setShowInfo(true)}
              title="About this playground"
              aria-label="About this playground"
            >
              <svg
                width="18"
                height="18"
                viewBox="0 0 16 16"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zm0 3.2a1.1 1.1 0 1 1 0 2.2 1.1 1.1 0 0 1 0-2.2zM9.3 12H6.7a.7.7 0 0 1 0-1.4h.6V7.9h-.5a.7.7 0 0 1 0-1.4H8c.4 0 .7.3.7.7v3.4h.6a.7.7 0 0 1 0 1.4z" />
              </svg>
            </button>
          )}
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
              <button
                className="fmt-btn"
                onClick={formatTemplate}
                disabled={engine !== "mf1"}
                title={
                  engine === "mf1"
                    ? "Format template (each variant on its own line)"
                    : "Formatting is available for MF1 only"
                }
              >
                Format
              </button>
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
              <button
                className="fmt-btn"
                onClick={formatArgs}
                disabled={!parsedArgs.ok}
                title="Format JSON"
              >
                Format
              </button>
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
