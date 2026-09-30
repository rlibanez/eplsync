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
