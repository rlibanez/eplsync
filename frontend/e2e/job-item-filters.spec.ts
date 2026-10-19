import { test, expect } from "./fixtures";

for (const kind of ["calibre", "downloads-cleanup", "calibre-cleanup"] as const)
  test(`${kind}: item filters support multiple values, exclusion, paging and field clearing`, async ({
    page,
  }) => {
    const cleanup = kind.endsWith("cleanup"),
      source = kind === "calibre-cleanup" ? "calibre" : "downloads";
    const path = cleanup ? `/${source}/jobs/cleanup/job` : "/calibre/jobs/job";
    const endpoint = cleanup
      ? "/revision-cleanups/job"
      : "/calibre/transfers/job";
    await page.route(`**/api${endpoint}`, (route) =>
      route.fulfill({
        json: {
          id: "job",
          source,
          type: "CLEANUP",
          directRemoval: true,
          action: source === "calibre" ? "BOOK" : "FILES",
          state: "COMPLETED",
          result: "SUCCEEDED",
          library: "main",
          createdAt: "2026-10-16T10:00:00Z",
          updatedAt: "2026-10-16T10:00:00Z",
          total: 40,
          selected: 40,
          pending: 0,
          accepted: 40,
          succeeded: 40,
          blocked: 0,
          failed: 0,
          skipped: 0,
          uncertain: 0,
        },
      }),
    );
    const requests: URLSearchParams[] = [];
    await page.route(`**/api${endpoint}/items?**`, (route) => {
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
    });
    await page.goto(path);
    const id = page.getByRole("textbox", { name: "EPL ID", exact: true });
    const textLabel = cleanup ? "Libro" : "Archivo",
      textKey = cleanup ? "book" : "file";
    const text = page.getByRole("textbox", { name: textLabel, exact: true });
    const state = page.getByRole("textbox", { name: "Estado", exact: true });
    for (const value of ["1", "3"]) {
      await id.fill(value);
      await id.press("Enter");
    }
    for (const value of ["Alpha, beta", "Gamma"]) {
      await text.fill(value);
      await text.press("Enter");
    }
    for (const label of cleanup
      ? ["Eliminado", "Fallido"]
      : ["Enviado", "Error"]) {
      await state.click();
      await page.getByRole("option", { name: label, exact: true }).click();
      await page.keyboard.press("Escape");
    }
    await expect
      .poll(() => requests.at(-1)?.getAll("eplId"))
      .toEqual(["1", "3"]);
    await expect
      .poll(() => requests.at(-1)?.getAll(textKey))
      .toEqual(["Alpha, beta", "Gamma"]);
    await expect
      .poll(() => requests.at(-1)?.get("state"))
      .toBe(cleanup ? "SUCCEEDED,FAILED" : "ACCEPTED,FAILED");
    for (const field of [id, text, state])
      await page
        .locator(".jobs-filters > div")
        .filter({ has: field })
        .getByRole("button", {
          name: "Incluir valores seleccionados",
          exact: true,
        })
        .click();
    await expect
      .poll(() => [
        requests.at(-1)?.get("eplIdOperator"),
        requests.at(-1)?.get(`${textKey}Operator`),
        requests.at(-1)?.get("statusOperator"),
      ])
      .toEqual(["ne", "ne", "ne"]);
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.getAll("eplId")).toEqual(["1", "3"]);
    expect(requests.at(-1)?.getAll(textKey)).toEqual(["Alpha, beta", "Gamma"]);
    await page
      .getByRole("button", { name: "Limpiar: Estado", exact: true })
      .click();
    await expect.poll(() => requests.at(-1)?.get("state") ?? "").toBe("");
    await page
      .getByRole("button", { name: "Limpiar: EPL ID", exact: true })
      .click();
    await expect.poll(() => requests.at(-1)?.getAll("eplId")).toEqual([]);
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("0");
    expect(requests.at(-1)?.get("eplIdOperator")).toBeNull();
    await page
      .getByRole("button", { name: `Limpiar: ${textLabel}`, exact: true })
      .click();
    await expect(page.locator(".jobs-filters .mantine-Pill-label")).toHaveCount(
      0,
    );
    await expect(
      page.getByRole("button", {
        name: "Incluir valores seleccionados",
        exact: true,
      }),
    ).toHaveCount(4);
    await id.fill("8");
    await id.press("Enter");
    await page.getByRole("button", { name: "Limpiar", exact: true }).click();
    await expect(page.locator(".jobs-filters .mantine-Pill-label")).toHaveCount(
      0,
    );
    // Clearing can reuse the cached first page. A new page must use the cleared scope.
    await page.getByRole("button", { name: "Siguiente", exact: true }).click();
    await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
    expect(requests.at(-1)?.getAll("eplId")).toEqual([]);
    expect(requests.at(-1)?.getAll(textKey)).toEqual([]);
    expect(requests.at(-1)?.get("state") ?? "").toBe("");
  });

test("individual download job supports multiple states and a clear link", async ({
  page,
}) => {
  await page.route("**/api/torrent/jobs/job", (route) =>
    route.fulfill({
      json: {
        jobId: "job",
        status: "COMPLETED",
        type: "DOWNLOAD",
        client: "qbittorrent",
        selectedItems: 40,
        processedItems: 40,
        selectedBooks: 40,
        accepted: 40,
        failed: 0,
        alreadyExists: 0,
        skipped: 0,
        pending: 0,
        inFlight: 0,
        cancelled: 0,
        batchSize: 100,
        concurrency: 5,
        interval: "0ms",
        createdAt: "2026-10-16T10:00:00Z",
      },
    }),
  );
  const requests: URLSearchParams[] = [];
  await page.route("**/api/torrent/jobs/job/items?**", (route) => {
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
  });
  await page.goto("/downloads/jobs/job");
  for (const [label, values] of [
    ["EPL ID", ["1", "3"]],
    ["Libro", ["Alpha", "Gamma"]],
    ["Detalle", ["accepted", "verified"]],
  ] as const) {
    const field = page.getByRole("textbox", { name: label, exact: true });
    await expect(field).not.toHaveAttribute("placeholder", /.+/);
    for (const value of values) {
      await field.fill(value);
      await field.press("Enter");
    }
  }
  await expect
    .poll(() => requests.at(-1)?.getAll("detail"))
    .toEqual(["accepted", "verified"]);
  const state = page.getByRole("textbox", { name: "Estado", exact: true });
  for (const label of ["Aceptado", "Fallido"]) {
    await state.click();
    await page.getByRole("option", { name: label, exact: true }).click();
    await page.keyboard.press("Escape");
  }
  await expect
    .poll(() => requests.at(-1)?.get("status"))
    .toBe("ACCEPTED,FAILED");
  await page
    .getByRole("button", { name: "Incluir valores seleccionados", exact: true })
    .last()
    .click();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  expect(requests.at(-1)?.get("statusOperator")).toBe("ne");
  expect(requests.at(-1)?.getAll("eplId")).toEqual(["1", "3"]);
  expect(requests.at(-1)?.getAll("book")).toEqual(["Alpha", "Gamma"]);
  await page.getByRole("button", { name: "Limpiar", exact: true }).click();
  await expect(page.locator(".jobs-filters .mantine-Pill-label")).toHaveCount(
    0,
  );
  await expect(
    page.getByRole("button", { name: "Anterior", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await expect.poll(() => requests.at(-1)?.get("page")).toBe("1");
  expect(requests.at(-1)?.get("status") ?? "").toBe("");
});
