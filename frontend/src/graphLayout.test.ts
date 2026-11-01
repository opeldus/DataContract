import { describe, expect, it } from "vitest";
import { assignLayers, downstreamOf } from "./graphLayout";

describe("assignLayers", () => {
  it("places nodes by their longest path from a source", () => {
    const layers = assignLayers(["a", "b", "c"], [
      { from: "a", to: "b" },
      { from: "b", to: "c" },
      { from: "a", to: "c" },
    ]);

    expect([layers.get("a"), layers.get("b"), layers.get("c")]).toEqual([0, 1, 2]);
  });

  it("terminates on a cycle", () => {
    const layers = assignLayers(["a", "b"], [
      { from: "a", to: "b" },
      { from: "b", to: "a" },
    ]);

    expect(layers.size).toBe(2);
  });
});

describe("downstreamOf", () => {
  it("follows the data flow transitively and ignores upstream nodes", () => {
    const edges = [
      { from: "quelle", to: "a" },
      { from: "a", to: "b" },
      { from: "b", to: "c" },
      { from: "x", to: "c" },
    ];

    expect([...downstreamOf("a", edges)].sort()).toEqual(["b", "c"]);
  });

  it("terminates on a cycle without listing the start node", () => {
    const edges = [
      { from: "a", to: "b" },
      { from: "b", to: "a" },
    ];

    expect([...downstreamOf("a", edges)]).toEqual(["b"]);
  });
});
