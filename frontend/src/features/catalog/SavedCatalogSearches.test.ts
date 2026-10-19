import { expect, it } from "vitest";
import { savedFilters, applySavedFilters } from "./SavedCatalogSearches";
it("stores only advanced criteria with repeated values and negation", () => {
  expect(
    savedFilters(
      new URLSearchParams(
        "author=Pratchett%2C+Terry&author=Orwell&authorOperator=NOT&page=5&size=50&sort=title,desc&view=grid",
      ),
    ),
  ).toEqual({
    author: ["Pratchett, Terry", "Orwell"],
    authorOperator: ["NOT"],
  });
});
it("replaces filters and resets only the page, preserving table settings", () => {
  const next = applySavedFilters(
    new URLSearchParams("title=Old&page=6&size=50&sort=author,desc"),
    { author: ["Orwell"], authorOperator: ["NOT"], sort: ["title,asc"] },
  );
  expect(next.get("title")).toBeNull();
  expect(next.get("page")).toBe("0");
  expect(next.get("size")).toBe("50");
  expect(next.getAll("sort")).toEqual(["author,desc"]);
  expect(next.get("authorOperator")).toBe("NOT");
});
