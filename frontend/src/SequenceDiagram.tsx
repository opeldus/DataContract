import { useEffect, useId, useState } from "react";

interface Props {
  text: string;
  fileName: string;
}

/** Mermaid is large, so it only loads once a diagram is actually shown. */
export function SequenceDiagram({ text, fileName }: Props) {
  const id = useId().replace(/\W/g, "");
  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    setSvg(null);
    setError(null);
    import("mermaid")
      .then(async ({ default: mermaid }) => {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          maxTextSize: 500_000,
          // Jobs have many stages, so boxes and gaps are much smaller than Mermaid's defaults and shown only once.
          sequence: {
            useMaxWidth: false,
            mirrorActors: false,
            width: 90,
            height: 34,
            actorMargin: 16,
            boxMargin: 4,
            messageMargin: 22,
            diagramMarginX: 8,
            diagramMarginY: 8,
            actorFontSize: 12,
            messageFontSize: 12,
          },
        });
        const result = await mermaid.render(`sequence-${id}`, text);
        if (current) {
          setSvg(result.svg);
        }
      })
      .catch((cause: Error) => {
        if (current) {
          setError(`Das Diagramm konnte nicht erzeugt werden: ${cause.message}`);
        }
      });
    return () => {
      current = false;
    };
  }, [id, text]);

  function download() {
    const url = URL.createObjectURL(new Blob([svg!], { type: "image/svg+xml" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `${fileName}.svg`;
    link.click();
    URL.revokeObjectURL(url);
  }

  if (error) {
    return <p role="alert" className="error">{error}</p>;
  }
  if (!svg) {
    return <p>Lädt…</p>;
  }
  return (
    <>
      <div className="toolbar">
        <span className="spacer" />
        <button type="button" onClick={download}>Als SVG herunterladen</button>
      </div>
      {/* Mermaid sanitises its output with securityLevel "strict", and the backend escapes all names. */}
      <div className="sequence-diagram" dangerouslySetInnerHTML={{ __html: svg }} />
    </>
  );
}
