import { json } from "@codemirror/lang-json";
import {
  HighlightStyle,
  LanguageSupport,
  StreamLanguage,
  syntaxHighlighting,
} from "@codemirror/language";
import { tags as t } from "@lezer/highlight";

// --- ICU MessageFormat (MF1 + MF2) -----------------------------------------
// There is no Lezer grammar for ICU MessageFormat, so we use a lightweight
// StreamLanguage tokenizer. It covers both MF1 ({arg, plural, ...}, #, quoted
// literals) and MF2 (.match/.input/.local, $vars, :functions).
const ICU_KEYWORDS =
  /^(plural|selectordinal|select|number|date|time|spellout|ordinal|duration|choice|offset)\b/;

const icuStream = StreamLanguage.define({
  name: "icu",
  token(stream) {
    if (stream.eatSpace()) return null;
    if (stream.match(/^\.(match|input|local)\b/)) return "keyword"; // MF2 declarations
    if (stream.match(/^\$[\p{L}\w-]+/u)) return "variableName"; // MF2 variable
    if (stream.match(/^:[\p{L}\w-]+/u)) return "operator"; // MF2 function
    if (stream.match(/^[{}]/)) return "bracket";
    if (stream.match(/^#/)) return "number"; // plural quantity placeholder
    if (stream.match(/^'[^']*'/)) return "string"; // ICU quoted literal
    if (stream.match(ICU_KEYWORDS)) return "keyword";
    stream.next();
    return null;
  },
});

export function icu(): LanguageSupport {
  return new LanguageSupport(icuStream);
}

export function jsonLang(): LanguageSupport {
  return json();
}

// Highlight colors are CSS variables, so they follow the active theme
// (light/dark) without rebuilding the editor.
const v = (name: string) => `var(--${name})`;

const highlightStyle = HighlightStyle.define([
  { tag: t.keyword, color: v("cm-keyword") },
  { tag: t.operator, color: v("cm-keyword") },
  { tag: [t.variableName, t.propertyName], color: v("cm-variable") },
  { tag: [t.string, t.special(t.string)], color: v("cm-string") },
  { tag: [t.number, t.bool, t.null], color: v("cm-number") },
  {
    tag: [t.bracket, t.brace, t.squareBracket, t.paren, t.punctuation],
    color: v("cm-bracket"),
  },
]);

export const highlighting = syntaxHighlighting(highlightStyle);
