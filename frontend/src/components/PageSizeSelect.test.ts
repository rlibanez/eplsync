import { describe, expect, it } from "vitest";
import { canShowAll } from "./PageSizeSelect";
describe("bounded show-all pagination", () => {
  it("requires a known nonempty filtered total of at most 1000", () => {
    for (const total of [undefined, 0, 1001, 73000])
      expect(canShowAll(total)).toBe(false);
    for (const total of [1, 999, 1000]) expect(canShowAll(total)).toBe(true);
  });
});
