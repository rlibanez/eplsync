import { useQuery } from "@tanstack/react-query";
import { Link, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";

type Source = "downloads" | "calibre";
export interface WorkListView {
  page?: number;
  size?: number;
  filter?: string;
  type?: string;
  username?: string;
  from?: string;
  to?: string;
  sort?: string;
}
interface Context {
  source: Source;
  query: string;
  view?: WorkListView;
}
interface Neighbor {
  id: string;
  cleanup: boolean;
  index: number;
}
export function useWorkContext(source: Source): Context | undefined {
  const { state } = useLocation();
  return state?.workList?.source === source ? state.workList : undefined;
}
export function WorkNavigation({
  source,
  id,
}: {
  source: Source;
  id?: string;
}) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const context = useWorkContext(source);
  const params = new URLSearchParams(context?.query ?? "sort=createdAt,desc");
  params.delete("page");
  params.delete("size");
  for (const key of new Set(params.keys())) {
    const values = params.getAll(key).filter(value => value.trim());
    params.delete(key);
    values.forEach(value => params.append(key, value));
  }
  const query = params.toString();
  const neighbors = useQuery({
    queryKey: ["work-neighbors", user?.id, source, id, query],
    queryFn: ({ signal }) =>
      get<{ previous: Neighbor | null; next: Neighbor | null }>(
        `/settings/work/${source}/${encodeURIComponent(id!)}/neighbors?${query}`,
        signal,
      ),
    enabled: !!id,
    retry: false,
    staleTime: 0,
  });
  return (
    <div className="detail-navigation-row">
      <Link
        className="back-link"
        to={`/${source}/jobs`}
        state={{ workList: context }}
      >
        <span aria-hidden="true">←</span>{" "}
        {t(source === "downloads" ? "nav.downloadJobs" : "calibreSend.jobs")}
      </Link>
      <nav className="book-navigation" aria-label={t("jobReport.navigation")}>
        {(["previous", "next"] as const).map((direction) => {
          const neighbor = neighbors.data?.[direction];
          if (!neighbor)
            return (
              <span key={direction} className="back-link" aria-disabled="true">
                {t(`detail.${direction}`)}
              </span>
            );
          const size = context?.view?.size ?? 20;
          const view = {
            ...context?.view,
            size,
            page: Math.floor(neighbor.index / size),
          };
          return (
            <Link
              key={direction}
              className="back-link"
              to={`/${source}/jobs/${neighbor.cleanup ? "cleanup/" : ""}${encodeURIComponent(neighbor.id)}`}
              state={{ workList: { source, query, view } }}
            >
              {t(`detail.${direction}`)}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
