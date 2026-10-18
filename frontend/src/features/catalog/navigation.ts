const key = "eplsync:catalog-search";
export function rememberCatalog(search: string) {
  try {
    sessionStorage.setItem(key, search);
  } catch {
    /* Optional browser storage. */
  }
}
export function catalogReturnUrl(state: unknown) {
  let search =
    typeof state === "object" &&
    state !== null &&
    "catalogSearch" in state &&
    typeof state.catalogSearch === "string"
      ? state.catalogSearch
      : undefined;
  if (search === undefined) {
    try {
      search = sessionStorage.getItem(key) ?? "";
    } catch {
      search = "";
    }
  }
  return search ? `/catalog?${new URLSearchParams(search)}` : "/catalog";
}

const bookOrigins = [
  [/^\/$/, "home"],
  [/^\/calibre$/, "calibre"],
  [/^\/calibre\/updates$/, "calibreUpdates"],
  [/^\/calibre\/jobs$/, "calibreJobs"],
  [/^\/calibre\/jobs\/(?:cleanup\/)?[^/]+$/, "calibreJob"],
  [/^\/downloads$/, "downloads"],
  [/^\/downloads\/updates$/, "downloadUpdates"],
  [/^\/downloads\/jobs$/, "downloadJobs"],
  [/^\/downloads\/jobs\/(?:cleanup\/)?[^/]+$/, "downloadJob"],
  [/^\/downloads\/sync$/, "sync"],
  [/^\/cleanup\/jobs(?:\/[^/]+)?$/, "cleanup"],
] as const;

export function bookReturn(state: unknown) {
  if (typeof state !== "object" || state === null || !("bookReturn" in state))
    return null;
  const origin = state.bookReturn;
  if (
    typeof origin !== "object" ||
    origin === null ||
    !("pathname" in origin) ||
    typeof origin.pathname !== "string" ||
    !("search" in origin) ||
    typeof origin.search !== "string" ||
    !("hash" in origin) ||
    typeof origin.hash !== "string"
  )
    return null;
  const label = bookOrigins.find(([pattern]) =>
    pattern.test(origin.pathname as string),
  )?.[1];
  if (
    !label ||
    (origin.search && !origin.search.startsWith("?")) ||
    (origin.hash && !origin.hash.startsWith("#"))
  )
    return null;
  return {
    to: { pathname: origin.pathname, search: origin.search, hash: origin.hash },
    state: "state" in origin ? origin.state : null,
    label,
  };
}
