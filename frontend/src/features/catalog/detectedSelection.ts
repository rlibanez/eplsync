import type { Choice } from "./Discovery";
export function detectedSelectionParams(choice: Choice) {
  const params = new URLSearchParams({ allSelected: String(choice.all) });
  for (const value of choice.all ? (choice.excluded ?? []) : choice.values)
    params.append(choice.all ? "excluded" : "selected", value);
  return params;
}
