import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  latestSavedSearch,
  savedSearchPage,
  savedSearchUrl,
} from "./SavedUpdateSearch";
import { get } from "../../api/catalog";
vi.mock("../../api/catalog", () => ({ get: vi.fn() }));
describe("saved update searches", () => {
  beforeEach(() => vi.resetAllMocks());
  it("requests only a server page with filters, ordering and a stable generation", async () => {
    const page = { items: [{ eplId: 7 }], meta: { totalItems: 1200 } };
    vi.mocked(get).mockResolvedValue(page);
    const url = savedSearchUrl("downloads", 123, "title,asc;eplId,desc", {
      status: "DOWNLOADED,NOT_FOUND",
    });
    const signal = new AbortController().signal;
    expect(await savedSearchPage(url, 1, 1000, signal)).toEqual(page);
    const [request, passedSignal] = vi.mocked(get).mock.calls[0];
    const params = new URL(request, "http://test").searchParams;
    expect(params.getAll("sort")).toEqual(["title,asc", "eplId,desc"]);
    expect(params.get("generation")).toBe("123");
    expect(params.get("status")).toBe("DOWNLOADED,NOT_FOUND");
    expect(params.get("page")).toBe("1");
    expect(params.get("size")).toBe("1000");
    expect(passedSignal).toBe(signal);
  });
  it("loads the authenticated user's server result without browser persistence", async () => {
    const search = {
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
