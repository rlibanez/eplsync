import { test, expect } from "./fixtures";

test("download status accepts multiple alternatives and preserves other filters", async ({
  page,
}) => {
  const queries: URLSearchParams[] = [];
  await page.route("**/api/torrent/downloads?**", (r) => {
    const params = new URL(r.request().url()).searchParams;
    queries.push(params);
    return r.fulfill({
      json: {
        items: [],
        meta: {
          page: Number(params.get("page")),
          size: Number(params.get("size")),
          totalItems: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        },
      },
    });
  });
  await page.goto("/downloads");
  await page.getByRole("textbox", { name: "Origen", exact: true }).click();
  await page.getByRole("option", { name: "EPL Sync", exact: true }).click();
  const status = page.getByRole("textbox", { name: "Estado", exact: true });
  await status.click();
  await page.getByRole("option", { name: "Descargado", exact: true }).click();
  const pill = page
    .locator(".jobs-filters .mantine-Pill-root")
    .filter({ hasText: "Descargado" });
  const pillBox = await pill.boundingBox();
  const searchBox = await status.boundingBox();
  expect(Math.abs(pillBox!.y - searchBox!.y)).toBeLessThan(5);
  await status.click();
  await page.getByRole("option", { name: "Descargando", exact: true }).click();
  await expect
    .poll(() => queries.at(-1)?.get("status"))
    .toBe("DOWNLOADED,DOWNLOADING");
  expect(queries.at(-1)?.get("origin")).toBe("EPLSYNC");
  expect(queries.at(-1)?.get("page")).toBe("0");
  await status.press("Escape");
  const pills = page.locator(".jobs-filters .mantine-Pill-root");
  await pills
    .filter({ hasText: "Descargado" })
    .getByRole("button", { includeHidden: true })
    .click();
  await expect.poll(() => queries.at(-1)?.get("status")).toBe("DOWNLOADING");
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(pills).toHaveCount(0);
  await expect(
    page.getByRole("textbox", { name: "Origen", exact: true }),
  ).toHaveValue("");
  // Clearing can reuse the initial cached result; make a new query to verify its filters.
  await page.getByRole("textbox", { name: "EPL ID", exact: true }).fill("777");
  await page
    .getByRole("textbox", { name: "EPL ID", exact: true })
    .press("Enter");
  await expect.poll(() => queries.at(-1)?.get("eplId")).toBe("777");
  expect(queries.at(-1)?.has("status")).toBe(false);
  expect(queries.at(-1)?.has("origin")).toBe(false);
  await expect(pills).toHaveCount(1);
  await page
    .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
    .click();
  await expect(pills).toHaveCount(0);
});
