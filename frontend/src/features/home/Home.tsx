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
import { type Metadata } from "../maintenance/CatalogMetadata";
import { type Page, type Job } from "../downloads/shared";
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
  const books = useQuery({
    queryKey: ["catalog", "home-total"],
    enabled:
      auth.can("CATALOG_READ") && (enabled("overview") || enabled("header")),
    queryFn: ({ signal }) =>
      get<BookPage>("/catalog/books?page=0&size=1", signal),
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
        get<BookPage>(
          `/catalog/books?page=0&size=${sectionConfig(section.title)?.bookCount ?? 10}&${bookSectionParams(section.status)}`,
          signal,
        ),
    })),
  });
  const metadata = useQuery({
    queryKey: ["catalog-metadata"],
    enabled: auth.can("CATALOG_READ") && enabled("overview"),
    queryFn: ({ signal }) =>
      get<{ metadata: Metadata | null }>("/catalog/import/metadata", signal),
  });
  const downloads = useQuery({
    queryKey: ["download-summary", ""],
    enabled: auth.can("TORRENT_SYNC") && enabled("overview"),
    queryFn: ({ signal }) =>
      get<{ total: number; byStatus: Record<string, number> }>(
        "/torrent/downloads/summary",
        signal,
      ),
  });
  const jobs = useQueries({
    queries: activeStates.map((state) => ({
      queryKey: ["jobs", "home", state],
      enabled: auth.can("TORRENT_JOBS_MANAGE") && enabled("overview"),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        get<Page<Job>>(`/torrent/jobs?page=0&size=1&status=${state}`, signal),
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
      get<OperationPage>(
        `/events/operations?page=0&size=${sectionConfig("recentEvents")?.eventCount ?? 10}`,
        signal,
      ),
  });
  const empty = books.data?.meta.totalItems === 0;
  const jobError = jobs.find((query) => query.isError);
  const jobsLoaded = jobs.every((query) => query.data);
  const activeCount = jobs.reduce(
    (total, query) => total + (query.data?.meta.totalItems ?? 0),
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
          {books.isPending ? (
            <Loading />
          ) : books.isError ? (
            <Failure error={books.error} retry={() => books.refetch()} />
          ) : (
            <>
              <strong className="home-count">
                {books.data.meta.totalItems}
              </strong>
              <span className="muted">{t("home.booksCount")}</span>
            </>
          )}
          <p className="muted home-small">
            {t("metadata.sourceModifiedAt")}:{" "}
            {metadata.data?.metadata?.sourceModifiedAt?.replace("T", " ") ??
              "—"}
          </p>
          {metadata.isError && (
            <Failure error={metadata.error} retry={() => metadata.refetch()} />
          )}
        </article>
      )}
      {auth.can("TORRENT_SYNC") && (
        <article className="home-panel">
          <Link className="home-panel-title" to="/downloads">
            <Download size={20} />
            <h2>{t("nav.downloads")}</h2>
            <ArrowRight size={16} />
          </Link>
          {downloads.isPending ? (
            <Loading />
          ) : downloads.isError ? (
            <Failure
              error={downloads.error}
              retry={() => downloads.refetch()}
            />
          ) : (
            <>
              <strong className="home-count">{downloads.data.total}</strong>
              <span className="muted">{t("home.downloadsCount")}</span>
              <div className="home-statuses">
                {Object.entries(downloads.data.byStatus)
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
          {jobError ? (
            <Failure
              error={jobError.error}
              retry={() => {
                jobs.forEach((query) => void query.refetch());
              }}
            />
          ) : !jobsLoaded ? (
            <Loading />
          ) : (
            <>
              <strong className="home-count">{activeCount}</strong>
              <span className="muted">{t("home.activeJobs")}</span>
              {activeCount === 0 ? (
                <p className="muted home-small">{t("home.noJobs")}</p>
              ) : (
                <div className="home-statuses">
                  {jobs.map(
                    (query, i) =>
                      query.data!.meta.totalItems > 0 && (
                        <span key={activeStates[i]}>
                          {status(activeStates[i])}{" "}
                          <b>{query.data!.meta.totalItems}</b>
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
