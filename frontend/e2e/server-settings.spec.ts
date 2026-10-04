import { test, expect } from "./fixtures";

test("server settings save only edited fields, keep secrets hidden and restore installation defaults", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  const original = [
    {
      key: "torrent.enabled",
      type: "boolean",
      value: false,
      overridden: false,
      configured: false,
    },
    {
      key: "torrent.base-url",
      type: "text",
      value: "http://qbittorrent:8090",
      overridden: false,
      configured: false,
    },
    {
      key: "torrent.bulk.concurrency",
      type: "number",
      value: 1,
      overridden: false,
      configured: false,
    },
    {
      key: "torrent.qbittorrent.auth.password",
      type: "secret",
      value: "",
      overridden: true,
      configured: true,
    },
  ];
  let fields = structuredClone(original);
  let saved: Record<string, unknown> = {};
  await page.route("**/api/settings/torrent", (r) => {
    if (r.request().method() === "PUT") {
      saved = r.request().postDataJSON();
      fields = fields.map((f) =>
        Object.hasOwn(saved, f.key)
          ? { ...f, value: saved[f.key] as typeof f.value, overridden: true }
          : f,
      );
    }
    if (r.request().method() === "DELETE") fields = structuredClone(original);
    return r.fulfill({ json: { section: "torrent", fields } });
  });
  await page.goto("/settings/torrent");
  await expect(page.getByLabel("Contraseña", { exact: true })).toHaveValue("");
  await page.getByLabel("Envíos simultáneos", { exact: true }).fill("4");
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect.poll(() => saved["torrent.bulk.concurrency"]).toBe(4);
  expect(saved).not.toHaveProperty("torrent.qbittorrent.auth.password");
  await page.reload();
  await expect(
    page.getByLabel("Envíos simultáneos", { exact: true }),
  ).toHaveValue("4");
  await page
    .getByRole("button", {
      name: "Restaurar valores de instalación",
      exact: true,
    })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", {
      name: "Restaurar valores de instalación",
      exact: true,
    })
    .click();
  await expect(
    page.getByLabel("Envíos simultáneos", { exact: true }),
  ).toHaveValue("1");
  await page.screenshot({
    path: "test-results/server-settings.png",
    fullPage: true,
  });
});

test("cover execution and saved defaults use the same fields and layout", async ({
  page,
}) => {
  await page.route("**/api/ui/config", (r) =>
    r.fulfill({ json: { defaultLanguage: "es" } }),
  );
  await page.route("**/api/catalog/covers/config", (r) =>
    r.fulfill({
      json: {
        connectTimeoutMs: 3000,
        requestTimeoutMs: 3000,
        batchTimeoutMs: 4000,
        concurrency: 4,
      },
    }),
  );
  await page.route("**/api/catalog/covers/task", (r) =>
    r.fulfill({ json: { task: null } }),
  );
  await page.route("**/api/settings/covers", (r) =>
    r.fulfill({
      json: {
        section: "covers",
        fields: [
          {
            key: "catalog.cover-check.connect-timeout",
            type: "duration",
            value: "3000ms",
          },
          {
            key: "catalog.cover-check.request-timeout",
            type: "duration",
            value: "PT3S",
          },
          {
            key: "catalog.cover-check.batch-timeout",
            type: "duration",
            value: "4s",
          },
          { key: "catalog.cover-check.concurrency", type: "number", value: 4 },
        ],
      },
    }),
  );
  await page.goto("/settings/covers");
  await expect(page.locator(".settings-section h2")).toHaveText([
    "Comprobar portadas",
    "Configuración de portadas",
  ]);
  await expect(page.locator(".cover-parameters")).toHaveCount(2);
  for (const label of [
    "Tiempo de conexión (segundos)",
    "Tiempo por URL (segundos)",
    "Tiempo por lote (segundos)",
    "Comprobaciones simultáneas",
  ]) {
    const inputs = page.getByRole("textbox", { name: label, exact: true });
    await expect(inputs).toHaveCount(2);
    expect(await inputs.first().inputValue()).toBe(
      await inputs.last().inputValue(),
    );
  }
  await page.screenshot({
    path: "test-results/cover-settings-layout.png",
    fullPage: true,
  });
});

test('trackers and tags support adding editing and removing individual rows', async ({ page }) => {
  await page.route('**/api/ui/config', r => r.fulfill({ json: { defaultLanguage: 'es' } }));
  let saved: Record<string, unknown> = {};
  await page.route('**/api/settings/torrent', r => {
    if (r.request().method() === 'PUT') saved = r.request().postDataJSON();
    return r.fulfill({ json: { section: 'torrent', fields: [
      { key: 'torrent.trackers', type: 'list', value: ['https://tracker.example/announce'], overridden: false },
      { key: 'torrent.qbittorrent.download.tags', type: 'list', value: ['EPLsync', '{language}'], overridden: false },
    ] } });
  });
  await page.goto('/settings/torrent');
  const trackers = page.getByRole('group', { name: 'Trackers', exact: true });
  await trackers.getByRole('textbox').fill('https://new.example/announce');
  await trackers.getByRole('button', { name: 'Añadir fila' }).click();
  await trackers.getByRole('textbox').last().fill('udp://tracker.example:1337');
  const tags = page.getByRole('group', { name: 'Etiquetas', exact: true });
  await tags.getByRole('button', { name: 'Eliminar Etiquetas 1' }).click();
  await expect(tags.getByRole('textbox')).toHaveValue('{language}');
  await page.getByRole('button', { name: 'Guardar', exact: true }).click();
  await expect.poll(() => saved['torrent.trackers']).toEqual(['https://new.example/announce', 'udp://tracker.example:1337']);
  expect(saved['torrent.qbittorrent.download.tags']).toEqual(['{language}']);
});

test('dependent client defaults are grouped and preserve disabled values', async ({ page }) => {
  await page.route('**/api/ui/config', r => r.fulfill({ json: { defaultLanguage: 'es' } }));
  await page.route('**/api/settings/torrent', r => r.fulfill({ json: { section: 'torrent', fields: [
    { key: 'torrent.qbittorrent.download.auto-management', type: 'boolean', value: true },
    { key: 'torrent.download.save-path', type: 'text', value: '/downloads/books' },
    { key: 'torrent.rename.enabled', type: 'boolean', value: false },
    { key: 'torrent.rename.pattern', type: 'text', value: '{title}' },
  ] } }));
  await page.goto('/settings/torrent');
  const location = page.getByRole('group', { name: 'Ubicación de descarga', exact: true });
  const path = location.getByRole('textbox');
  await expect(path).toBeDisabled();
  await location.getByRole('checkbox').uncheck();
  await expect(path).toBeEnabled();
  await expect(path).toHaveValue('/downloads/books');
  const naming = page.getByRole('group', { name: 'Nombre del torrent', exact: true });
  const pattern = naming.getByRole('textbox');
  await expect(pattern).toBeDisabled();
  await naming.getByRole('checkbox').check();
  await pattern.fill('{author} - {title}');
  await naming.getByRole('checkbox').uncheck();
  await expect(pattern).toBeDisabled();
  await naming.getByRole('checkbox').check();
  await expect(pattern).toHaveValue('{author} - {title}');
});
