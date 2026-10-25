import { describe, expect, it } from "vitest";
import { formatDate } from "./formatDate";

describe("timestamps", () => {
  it.each(["es", "en"])("includes a zero-padded 24-hour clock with seconds in %s", (locale) => {
    expect(formatDate("2026-10-26T09:04:37Z", locale, "UTC")).toContain("09:04:37");
    expect(formatDate("2026-10-26T00:00:00Z", locale, "UTC")).toContain("00:00:00");
  });
  it("converts instants to the requested timezone", () => {
    expect(formatDate("2026-10-26T09:04:37Z", "es", "Europe/Madrid")).toContain("10:04:37");
  });
  it("keeps dates without a time and handles absent values", () => {
    expect(formatDate("2026-10-26", "es")).toBe("26 oct 2026");
    expect(formatDate(null, "es")).toBe("—");
  });
});
