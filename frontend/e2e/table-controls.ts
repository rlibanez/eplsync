import type { Locator } from "@playwright/test";
import { expect, type Page } from "./fixtures";

export async function checkTableControls(
  page: Page,
  field: string,
  label: string,
  scope?: Locator,
) {
  const root = scope ?? page;
  const table = root.locator(
    ".download-record-table, .updates-table, .events-table",
  );
  const initial = await table
    .locator("thead th[data-table-column]")
    .evaluateAll((cells) =>
      cells.map((cell) => (cell as HTMLElement).dataset.tableColumn),
    );
  await root.getByRole("button", { name: "Columnas", exact: true }).click();
  const picker = page.locator(".column-picker");
  await picker.getByRole("checkbox", { name: label, exact: true }).uncheck();
  await expect(table.locator(`th[data-table-column="${field}"]`)).toHaveCount(
    0,
  );
  await expect(table.locator("tbody tr").first().locator("td")).toHaveCount(
    initial.length -
      1 +
      (await table
        .locator("thead .updates-selection, thead .history-selection")
        .count()),
  );
  await picker.getByRole("checkbox", { name: label, exact: true }).check();
  await picker
    .getByRole("button", { name: `Mover ${label} hacia arriba`, exact: true })
    .click();
  const moved = await table
    .locator("thead th[data-table-column]")
    .evaluateAll((cells) =>
      cells.map((cell) => (cell as HTMLElement).dataset.tableColumn),
    );
  expect(moved.indexOf(field)).toBe(initial.indexOf(field) - 1);
  const cells = await table
    .locator("tbody tr")
    .first()
    .locator("td")
    .allTextContents();
  expect(cells.filter((cell) => cell.trim())).not.toHaveLength(0);
  await picker
    .getByRole("button", { name: "Restaurar columnas", exact: true })
    .click();
  expect(
    await table
      .locator("thead th[data-table-column]")
      .evaluateAll((cells) =>
        cells.map((cell) => (cell as HTMLElement).dataset.tableColumn),
      ),
  ).toEqual(initial);
  await page.keyboard.press("Escape");
  await root.getByRole("button", { name: "Ordenar", exact: true }).click();
  const sorting = page.locator(".catalog-sorting-panel");
  await sorting
    .getByRole("button", { name: "Añadir criterio", exact: true })
    .click();
  await expect(sorting.locator(".catalog-sorting-row")).toHaveCount(2);
  await sorting
    .getByRole("button", { name: "Subir criterio 2", exact: true })
    .click();
  await sorting
    .getByRole("button", { name: "Restablecer", exact: true })
    .click();
  await expect(sorting.locator(".catalog-sorting-row")).toHaveCount(1);
  await page.keyboard.press("Escape");
}
