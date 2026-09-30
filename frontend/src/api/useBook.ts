import { useQuery } from "@tanstack/react-query";
import { get, type Book } from "./catalog";
export function useBook(id: string | undefined) {
  return useQuery({
    queryKey: ["book", id],
    enabled: !!id,
    queryFn: ({ signal }) =>
      get<Book>(`/catalog/books/${encodeURIComponent(id!)}`, signal),
  });
}
