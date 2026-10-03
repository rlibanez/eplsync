import { useQuery } from "@tanstack/react-query";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { catalogParams, get, type BookPage } from "../../api/catalog";
import { filterRequest } from "./CatalogFilters";

export function BookNavigation({ id }: { id: number }) {
  const { t } = useTranslation();
  const { state } = useLocation();
  const search = typeof state?.catalogSearch === "string" ? state.catalogSearch : null;
  const params = catalogParams(new URLSearchParams(search ?? ""));
  const page = Number(params.get("page") ?? 0);
  const load = (target: number, signal: AbortSignal) => {
    const next = new URLSearchParams(params);
    next.set("page", String(target));
    return get<BookPage>(`/catalog/books?${filterRequest(next)}`, signal);
  };
  const current = useQuery({
    queryKey: ["book-navigation", search, page],
    queryFn: ({ signal }) => load(page, signal), enabled: search !== null,
  });
  const index = current.data?.items.findIndex(book => book.eplId === id) ?? -1;
  const last = (current.data?.items.length ?? 0) - 1;
  const previous = useQuery({
    queryKey: ["book-navigation", search, page - 1],
    queryFn: ({ signal }) => load(page - 1, signal),
    enabled: search !== null && index === 0 && page > 0,
  });
  const next = useQuery({
    queryKey: ["book-navigation", search, page + 1],
    queryFn: ({ signal }) => load(page + 1, signal),
    enabled: search !== null && index >= 0 && index === last && page + 1 < (current.data?.meta.totalPages ?? 0),
  });
  if (search === null) return null;
  const before = index > 0 ? current.data?.items[index - 1] : index === 0 ? previous.data?.items.at(-1) : undefined;
  const after = index >= 0 && index < last ? current.data?.items[index + 1] : index >= 0 ? next.data?.items[0] : undefined;
  return <nav className="book-navigation" aria-label={t("detail.navigation")}>
    {([ [before, index === 0 ? page - 1 : page, "previous"], [after, index === last ? page + 1 : page, "next"] ] as const).map(([book, targetPage, label]) => {
      const targetSearch = new URLSearchParams(search);
      targetSearch.set("page", String(targetPage));
      return book ? <Link className="back-link" key={label} to={`/catalog/${book.eplId}`} state={{ catalogSearch: targetSearch.toString() }}>{t(`detail.${label}`)}</Link>
        : <span key={label} className="back-link" aria-disabled="true">{t(`detail.${label}`)}</span>;
    })}
  </nav>;
}
