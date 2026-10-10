// Match the backend's Spanish alphabetical order, regardless of browser locale.
const collator = new Intl.Collator("es", {
  sensitivity: "base",
  numeric: true,
});
export function textSortKey(value: string) {
  return value.normalize("NFC").replace(/^[^\p{L}\p{N}]+/u, "");
}
export function compareText(left: string, right: string) {
  return collator.compare(textSortKey(left), textSortKey(right));
}
