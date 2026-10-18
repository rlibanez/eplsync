import { describe, expect, it } from "vitest";
import { bookReturn } from "./navigation";

describe("book origin", () => {
  it.each([
    ["/", "home"],
    ["/calibre", "calibre"],
    ["/downloads", "downloads"],
    ["/calibre/updates", "calibreUpdates"],
    ["/downloads/updates", "downloadUpdates"],
    ["/calibre/jobs/job", "calibreJob"],
    ["/downloads/jobs/job", "downloadJob"],
    ["/calibre/jobs/cleanup/job", "calibreJob"],
    ["/downloads/jobs/cleanup/job", "downloadJob"],
    ["/downloads/sync", "sync"],
  ])("returns to %s with filters and state", (pathname, label) => {
    const origin = {
      pathname,
      search: "?page=2&state=FAILED",
      hash: "#table",
      state: { bookViewKey: "origin" },
    };
    expect(bookReturn({ bookReturn: origin })).toEqual({
      to: { pathname, search: origin.search, hash: origin.hash },
      state: origin.state,
      label,
    });
  });
  it.each([
    null,
    {},
    { catalogSearch: "page=2" },
    { bookReturn: null },
    ...["https://evil.test", "//evil.test", "/catalog/123", "/unknown"].map(
      (pathname) => ({ bookReturn: { pathname, search: "", hash: "" } }),
    ),
    { bookReturn: { pathname: "/downloads", search: "bad", hash: "" } },
  ])("rejects absent, malformed or external origins", (state) =>
    expect(bookReturn(state)).toBeNull(),
  );
});
