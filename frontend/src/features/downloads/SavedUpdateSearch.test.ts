import { describe, it, expect } from "vitest";
import { collectSearch, searchPage } from "./SavedUpdateSearch";
describe("saved update searches", () => {
  it("sorts alphabetically and paginates without modifying the saved rows", () => {
    const rows = [
      { title: "Zorro", revision: 1 },
      { title: "Árbol", revision: 2 },
      { title: "Barco", revision: 3 },
    ];
    expect(
      searchPage(rows, 0, 2, "title,asc").items.map((x) => x.title),
    ).toEqual(["Árbol", "Barco"]);
    expect(searchPage(rows, 1, 2, "revision,desc").items).toEqual([rows[0]]);
    expect(rows[0].title).toBe("Zorro");
  });
  it("collects all pages and propagates errors instead of replacing a previous result", async () => {
    const seen: number[] = [];
    expect(
      await collectSearch(async (page) => {
        seen.push(page);
        return searchPage(
          [1, 2, 3].map((id) => ({ id })),
          page,
          2,
          "id,asc",
        );
      }),
    ).toEqual([{ id: 1 }, { id: 2 }, { id: 3 }]);
    expect(seen).toEqual([0, 1]);
    await expect(
      collectSearch(async () => {
        throw new Error("Connection failed");
      }),
    ).rejects.toThrow("Connection failed");
  });
});
