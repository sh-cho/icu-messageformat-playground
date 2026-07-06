import { useEffect } from "react";
import type { Meta } from "./api";

interface Row {
  label: string;
  value: React.ReactNode;
}

function Section({ title, rows }: { title: string; rows: Row[] }) {
  return (
    <div className="info-section">
      <h3>{title}</h3>
      <dl className="info-rows">
        {rows.map((r) => (
          <div className="info-row" key={r.label}>
            <dt>{r.label}</dt>
            <dd>{r.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

export default function InfoModal({
  meta,
  onClose,
}: {
  meta: Meta;
  onClose: () => void;
}) {
  // Close on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const engines = meta.engines
    .map((e) => `${e.name}${e.preview ? " (Preview)" : ""}`)
    .join(", ");

  return (
    <div
      className="modal-overlay"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label="About this playground"
    >
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h2>About</h2>
          <button
            className="modal-close"
            onClick={onClose}
            aria-label="Close"
            title="Close"
          >
            ✕
          </button>
        </div>

        <Section
          title="Library & spec"
          rows={[
            { label: "icu4j", value: meta.icu4jVersion },
            { label: "Unicode", value: meta.unicodeVersion },
            { label: "CLDR", value: meta.cldrVersion },
            { label: "Message formats", value: engines },
            { label: "Locales", value: `${meta.localeCount} available` },
          ]}
        />

        <Section
          title="Runtime"
          rows={[
            { label: "Java", value: `${meta.javaVersion} (${meta.javaVm})` },
            { label: "Kotlin", value: meta.kotlinVersion },
            { label: "Runtime", value: meta.runtime },
          ]}
        />

        <Section
          title="App"
          rows={[
            {
              label: "Source",
              value: (
                <a href={meta.repoUrl} target="_blank" rel="noopener noreferrer">
                  {meta.repoUrl.replace(/^https:\/\//, "")}
                </a>
              ),
            },
          ]}
        />
      </div>
    </div>
  );
}
