/** Old discovery links migrate to the same presence filters used by Catalog. */
export function normalizeDiscoveryPresence(input: URLSearchParams) {
  const params = new URLSearchParams(input);
  for (const [legacy, library] of [
    ["discoveryExcludeDownloads", "torrent"],
    ["discoveryExcludeCalibre", "calibre"],
  ]) {
    if (
      params.has("discoverySource") &&
      !params.has(`${library}Presence`) &&
      params.get(legacy) === "true"
    )
      params.set(`${library}Presence`, "ABSENT");
    params.delete(legacy);
  }
  return params;
}
