import { describe, expect, it } from "vitest";
import { catalogParams } from "./catalog";
describe("catalog URL contract", () => {
  it("always paginates and adds a stable tie-breaker", () => {
    const p = catalogParams(new URLSearchParams());
    expect(p.get("page")).toBe("0");
    expect(p.get("size")).toBe("20");
    expect(p.getAll("sort")).toEqual(["title,asc", "eplId,asc"]);
  });
  it("retains supported filters while rejecting invalid URL options", () => {
    const p = catalogParams(
      new URLSearchParams(
        "title= Dune &author=Herbert&language=es&page=-1&size=100000&sort=download,asc&all=true",
      ),
    );
    expect(p.get("title")).toBe("Dune");
    expect(p.get("author")).toBe("Herbert");
    expect(p.get("language")).toBe("es");
    expect(p.get("page")).toBe("0");
    expect(p.get("size")).toBe("20");
    expect(p.get("sort")).toBe("title,asc");
    expect(p.has("all")).toBe(false);
  });
  it("preserves page and supported order when reopening a detail link", () => {
    const p = catalogParams(
      new URLSearchParams("page=3&size=50&sort=eplId,asc"),
    );
    expect(catalogParams(p).toString()).toBe(p.toString());
    expect(p.getAll("sort")).toEqual(["eplId,asc"]);
    expect(p.get("page")).toBe("3");
  });
});
