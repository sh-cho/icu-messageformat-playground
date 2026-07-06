import { useEffect, useRef } from "react";
import { EditorState, StateEffect, StateField } from "@codemirror/state";
import {
  type Command,
  Decoration,
  DecorationSet,
  EditorView,
  keymap,
  lineNumbers,
} from "@codemirror/view";
import {
  defaultKeymap,
  history,
  historyKeymap,
  indentLess,
  indentMore,
} from "@codemirror/commands";
import { indentUnit } from "@codemirror/language";
import { icu, highlighting, jsonLang } from "./cm-extensions";

const INDENT = "  ";

const insertSpaces: Command = (view) => {
  if (view.state.selection.ranges.some((r) => !r.empty)) return indentMore(view);
  view.dispatch(
    view.state.update(view.state.replaceSelection(INDENT), {
      scrollIntoView: true,
      userEvent: "input",
    }),
  );
  return true;
};

const setErrorOffset = StateEffect.define<number | null>();

const errorMark = Decoration.mark({ class: "cm-error-mark" });

const errorField = StateField.define<DecorationSet>({
  create() {
    return Decoration.none;
  },
  update(deco, tr) {
    deco = deco.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(setErrorOffset)) {
        const off = e.value;
        if (off == null || off < 0 || off > tr.state.doc.length) {
          deco = Decoration.none;
        } else {
          const from = off;
          const to = Math.min(off + 1, tr.state.doc.length);
          deco =
            to > from
              ? Decoration.set([errorMark.range(from, to)])
              : Decoration.none;
        }
      }
    }
    return deco;
  },
  provide: (f) => EditorView.decorations.from(f),
});

// CSS-variable theme, so flipping data-theme on <html> restyles without rebuild.
const cssVarTheme = EditorView.theme({
  "&": { height: "100%", backgroundColor: "transparent", color: "var(--text)" },
  ".cm-scroller": { fontFamily: "inherit" },
  ".cm-content": { caretColor: "var(--accent)" },
  ".cm-gutters": {
    backgroundColor: "var(--panel)",
    color: "var(--muted)",
    borderRight: "1px solid var(--border)",
  },
  ".cm-lineNumbers .cm-gutterElement": { color: "var(--muted)" },
  ".cm-activeLine": { backgroundColor: "var(--active-line)" },
  ".cm-activeLineGutter": {
    backgroundColor: "var(--active-line)",
    color: "var(--text)",
  },
  "&.cm-focused .cm-cursor": { borderLeftColor: "var(--accent)" },
  ".cm-content ::selection": { backgroundColor: "var(--selection)" },
});

interface EditorProps {
  value: string;
  onChange: (value: string) => void;
  language?: "json" | "icu" | "text";
  errorOffset?: number | null;
}

export default function Editor({
  value,
  onChange,
  language = "text",
  errorOffset = null,
}: EditorProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    const extensions = [
      lineNumbers(),
      history(),
      indentUnit.of(INDENT),
      // Bound first so Tab is captured by the editor instead of moving focus.
      keymap.of([
        { key: "Tab", run: insertSpaces, shift: indentLess },
        ...defaultKeymap,
        ...historyKeymap,
      ]),
      errorField,
      cssVarTheme,
      highlighting,
      EditorView.lineWrapping,
      EditorView.updateListener.of((u) => {
        if (u.docChanged) onChangeRef.current(u.state.doc.toString());
      }),
    ];
    if (language === "json") extensions.push(jsonLang());
    else if (language === "icu") extensions.push(icu());

    const view = new EditorView({
      state: EditorState.create({ doc: value, extensions }),
      parent: hostRef.current!,
    });
    viewRef.current = view;
    return () => view.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [language]);

  // Sync external value changes (e.g. loading an example) into the editor.
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
      });
    }
  }, [value]);

  useEffect(() => {
    viewRef.current?.dispatch({ effects: setErrorOffset.of(errorOffset) });
  }, [errorOffset]);

  return <div className="editor" ref={hostRef} />;
}
