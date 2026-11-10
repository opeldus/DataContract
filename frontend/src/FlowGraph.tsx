import { useMemo } from "react";
import { assignLayers, type LayoutEdge } from "./graphLayout";

const NODE_WIDTH = 210;
const NODE_HEIGHT = 46;
const COLUMN_GAP = 80;
const ROW_GAP = 20;
const PADDING = 12;

export interface FlowNode {
  id: string;
  title: string;
  detail: string;
  variant: "contract" | "product" | "asset";
}

interface Props {
  label: string;
  nodes: FlowNode[];
  edges: LayoutEdge[];
  selectedId: string | null;
  highlighted?: Set<string>;
  onSelect: (id: string | null) => void;
}

function truncate(text: string) {
  return text.length > 29 ? `${text.slice(0, 28)}…` : text;
}

export function FlowGraph({ label, nodes, edges: allEdges, selectedId, highlighted, onSelect }: Props) {
  // DataStage jobs can link the same two stages many times; one arrow is enough.
  const edges = useMemo(
    () => [...new Map(allEdges.map((edge) => [`${edge.from}-${edge.to}`, edge])).values()],
    [allEdges],
  );

  const layout = useMemo(() => {
    const layers = assignLayers(nodes.map((node) => node.id), edges);
    const rows = new Map<number, number>();
    const positions = new Map<string, { x: number; y: number }>();
    for (const node of nodes) {
      const layer = layers.get(node.id) ?? 0;
      const row = rows.get(layer) ?? 0;
      rows.set(layer, row + 1);
      positions.set(node.id, { x: PADDING + layer * (NODE_WIDTH + COLUMN_GAP), y: PADDING + row * (NODE_HEIGHT + ROW_GAP) });
    }
    const columns = Math.max(0, ...layers.values()) + 1;
    const maxRows = Math.max(1, ...rows.values());
    return {
      positions,
      width: PADDING * 2 + columns * NODE_WIDTH + (columns - 1) * COLUMN_GAP,
      height: PADDING * 2 + maxRows * NODE_HEIGHT + (maxRows - 1) * ROW_GAP,
    };
  }, [nodes, edges]);

  const inFocus = (id: string) => !highlighted || highlighted.has(id);

  return (
    <div className="graph-scroll">
      <svg width={layout.width} height={layout.height} role="group" aria-label={label} onClick={() => onSelect(null)}>
        <defs>
          <marker id="flow-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0 0 L8 4 L0 8 z" className="graph-arrow" />
          </marker>
        </defs>
        {edges.map((edge) => {
          const from = layout.positions.get(edge.from);
          const to = layout.positions.get(edge.to);
          if (!from || !to) {
            return null;
          }
          const startX = from.x + NODE_WIDTH;
          const startY = from.y + NODE_HEIGHT / 2;
          const endY = to.y + NODE_HEIGHT / 2;
          const middle = (startX + to.x) / 2;
          const onPath = inFocus(edge.from) && inFocus(edge.to);
          return (
            <path
              key={`${edge.from}-${edge.to}`}
              className={highlighted ? (onPath ? "graph-edge impact" : "graph-edge dimmed") : "graph-edge"}
              markerEnd="url(#flow-arrow)"
              d={`M${startX} ${startY} C${middle} ${startY} ${middle} ${endY} ${to.x} ${endY}`}
            />
          );
        })}
        {nodes.map((node) => {
          const position = layout.positions.get(node.id)!;
          const classes = ["graph-node", node.variant];
          if (node.id === selectedId) {
            classes.push("selected");
          } else if (!inFocus(node.id)) {
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
              aria-label={`${node.title}, ${node.detail}`}
              onClick={(event) => {
                event.stopPropagation();
                onSelect(node.id);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelect(node.id);
                }
              }}
            >
              <title>{node.title}</title>
              <rect width={NODE_WIDTH} height={NODE_HEIGHT} rx={node.variant === "product" ? 8 : 3} />
              <text x={10} y={19}>{truncate(node.title)}</text>
              <text x={10} y={35} className="version">{truncate(node.detail)}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
