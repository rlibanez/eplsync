/** Header selection is scoped to the visible page; other pages stay untouched. */
export function pageSelection<T>(
  selected: readonly T[],
  visible: readonly T[],
) {
  const selectedSet = new Set(selected);
  const count = visible.filter((id) => selectedSet.has(id)).length;
  return {
    checked: visible.length > 0 && count === visible.length,
    indeterminate: count > 0 && count < visible.length,
  };
}

export function togglePageSelection<T>(
  selected: readonly T[],
  visible: readonly T[],
  checked: boolean,
): T[] {
  if (checked) return [...new Set([...selected, ...visible])];
  const page = new Set(visible);
  return selected.filter((id) => !page.has(id));
}
