import { describe, it, expect, vi, beforeEach } from "vitest";
import { latestSavedSearch, searchPage } from "./SavedUpdateSearch";
import { get } from "../../api/catalog";
vi.mock("../../api/catalog", () => ({ get: vi.fn() }));
describe("saved update searches", () => {
  beforeEach(() => vi.resetAllMocks());
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
  it("loads the authenticated user's server result without browser persistence", async () => {
    const search = {
      rows: [{ id: 1 }],
      searchedAt: 123,
      origin: "MANUAL",
      criteria: { states: ["DOWNLOADED"] },
    };
    vi.mocked(get).mockResolvedValue({ search });
    expect(await latestSavedSearch("downloads")).toEqual(search);
    expect(get).toHaveBeenCalledWith(
      "/settings/tasks/searches/downloads",
      undefined,
    );
    vi.mocked(get).mockResolvedValue({ search: null });
    expect(await latestSavedSearch("calibre")).toBeNull();
  });
  it("propagates read failures without manufacturing an empty saved result", async () => {
    vi.mocked(get).mockRejectedValue(new Error("Connection failed"));
    await expect(latestSavedSearch("downloads")).rejects.toThrow(
      "Connection failed",
    );
  });
});
