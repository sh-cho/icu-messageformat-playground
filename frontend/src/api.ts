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

export async function formatMessage(
  req: FormatRequest,
  signal?: AbortSignal,
): Promise<FormatResponse> {
  const res = await fetch("/api/format", {
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
  const res = await fetch("/api/format-all", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
    signal,
  });
  if (!res.ok) return [];
  return res.json();
}

export async function fetchLocales(): Promise<LocaleInfo[]> {
  try {
    const res = await fetch("/api/locales");
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}
