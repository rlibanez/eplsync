import { test, expect, operationResponse } from "./fixtures";

for (const access of [true, false]) {
  test(`Calibre event links its original job only with job access (${access})`, async ({
    page,
  }) => {
    await page.route("**/api/auth/me", (r) =>
      r.fulfill({
        json: {
          id: "reader",
          username: "Reader",
          role: "USER",
          mustChangePassword: false,
          permissions: [
            "EVENTS_MANAGE",
            ...(access ? ["CALIBRE_JOBS_MANAGE"] : []),
          ],
        },
      }),
    );
    await page.route("**/api/catalog/covers/task", (r) =>
      r.fulfill({ json: { task: null } }),
    );
    const id = "f9589ff3-d188-4c66-99f3-38c1cb142992";
    const event = {
      id: 1,
      category: "JOB",
      action: "CALIBRE_SEND",
      outcome: "PARTIAL",
      origin: "MANUAL",
      operationId: id,
      createdAt: "2026-11-03T16:44:07Z",
      details: {
        accepted: 14,
        total: 16,
        failed: 2,
        omittedItems: 0,
        library: "library",
        uncertain: 0,
      },
    };
    await page.route("**/api/events/operations?**", (r) =>
      r.fulfill({ json: operationResponse([event], r.request().url()) }),
    );
    await page.goto("/events");
    await page.getByText("Ver resumen", { exact: true }).click();
    await expect(page.locator(".event-details")).toContainText(
      "Omitidos por el usuario",
    );
    await expect(page.locator(".event-details")).toContainText("Biblioteca");
    await expect(page.locator(".event-details")).toContainText("Sin confirmar");
    const link = page.getByRole("link", { name: id, exact: true });
    if (access)
      await expect(link).toHaveAttribute("href", `/calibre/jobs/${id}`);
    else {
      await expect(link).toHaveCount(0);
      await expect(
        page.getByText(`Ejecución: ${id}`, { exact: true }),
      ).toBeVisible();
    }
  });
}
