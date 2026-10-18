import { describe, expect, it } from "vitest";
import { pageSelection, togglePageSelection } from "./pageSelection";

describe("page selection", () => {
  it("adds and removes only visible IDs, without duplicates", () => {
    let selected = togglePageSelection([5, 6], [1, 2], true);
    expect(selected).toEqual([5, 6, 1, 2]);
    expect(togglePageSelection(selected, [1, 2], true)).toEqual(selected);
    selected = togglePageSelection(selected, [1, 2], false);
    expect(selected).toEqual([5, 6]);
    expect(pageSelection(selected, [1, 2])).toEqual({
      checked: false,
      indeterminate: false,
    });
    expect(pageSelection(selected, [5, 6])).toEqual({
      checked: true,
      indeterminate: false,
    });
  });
  it("ignores other pages when displaying partial or empty selection", () => {
    expect(pageSelection([1, 5, 6], [1, 2])).toEqual({
      checked: false,
      indeterminate: true,
    });
    expect(pageSelection([5, 6], [])).toEqual({
      checked: false,
      indeterminate: false,
    });
    expect(togglePageSelection([5, 6], [], false)).toEqual([5, 6]);
  });
});
