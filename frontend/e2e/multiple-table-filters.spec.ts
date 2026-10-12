import { test, expect } from "./fixtures";
const cases = [
  {
    path: "/downloads/jobs",
    api: "**/api/torrent/jobs?**",
    label: "Estado",
    key: "status",
    choices: ["En ejecución", "Pausado"],
    values: ["RUNNING", "PAUSED"],
  },
  {
    path: "/downloads/updates",
    api: "**/api/torrent/revision-updates/search?**",
    label: "Estado registrado",
    key: "status",
    choices: ["Descargado", "No encontrado"],
    values: ["DOWNLOADED", "NOT_FOUND"],
  },
  {
    path: "/calibre",
    api: "**/api/calibre/books?**",
    label: "Comparación",
    key: "comparison",
    choices: [
      "Misma revisión que el catálogo",
      "Revisión anterior al catálogo",
    ],
    values: ["CURRENT", "OUTDATED"],
  },
  {
    path: "/calibre",
    api: "**/api/calibre/books?**",
    label: "En el catálogo",
    key: "presence",
    choices: ["Sí", "No"],
    values: ["PRESENT", "MISSING"],
  },
];
for (const item of cases) {
  test(
    item.path + " " + item.label + " supports compact multiple filters",
    async ({ page }) => {
      const queries: URLSearchParams[] = [];
      await page.route("**/api/settings/calibre/snapshot", (r) =>
        r.fulfill({ json: { snapshot: null } }),
      );
      await page.route("**/api/settings/downloads/updates", (r) =>
        r.fulfill({ json: { states: ["DOWNLOADED", "NOT_FOUND"] } }),
      );
      await page.route(item.api, (r) => {
        const params = new URL(r.request().url()).searchParams;
        queries.push(params);
        return r.fulfill({
          json: {
            items: [],
            meta: {
              page: 0,
              size: 20,
              totalItems: 0,
              totalPages: 0,
              hasNext: false,
              hasPrevious: false,
            },
          },
        });
      });
      await page.goto(item.path);
      if (item.path === "/downloads/updates") {
        await page
          .getByRole("button", { name: "Buscar actualizaciones", exact: true })
          .click();
        await page
          .getByRole("dialog")
          .getByRole("button", { name: "Buscar", exact: true })
          .click();
        await expect(page.getByRole("dialog")).toHaveCount(0);
      }
      const input = page.getByRole("textbox", {
        name: item.label,
        exact: true,
      });
      for (const label of item.choices) {
        await input.click();
        await page.getByRole("option", { name: label, exact: true }).click();
      }
      await expect
        .poll(() => queries.at(-1)?.get(item.key))
        .toBe(item.values.join(","));
      expect(queries.at(-1)?.get("page")).toBe("0");
      await input.press("Escape");
      const root = page
        .locator(".mantine-MultiSelect-root")
        .filter({ has: input });
      await root
        .locator(".mantine-Pill-root")
        .filter({ hasText: item.choices[0] })
        .getByRole("button", { includeHidden: true })
        .click();
      await expect
        .poll(() => queries.at(-1)?.get(item.key))
        .toBe(item.values[1]);
    },
  );
}
