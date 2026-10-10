import { describe, expect, it } from "vitest";
import { compareText } from "./textOrdering";
describe("Spanish table ordering", () => {
  it("groups accents, keeps Ñ after N, ignores leading punctuation and orders numbers", () => {
    const input = [
      "Zeta",
      "¿Quién?",
      "Órbita",
      "Niño",
      "Nube",
      "Ñandú",
      "Árbol",
      "Éxodo",
      "Índice",
      "Único",
      "¡Bravo!",
      '"Casa"',
      "Árbol 10",
      "Árbol 2",
      "Árbol 1",
    ];
    expect(input.sort(compareText)).toEqual([
      "Árbol",
      "Árbol 1",
      "Árbol 2",
      "Árbol 10",
      "¡Bravo!",
      '"Casa"',
      "Éxodo",
      "Índice",
      "Niño",
      "Nube",
      "Ñandú",
      "Órbita",
      "¿Quién?",
      "Único",
      "Zeta",
    ]);
    expect(compareText("ÁRBOL", "arbol")).toBe(0);
    expect(compareText("Libro 0002", "Libro 2")).toBe(0);
  });
});
