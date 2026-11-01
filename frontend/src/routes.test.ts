import { describe, expect, it } from "vitest";
import { fromHash, type Page, toHash } from "./routes";

describe("routes", () => {
  it("round-trips every page", () => {
    const pages: Page[] = [
      { view: "mappings" },
      { view: "mapping", id: "abc" },
      { view: "products" },
      { view: "product", id: "def" },
      { view: "graph" },
      { view: "requirements" },
      { view: "releases" },
    ];

    for (const page of pages) {
      expect(fromHash(toHash(page))).toEqual(page);
    }
  });

  it("reads the links generated for Jira and Confluence", () => {
    expect(fromHash("#/mappings/93883fde-0066-47b2-972e-d52c13ec6c15")).toEqual({
      view: "mapping",
      id: "93883fde-0066-47b2-972e-d52c13ec6c15",
    });
  });

  it("falls back to the mappings for empty or unknown hashes", () => {
    expect(fromHash("")).toEqual({ view: "mappings" });
    expect(fromHash("#/gibt-es-nicht")).toEqual({ view: "mappings" });
  });
});
