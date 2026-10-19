import { test, expect } from "./fixtures";

test("download jobs show submission and download separately and omit only failed tracking", async ({
  page,
}) => {
  let omitted = false;
  const commands: string[] = [];
  const job = () => ({
    jobId: "lifecycle",
    type: "DOWNLOAD",
    status: omitted ? "COMPLETED" : "DOWNLOAD_WAIT",
    client: "qbittorrent",
    selectedBooks: 2,
    selectedItems: 2,
    processedItems: 2,
    accepted: 2,
    alreadyExists: 0,
    failed: 0,
    skipped: 0,
    cancelled: 0,
    pending: 0,
    inFlight: 0,
    batchSize: 100,
    concurrency: 5,
    interval: "0ms",
    multipleHashes: "FIRST",
    download: {
      selected: 2,
      downloaded: 1,
      pending: 0,
      attention: omitted ? 0 : 1,
      ignored: omitted ? 1 : 0,
    },
  });
  await page.route("**/api/torrent/jobs/lifecycle**", async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    if (request.method() === "POST") {
      commands.push(path);
      omitted = true;
      return route.fulfill({ json: job() });
    }
    if (path.endsWith("/items"))
      return route.fulfill({
        json: {
          items: [
            {
              id: "done",
              eplId: 1,
              title: "Descargado",
              hash: "A".repeat(40),
              revision: 1,
              status: "ACCEPTED",
              attempts: 1,
              downloadState: "DOWNLOADED",
            },
            {
              id: "failed",
              eplId: 2,
              title: "Ausente",
              hash: "B".repeat(40),
              revision: 1,
              status: "ACCEPTED",
              attempts: 1,
              downloadState: omitted ? "IGNORED" : "NOT_FOUND",
              downloadMessage: omitted
                ? null
                : "El torrent ya no está disponible",
            },
          ],
          meta: {
            page: 0,
            size: 20,
            totalItems: 2,
            totalPages: 1,
            hasNext: false,
            hasPrevious: false,
          },
        },
      });
    return route.fulfill({ json: job() });
  });
  await page.goto("/downloads/jobs/lifecycle");
  await expect(
    page.getByText("Esperando descargas", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Pausar", exact: true }),
  ).toBeVisible();
  await page.getByText("Informe del trabajo", { exact: true }).click();
  await expect(page.getByRole("progressbar")).toHaveAttribute(
    "aria-valuenow",
    "50",
  );
  await expect(
    page.getByText("Torrents aceptados", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("El torrent ya no está disponible", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Omitir seguimiento", exact: true })
    .click();
  await expect(page.getByRole("progressbar")).toHaveAttribute(
    "aria-valuenow",
    "100",
  );
  await expect(
    page.getByRole("button", { name: "Omitir seguimiento", exact: true }),
  ).toHaveCount(0);
  expect(commands).toEqual([
    "/api/torrent/jobs/lifecycle/items/failed/omit-download",
  ]);
});
