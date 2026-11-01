import { useEffect, useMemo, useState } from "react";
import { api, type Graph, type GraphNode } from "./api";
import { assignLayers, downstreamOf } from "./graphLayout";
import { STATUS_LABELS } from "./labels";

const NODE_WIDTH = 190;
const NODE_HEIGHT = 46;
const COLUMN_GAP = 90;
const ROW_GAP = 22;
const PADDING = 12;

function truncate(label: string) {
  return label.length > 26 ? `${label.slice(0, 25)}…` : label;
}

interface Props {
  release: string | null;
  onOpen: (node: GraphNode) => void;
}

export function DependencyGraph({ release, onOpen }: Props) {
  const [graph, setGraph] = useState<Graph | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setSelectedId(null);
    api.graph(release).then(setGraph).catch((cause: Error) => setError(cause.message));
  }, [release]);

  const layout = useMemo(() => {
    if (!graph) {
      return null;
    }
    const layers = assignLayers(graph.nodes.map((node) => node.id), graph.edges);
    const rowsInLayer = new Map<number, number>();
    const positions = new Map<string, { x: number; y: number }>();
    const ordered = [...graph.nodes].sort((a, b) => a.label.localeCompare(b.label));
    for (const node of ordered) {
      const layer = layers.get(node.id) ?? 0;
      const row = rowsInLayer.get(layer) ?? 0;
      rowsInLayer.set(layer, row + 1);
      positions.set(node.id, {
        x: PADDING + layer * (NODE_WIDTH + COLUMN_GAP),
        y: PADDING + row * (NODE_HEIGHT + ROW_GAP),
      });
    }
    const columns = Math.max(0, ...layers.values()) + 1;
    const rows = Math.max(1, ...rowsInLayer.values());
    return {
      positions,
      width: PADDING * 2 + columns * NODE_WIDTH + (columns - 1) * COLUMN_GAP,
      height: PADDING * 2 + rows * NODE_HEIGHT + (rows - 1) * ROW_GAP,
    };
  }, [graph]);

  const impact = useMemo(
    () => (selectedId && graph ? downstreamOf(selectedId, graph.edges) : new Set<string>()),
    [selectedId, graph],
  );

  if (error) {
    return <main className="page"><p role="alert" className="error">{error}</p></main>;
  }
  if (!graph || !layout) {
    return <main className="page"><p>Lädt…</p></main>;
  }

  const selected = graph.nodes.find((node) => node.id === selectedId) ?? null;
  const impacted = graph.nodes.filter((node) => impact.has(node.id));
  const isHighlighted = (id: string) => id === selectedId || impact.has(id);

  return (
    <main className="page">
      <h1>Abhängigkeiten</h1>
      <section className="panel" aria-label="Abhängigkeitsgraph">
        <div className="graph-legend">
          <span><i className="legend-contract" />Contract</span>
          <span><i className="legend-product" />Datenprodukt</span>
          <span>Daten fließen von links nach rechts. Wähle ein Element, um die Auswirkungen zu sehen.</span>
        </div>
        {graph.nodes.length === 0 && <p className="empty">Es gibt noch keine Contracts oder Datenprodukte.</p>}
        {graph.nodes.length > 0 && (
          <div className="graph-scroll">
            <svg
              width={layout.width}
              height={layout.height}
              role="group"
              aria-label="Contracts und Datenprodukte mit ihren Verbindungen"
              onClick={() => setSelectedId(null)}
            >
              <defs>
                <marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
                  <path d="M0 0 L8 4 L0 8 z" className="graph-arrow" />
                </marker>
              </defs>
              {graph.edges.map((edge) => {
                const from = layout.positions.get(edge.from);
                const to = layout.positions.get(edge.to);
                if (!from || !to) {
                  return null;
                }
                const startX = from.x + NODE_WIDTH;
                const startY = from.y + NODE_HEIGHT / 2;
                const endY = to.y + NODE_HEIGHT / 2;
                const middle = (startX + to.x) / 2;
                const onImpactPath = selectedId !== null && isHighlighted(edge.from) && impact.has(edge.to);
                const className = onImpactPath ? "graph-edge impact" : selectedId ? "graph-edge dimmed" : "graph-edge";
                return (
                  <path
                    key={`${edge.from}-${edge.to}`}
                    className={className}
                    markerEnd="url(#arrow)"
                    d={`M${startX} ${startY} C${middle} ${startY} ${middle} ${endY} ${to.x} ${endY}`}
                  />
                );
              })}
              {graph.nodes.map((node) => {
                const position = layout.positions.get(node.id)!;
                const classes = ["graph-node", node.kind === "PRODUCT" ? "product" : "contract"];
                if (node.id === selectedId) {
                  classes.push("selected");
                } else if (selectedId && !impact.has(node.id)) {
                  classes.push("dimmed");
                }
                return (
                  <g
                    key={node.id}
                    className={classes.join(" ")}
                    transform={`translate(${position.x} ${position.y})`}
                    role="button"
                    tabIndex={0}
                    aria-pressed={node.id === selectedId}
                    aria-label={`${node.kind === "PRODUCT" ? "Datenprodukt" : "Contract"} ${node.label}, Version ${node.version}, ${STATUS_LABELS[node.status]}`}
                    onClick={(event) => {
                      event.stopPropagation();
                      setSelectedId(node.id);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        setSelectedId(node.id);
                      }
                    }}
                  >
                    <title>{node.label}</title>
                    <rect width={NODE_WIDTH} height={NODE_HEIGHT} rx={node.kind === "PRODUCT" ? 8 : 3} />
                    <text x={10} y={19}>{truncate(node.label)}</text>
                    <text x={10} y={35} className="version">{node.version} · {STATUS_LABELS[node.status]}</text>
                  </g>
                );
              })}
            </svg>
          </div>
        )}
      </section>

      {selected && (
        <section className="panel" aria-label="Auswirkungen" aria-live="polite">
          <div className="toolbar">
            <h2>Auswirkungen von {selected.label}</h2>
            <span className="spacer" />
            <button type="button" onClick={() => onOpen(selected)}>Öffnen</button>
          </div>
          {impacted.length === 0 && <p className="empty">Davon hängt nichts ab.</p>}
          {impacted.length > 0 && (
            <>
              <p className="meta">Eine Änderung kann diese {impacted.length} Elemente betreffen:</p>
              <ul className="consumers">
                {impacted.map((node) => (
                  <li key={node.id}>
                    <button type="button" className="link" onClick={() => onOpen(node)}>{node.label}</button>{" "}
                    ({node.kind === "PRODUCT" ? "Datenprodukt" : "Contract"}, {node.version})
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </main>
  );
}
