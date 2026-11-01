import { describe, expect, it } from "vitest";
import { isTruthy, parseClipboard } from "./paste";

describe("parseClipboard", () => {
  it("splits rows and cells from Excel", () => {
    expect(parseClipboard("a\tb\r\nc\td\r\n")).toEqual([
      ["a", "b"],
      ["c", "d"],
    ]);
  });

  it("keeps line breaks and quotes inside quoted cells", () => {
    expect(parseClipboard('"Zeile 1\nZeile 2"\t"sagt ""hallo"""\n')).toEqual([
      ["Zeile 1\nZeile 2", 'sagt "hallo"'],
    ]);
  });

  it("keeps empty cells and a last row without line break", () => {
    expect(parseClipboard("a\t\tc\nd")).toEqual([["a", "", "c"], ["d"]]);
  });

  it("does not treat a quote inside a cell as quoting", () => {
    expect(parseClipboard('5" Monitor\tx')).toEqual([['5" Monitor', "x"]]);
  });
});

describe("isTruthy", () => {
  it("recognizes values commonly used in mapping sheets", () => {
    expect(["x", "Ja", " TRUE ", "1"].every(isTruthy)).toBe(true);
    expect(["", "nein", "0"].some(isTruthy)).toBe(false);
  });
});
