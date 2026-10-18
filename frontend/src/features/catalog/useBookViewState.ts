import { useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { useLocation } from "react-router-dom";

// Bounded, tab-local UI snapshots. Restored only by the book's explicit return link.
const views = new Map<string, Record<string, unknown>>();
export function bookViewSnapshot(key: string) {
  if (!views.has(key)) views.set(key, {});
  while (views.size > 50) views.delete(views.keys().next().value!);
  return views.get(key)!;
}
export function useBookViewState<T>(
  name: string,
  initial: T,
): [T, Dispatch<SetStateAction<T>>] {
  const location = useLocation();
  const [value, setValue] = useState<T>(() => {
    const saved = location.state?.bookView;
    return saved && Object.hasOwn(saved, name) ? (saved[name] as T) : initial;
  });
  useEffect(() => {
    bookViewSnapshot(location.key)[name] = value;
  }, [location.key, name, value]);
  return [value, setValue];
}
