import type { Route } from "@playwright/test";

/** Existing scenario fixtures retain rows server-side; HTTP exposes metadata or one page. */
export async function fulfillSavedSearch(
  route: Route,
  response: Parameters<Route["fulfill"]>[0],
) {
  const url = new URL(route.request().url());
  if (
    !/\/settings\/tasks\/searches\/(downloads|calibre)(?:\/rows)?$/.test(
      url.pathname,
    ) ||
    !response?.json
  )
    return route.fulfill(response);
  const payload = response.json as Record<string, any>;
  if (url.pathname.endsWith("/rows")) {
    const p = url.searchParams;
    let rows: Record<string, any>[] = payload.search?.rows ?? [];
    const status = p.get("status")?.split(",");
    rows = rows.filter(
      (row) =>
        (!status || (p.get("statusOperator") === "ne" ? !status.includes(row.status) : status.includes(row.status))) &&
        (!p.get("title") || (String(row.title ?? "").toLowerCase().includes(p.get("title")!.toLowerCase()) !== (p.get("titleOperator") === "ne"))) &&
        (!p.get("author") || (String(row.authors ?? "").toLowerCase().includes(p.get("author")!.toLowerCase()) !== (p.get("authorOperator") === "ne"))) &&
        (!p.get("eplId") || String(row.eplId) === p.get("eplId")) &&
        (!p.get("availability") ||
          (row.downloadAvailability ?? "UNKNOWN") === p.get("availability")),
    );
    const criteria = p.getAll("sort").flatMap((sort) => sort.split(";"));
    rows = [...rows].sort((a, b) => {
      for (const criterion of criteria) {
        const [field, direction] = criterion.split(",");
        const order =
          typeof a[field] === "number" && typeof b[field] === "number"
            ? a[field] - b[field]
            : String(a[field] ?? "").localeCompare(
                String(b[field] ?? ""),
                "es",
                { sensitivity: "base", ignorePunctuation: true },
              );
        if (order) return direction === "desc" ? -order : order;
      }
      return 0;
    });
    const page = Number(p.get("page") ?? 0),
      size = Number(p.get("size") ?? 20),
      totalPages = Math.ceil(rows.length / size);
    return route.fulfill({
      json: {
        items: rows.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalItems: rows.length,
          totalPages,
          hasNext: page + 1 < totalPages,
          hasPrevious: page > 0,
        },
      },
    });
  }
  const metadata = (search: Record<string, unknown> | null) => {
    if (!search) return null;
    const { rows: _rows, ...summary } = search;
    return summary;
  };
  return route.fulfill({
    ...response,
    json:
      "search" in payload
        ? { ...payload, search: metadata(payload.search) }
        : metadata(payload),
  });
}
