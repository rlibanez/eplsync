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
  it("preserves priority, rejects duplicate fields and only adds an implicit tie-breaker for API queries", () => {
    const input = new URLSearchParams(
      "sort=language,asc&sort=title,desc&sort=title,asc&sort=bad,asc",
    );
    expect(catalogParams(input, false).getAll("sort")).toEqual([
      "language,asc",
      "title,desc",
    ]);
    expect(catalogParams(input).getAll("sort")).toEqual([
      "language,asc",
      "title,desc",
      "eplId,asc",
    ]);
    expect(
      catalogParams(
        new URLSearchParams("sort=eplId,desc&sort=title,asc"),
      ).getAll("sort"),
    ).toEqual(["eplId,desc", "title,asc"]);
  });
});

it("preserves page sorting in both directions and alongside other criteria", () => {
  for (const direction of ["asc", "desc"]) {
    const params = catalogParams(new URLSearchParams("sort=language,asc&sort=pages," + direction));
    expect(params.getAll("sort")).toEqual(["language,asc", "pages," + direction, "eplId,asc"]);
  }
});

it("preserves negative publication years without truncating their sign or digits", () => {
  const params = catalogParams(new URLSearchParams("publicationYear=-2500&publicationYearFrom=-2100&publicationYearTo=-468"));
  expect(params.get("publicationYear")).toBe("-2500");
  expect(params.get("publicationYearFrom")).toBe("-2100");
  expect(params.get("publicationYearTo")).toBe("-468");
});
