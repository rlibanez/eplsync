import { useAuth } from "../auth/Auth";
import { Fragment } from "react";
import {
  useHomePreferences,
  useSaveHomePreferences,
  type HomeSectionId,
} from "./homePreferences";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { useQueries, useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Library,
  Download,
  ListChecks,
  BookOpen,
  X,
} from "lucide-react";
import { ActionIcon, Badge } from "@mantine/core";
import { get, type BookPage } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { BookCover } from "../catalog/BookCover";
import { homeReads } from "./homeReads";
import { operationOutcome, type OperationPage } from "../events/eventTypes";
import { Failure, Loading } from "../../components/Feedback";

const bookSections = [
  { status: "PUBLISHED", title: "newReleases" },
  { status: "UPDATED", title: "recentUpdates" },
  { status: null, title: "recentBooks" },
] as const;
const bookSectionParams = (status: string | null) =>
  status
    ? `publicationStatus=${status}&sort=publicationDate%2Cdesc&sort=eplId%2Cdesc`
    : "sort=insertDate%2Cdesc&sort=eplId%2Cdesc";
const activeStates = ["QUEUED", "RUNNING", "RETRY_WAIT", "PAUSED"];
export function Home() {
  const { t } = useTranslation();
  const auth = useAuth();
  const preferences = useHomePreferences();
  const savePreferences = useSaveHomePreferences();
  const sectionConfig = (id: HomeSectionId) =>
    preferences.data?.sections.find((section) => section.id === id);
  const enabled = (id: HomeSectionId) => sectionConfig(id)?.enabled === true;
  function dismissHero() {
    if (!preferences.data) return;
    savePreferences.mutate({
      sections: preferences.data.sections.map((section) =>
        section.id === "header" ? { ...section, enabled: false } : section,
      ),
    });
  }
  const { date, status } = useLocale();
  const summary = useQuery({
    queryKey: ["home-summary"],
    enabled: enabled("overview") || enabled("header"),
    queryFn: ({ signal }) =>
      homeReads.run(
        () =>
          get<{
            catalog?: { total: number; sourceModifiedAt: string | null };
            downloads?: { total: number; byStatus: Record<string, number> };
            jobs?: { byStatus: Record<string, number> };
          }>("/home/summary", signal),
        signal,
      ),
  });
  const recentBooks = useQueries({
    queries: bookSections.map((section) => ({
      queryKey: [
        "catalog",
        "home-publications",
        section.title,
        sectionConfig(section.title)?.bookCount,
      ],
      enabled: auth.can("CATALOG_READ") && enabled(section.title),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        homeReads.run(
          () =>
            get<BookPage>(
              `/catalog/books?page=0&size=${sectionConfig(section.title)?.bookCount ?? 10}&${bookSectionParams(section.status)}`,
              signal,
            ),
          signal,
        ),
    })),
  });
  const events = useQuery({
    queryKey: [
      "events",
      "home",
      sectionConfig("recentEvents")?.eventCount ?? 10,
    ],
    enabled: auth.can("EVENTS_MANAGE") && enabled("recentEvents"),
    queryFn: ({ signal }) =>
      homeReads.run(
        () =>
          get<OperationPage>(
            `/events/operations?page=0&size=${sectionConfig("recentEvents")?.eventCount ?? 10}`,
            signal,
          ),
        signal,
      ),
  });
  const empty = summary.data?.catalog?.total === 0;
  const activeCount = activeStates.reduce(
    (total, state) => total + (summary.data?.jobs?.byStatus[state] ?? 0),
    0,
  );
  if (preferences.isPending) return <Loading />;
  if (preferences.isError)
    return (
      <Failure error={preferences.error} retry={() => preferences.refetch()} />
    );
  const header = (
    <section className="hero home-hero">
      <ActionIcon
        className="home-hero-close"
        variant="subtle"
        color="gray"
        size="lg"
        aria-label={t("home.dismissIntro")}
        title={t("home.dismissIntro")}
        onClick={dismissHero}
        disabled={savePreferences.isPending}
      >
        <X size={20} />
      </ActionIcon>
      <div>
        <h1>
          {t("home.title")}
          <br />
          <span>{t("home.subtitle")}</span>
        </h1>
        <p>{t("home.description")}</p>
        <Link
          className="primary-link"
          to={
            empty && auth.can("CATALOG_IMPORT")
              ? "/settings/catalog"
              : auth.can("CATALOG_READ")
                ? "/catalog"
                : "/settings/account"
          }
        >
          {t(
            empty && auth.can("CATALOG_IMPORT") ? "home.import" : "nav.explore",
          )}{" "}
          <ArrowRight size={18} />
        </Link>
      </div>
      <div className="book-art" aria-hidden="true">
        <div className="art-book one">
          EPL<span>{t("home.art")}</span>
          <BookOpen />
        </div>
        <div className="art-book two" />
        <div className="art-book three" />
      </div>
    </section>
  );
  const overview = (
    <section className="home-stats" aria-label={t("home.overview")}>
      {auth.can("CATALOG_READ") && (
        <article className="home-panel">
          <Link className="home-panel-title" to="/catalog">
            <Library size={20} />
            <h2>{t("nav.catalog")}</h2>
            <ArrowRight size={16} />
          </Link>
          {summary.isPending ? (
            <Loading />
          ) : summary.isError ? (
            <Failure error={summary.error} retry={() => summary.refetch()} />
          ) : (
            <>
              <strong className="home-count">
                {summary.data?.catalog?.total ?? 0}
              </strong>
              <span className="muted">{t("home.booksCount")}</span>
            </>
          )}
          <p className="muted home-small">
            {t("metadata.sourceModifiedAt")}:{" "}
            {summary.data?.catalog?.sourceModifiedAt?.replace("T", " ") ?? "—"}
          </p>
        </article>
      )}
      {auth.can("TORRENT_SYNC") && (
        <article className="home-panel">
          <Link className="home-panel-title" to="/downloads">
            <Download size={20} />
            <h2>{t("nav.downloads")}</h2>
            <ArrowRight size={16} />
          </Link>
          {summary.isPending ? (
            <Loading />
          ) : summary.isError ? (
            <Failure error={summary.error} retry={() => summary.refetch()} />
          ) : (
            <>
              <strong className="home-count">
                {summary.data?.downloads?.total ?? 0}
              </strong>
              <span className="muted">{t("home.downloadsCount")}</span>
              <div className="home-statuses">
                {Object.entries(summary.data?.downloads?.byStatus ?? {})
                  .filter(([, count]) => count > 0)
                  .map(([key, count]) => (
                    <span key={key}>
                      {status(key)} <b>{count}</b>
                    </span>
                  ))}
              </div>
            </>
          )}
        </article>
      )}
      {auth.can("TORRENT_JOBS_MANAGE") && (
        <article className="home-panel">
          <Link className="home-panel-title" to="/downloads/jobs">
            <ListChecks size={20} />
            <h2>{t("nav.jobs")}</h2>
            <ArrowRight size={16} />
          </Link>
          {summary.isError ? (
            <Failure error={summary.error} retry={() => summary.refetch()} />
          ) : summary.isPending ? (
            <Loading />
          ) : (
            <>
              <strong className="home-count">{activeCount}</strong>
              <span className="muted">{t("home.activeJobs")}</span>
              {activeCount === 0 ? (
                <p className="muted home-small">{t("home.noJobs")}</p>
              ) : (
                <div className="home-statuses">
                  {activeStates.map(
                    (state) =>
                      (summary.data?.jobs?.byStatus[state] ?? 0) > 0 && (
                        <span key={state}>
                          {status(state)}{" "}
                          <b>{summary.data?.jobs?.byStatus[state] ?? 0}</b>
                        </span>
                      ),
                  )}
                </div>
              )}
            </>
          )}
        </article>
      )}
    </section>
  );
  const bookPanels = bookSections.map((section, index) => {
    const result = recentBooks[index];
    return (
      <section className="home-panel" key={section.title}>
        <div className="home-section-title">
          <h2>{t("home." + section.title)}</h2>
          <Link to={`/catalog?${bookSectionParams(section.status)}`}>
            {t("home.seeAll")} <ArrowRight size={16} />
          </Link>
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : !result.data.items.length ? (
          <p className="muted">{t("home.noRecentBooks")}</p>
        ) : (
          <div className="home-books">
            {result.data.items.map((book) => (
              <Link
                className="home-book"
                key={book.eplId}
                to={`/catalog/${book.eplId}`}
              >
                <div className="home-cover">
                  <BookCover book={book} />
                </div>
                <strong title={book.title}>{book.title}</strong>
                <span className="muted" title={book.author}>
                  {book.author}
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>
    );
  });
  const eventPanel = (
    <section className="home-panel">
      <div className="home-section-title">
        <h2>{t("home.recentEvents")}</h2>
        <Link to="/events">
          {t("home.seeAll")} <ArrowRight size={16} />
        </Link>
      </div>
      {events.isPending ? (
        <Loading />
      ) : events.isError ? (
        <Failure error={events.error} retry={() => events.refetch()} />
      ) : !events.data.items.length ? (
        <p className="muted">{t("home.noEvents")}</p>
      ) : (
        <ul className="home-events">
          {events.data.items.map(({ latest }) => (
            <li key={latest.operationId}>
              <Link
                to={`/events?operationId=${encodeURIComponent(latest.operationId)}`}
              >
                <span>
                  {t(`events.actions.${latest.action}`, {
                    defaultValue: latest.action,
                  })}
                </span>
                <Badge
                  color={
                    latest.outcome === "FAILED"
                      ? "red"
                      : latest.outcome === "SUCCEEDED"
                        ? "teal"
                        : "gray"
                  }
                >
                  {t(`events.outcomes.${operationOutcome(latest.outcome)}`)}
                </Badge>
                <time dateTime={latest.createdAt}>
                  {date(latest.createdAt)}
                </time>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
  return (
    <div className="home-dashboard">
      {savePreferences.isError && (
        <Failure error={savePreferences.error} retry={dismissHero} />
      )}
      {preferences.data.sections
        .filter((section) => section.enabled)
        .map((section) => {
          let content;
          if (section.id === "header") content = header;
          else if (section.id === "overview") content = overview;
          else if (section.id === "recentEvents")
            content = auth.can("EVENTS_MANAGE") ? eventPanel : null;
          else
            content = auth.can("CATALOG_READ")
              ? bookPanels[
                  bookSections.findIndex((item) => item.title === section.id)
                ]
              : null;
          return <Fragment key={section.id}>{content}</Fragment>;
        })}
    </div>
  );
}
