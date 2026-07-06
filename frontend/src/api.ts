import { markServerReachable, markServerUnreachable } from "./connection";

export type Engine = "mf1" | "mf2";

export type ErrorType = "SYNTAX" | "MISSING_ARG" | "TYPE_MISMATCH" | "INTERNAL";

export interface FormatError {
  type: ErrorType;
  message: string;
  offset: number | null;
}

export interface ArgInfo {
  name: string;
  type: "number" | "string" | "date" | "time";
}

export interface PluralCheck {
  argName: string;
  type: "plural" | "selectordinal";
  required: string[];
  provided: string[];
  missing: string[];
}

export interface FormatResponse {
  output: string | null;
  error: FormatError | null;
  detectedArgs: ArgInfo[];
  pluralChecks: PluralCheck[];
}

export interface LocaleInfo {
  tag: string;
  displayName: string;
}

export interface FormatRequest {
  engine: Engine;
  template: string;
  locale: string;
  args: Record<string, unknown>;
}

export interface LocaleResult {
  tag: string;
  displayName: string;
  output: string | null;
  error: FormatError | null;
  pluralChecks: PluralCheck[];
}

// fetch wrapper that doubles as a passive reachability probe: any resolved
// response means reachable; a non-abort rejection means unreachable.
async function apiFetch(
  input: RequestInfo,
  init?: RequestInit,
): Promise<Response> {
  try {
    const res = await fetch(input, init);
    markServerReachable();
    return res;
  } catch (e) {
    if ((e as Error).name !== "AbortError") markServerUnreachable();
    throw e;
  }
}

export async function formatMessage(
  req: FormatRequest,
  signal?: AbortSignal,
): Promise<FormatResponse> {
  const res = await apiFetch("/api/format", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) {
    return {
      output: null,
      error: { type: "INTERNAL", message: `HTTP ${res.status}`, offset: null },
      detectedArgs: [],
      pluralChecks: [],
    };
  }
  return res.json();
}

export async function formatAllLocales(
  req: FormatRequest,
  signal?: AbortSignal,
): Promise<LocaleResult[]> {
  const res = await apiFetch("/api/format-all", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) return [];
  return res.json();
}

export async function prettifyTemplate(req: FormatRequest): Promise<string> {
  const res = await apiFetch("/api/prettify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) return req.template;
  const data: { template: string } = await res.json();
  return data.template;
}

export async function fetchLocales(): Promise<LocaleInfo[]> {
  try {
    const res = await apiFetch("/api/locales");
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}

export interface EngineInfo {
  id: string;
  name: string;
  preview: boolean;
}

export interface Meta {
  icu4jVersion: string;
  unicodeVersion: string;
  cldrVersion: string;
  engines: EngineInfo[];
  localeCount: number;
  javaVersion: string;
  javaVm: string;
  kotlinVersion: string;
  runtime: string;
  repoUrl: string;
}

export async function fetchMeta(): Promise<Meta | null> {
  try {
    const res = await apiFetch("/api/meta");
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}
