import { test, expect } from "./fixtures";

for (const scenario of [
  {
    source: "calibre",
    label: "Estado",
    key: "state",
    operator: "statusOperator",
    labels: ["En cola", "Pausado"],
    values: ["QUEUED", "PAUSED"],
  },
  {
    source: "calibre",
    label: "Tipo",
    key: "type",
    operator: "typeOperator",
    labels: ["Envío", "Limpieza"],
    values: ["SEND", "CLEANUP"],
  },
  {
    source: "downloads",
    label: "Tipo",
    key: "type",
    operator: "typeOperator",
    labels: ["Descarga", "Actualización"],
    values: ["DOWNLOAD", "UPDATE"],
  },
])
  test(`${scenario.source}: multiple ${scenario.label} values support exclusion, pagination and clearing`, async ({
    page,
  }) => {
    const requests: URLSearchParams[] = [];
    await page.route(
      scenario.source === "calibre"
        ? "**/api/calibre/transfers?**"
        : "**/api/torrent/jobs?**",
      (route) => {
        const params = new URL(route.request().url()).searchParams;
        requests.push(params);
        const p = Number(params.get("page") ?? 0);
        return route.fulfill({
          json: {
            items: [],
            meta: {
              page: p,
              size: 20,
              totalItems: 40,
              totalPages: 2,
              hasNext: p === 0,
              hasPrevious: p > 0,
            },
          },
        });
      },
    );
    await page.goto(`/${scenario.source}/jobs`);
    const field = page.getByRole("textbox", {
      name: scenario.label,
      exact: true,
    });
    const control = page.locator(".jobs-filters > div").filter({ has: field });
    for (const label of scenario.labels) {
      await field.click();
      await page.getByRole("option", { name: label, exact: true }).click();
      await page.keyboard.press("Escape");
    }
    await expect
      .poll(() => requests.at(-1)?.get(scenario.key))
      .toBe(scenario.values.join(","));
    for (const label of scenario.labels)
      await expect(
        control.locator(".mantine-Pill-label").filter({ hasText: label }),
      ).toHaveCount(1);
    await control
      .getByRole("button", {
        name: "Incluir valores seleccionados",
        exact: true,
      })
      .click();
    await expect.poll(() => requests.at(-1)?.get(scenario.operator)).toBe("ne");
    // Combine fields, preserving the other field's selected set.
    if (scenario.key === "type") {
      const state = page.getByRole("textbox", { name: "Estado", exact: true });
      for (const label of ["Terminado", "Pausado"]) {
        await state.click();
        await page.getByRole("option", { name: label, exact: true }).click();
        await page.keyboard.press("Escape");
      }
      await expect
        .poll(() =>
          requests
            .at(-1)
            ?.get(scenario.source === "calibre" ? "state" : "status"),
        )
        .toBe("COMPLETED,PAUSED");
    }
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.get(scenario.key)).toBe(scenario.values.join(","));
    expect(requests.at(-1)?.get(scenario.operator)).toBe("ne");
    for (const label of scenario.labels) {
      await field.click();
      await page.getByRole("option", { name: label, exact: true }).click();
      await page.keyboard.press("Escape");
    }
    await expect.poll(() => requests.at(-1)?.get(scenario.key) ?? "").toBe("");
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("0");
    await expect(
      control.getByRole("button", {
        name: "Incluir valores seleccionados",
        exact: true,
      }),
    ).toHaveText("=");
    expect(requests.at(-1)?.get(scenario.operator)).toBeNull();
  });
