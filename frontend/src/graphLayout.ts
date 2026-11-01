export interface LayoutEdge {
  from: string;
  to: string;
}

/** Everything that depends on the node, following the data flow. The node itself is not included. */
export function downstreamOf(nodeId: string, edges: LayoutEdge[]): Set<string> {
  const reached = new Set<string>();
  const queue = [nodeId];
  while (queue.length > 0) {
    const current = queue.pop()!;
    for (const edge of edges) {
      if (edge.from === current && !reached.has(edge.to) && edge.to !== nodeId) {
        reached.add(edge.to);
        queue.push(edge.to);
      }
    }
  }
  return reached;
}

/**
 * Assigns each node to a column by its longest path from a source, so data flows left to right.
 * Cycles are invalid but can exist in drafts: their nodes stay in the column reached so far.
 */
export function assignLayers(nodeIds: string[], edges: LayoutEdge[]): Map<string, number> {
  const layers = new Map(nodeIds.map((id) => [id, 0]));
  for (let pass = 0; pass < nodeIds.length; pass++) {
    let changed = false;
    for (const edge of edges) {
      const from = layers.get(edge.from);
      const to = layers.get(edge.to);
      if (from !== undefined && to !== undefined && to < from + 1) {
        layers.set(edge.to, from + 1);
        changed = true;
      }
    }
    if (!changed) {
      break;
    }
  }
  return layers;
}
