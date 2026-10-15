import { fulfillSavedSearch } from "./saved-search-response";
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
      let searches = 0;
      await page.route("**/api/settings/calibre/snapshot", (r) =>
        fulfillSavedSearch(r, { json: { snapshot: null } }),
      );
      await page.route("**/api/settings/downloads/updates", (r) =>
        fulfillSavedSearch(r, {
          json: { states: ["DOWNLOADED", "NOT_FOUND"] },
        }),
      );
      await page.route(item.api, (r) => {
        const params = new URL(r.request().url()).searchParams;
        queries.push(params);
        return fulfillSavedSearch(r, {
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
      if (item.path === "/downloads/updates") {
        await page.route(
          "**/api/settings/tasks/searches/downloads**",
          (route) => {
            if (route.request().method() === "POST") searches++;
            return fulfillSavedSearch(route, {
              json:
                route.request().method() === "GET"
                  ? { search: null }
                  : {
                      rows: [],
                      searchedAt: Date.now(),
                      states: route.request().postDataJSON().states,
                      origin: "MANUAL",
                      criteria: {},
                    },
            });
          },
        );
      }
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
      if (item.path !== "/downloads/updates") {
        await expect
          .poll(() => queries.at(-1)?.get(item.key))
          .toBe(item.values.join(","));
      } else {
        await expect(
          page
            .locator(".mantine-MultiSelect-root")
            .filter({ has: input })
            .locator(".mantine-Pill-root"),
        ).toHaveCount(2);
        expect(searches).toBe(1);
      }
      if (item.path !== "/downloads/updates")
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
      if (item.path !== "/downloads/updates") {
        await expect
          .poll(() => queries.at(-1)?.get(item.key))
          .toBe(item.values[1]);
      } else {
        await expect(root.locator(".mantine-Pill-root")).toHaveCount(1);
        await expect(root.locator(".mantine-Pill-label")).toHaveText(
          item.choices[1],
        );
        expect(searches).toBe(1);
      }
    },
  );
}
