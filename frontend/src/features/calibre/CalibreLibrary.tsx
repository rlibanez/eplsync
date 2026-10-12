import { BookOpen } from "lucide-react";
import { BookCover } from "../catalog/BookCover";
import { useNotifications } from "../notifications/Notifications";
import { useState } from "react";
import { Button, Select, TextInput } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import { Paging, post, type Page } from "../downloads/shared";
interface Book {
  calibreId: number;
  title: string;
  authors: string;
  eplId: number | null;
  revision: number | null;
  catalogRevision: number | null;
  comparison: string;
  presence: string;
  coverUrl: string | null;
  coverAvailable: boolean | null;
}
interface Snapshot {
  library: string;
  syncedAt: string;
  total: number;
  identified: number;
}
export function CalibreLibrary() {
  const { t } = useTranslation();
  const auth = useAuth();
  const { notify } = useNotifications();
  const { date, number } = useLocale();
  const cache = useQueryClient();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20),
    [title, setTitle] = useState(""),
    [author, setAuthor] = useState(""),
    [eplId, setEplId] = useState(""),
    [comparison, setComparison] = useState(""),
    [presence, setPresence] = useState(""),
    [sort, setSort] = useState("title,asc");
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    title,
    author,
    ...(eplId ? { eplId } : {}),
    comparison,
    presence,
  });
  const result = useQuery({
    queryKey: ["calibre-library", params.toString(), sort],
    queryFn: ({ signal }) =>
      get<Page<Book>>(`/calibre/books?${params}&${sortQuery(sort)}`, signal),
  });
  const snapshot = useQuery({
    queryKey: ["calibre-snapshot"],
    queryFn: ({ signal }) =>
      get<{ snapshot: Snapshot | null }>(
        "/settings/calibre/snapshot",
        signal,
      ).then((d) => d.snapshot),
  });
  const sync = useMutation({
    mutationFn: () => post<Snapshot>("/settings/calibre/sync", {}),
    retry: false,
    onSuccess: (data) => {
      cache.setQueryData(["calibre-snapshot"], data);
      void cache.invalidateQueries({ queryKey: ["calibre-library"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      setPage(0);
      notify({
        title: "Calibre",
        message: t("calibre.synced"),
        tone: "success",
      });
    },
    onError: (error) =>
      notify({
        title: "Calibre",
        message:
          (error as { details?: string }).details || t("errors.unexpected"),
        tone: "error",
      }),
  });
  const columns = useDownloadColumns(
    "eplsync.calibre.columns",
    [
      { field: "calibreId", label: "calibre.id", width: 120 },
      { field: "eplId", label: "filters.eplId", width: 120 },
      { field: "title", label: "downloads.book", width: 360 },
      { field: "authors", label: "filters.author", width: 260 },
      { field: "revision", label: "calibre.libraryRevision", width: 160 },
      {
        field: "catalogRevision",
        label: "calibre.catalogRevision",
        width: 180,
      },
      { field: "comparison", label: "calibre.comparison", width: 180 },
      { field: "presence", label: "calibre.presence", width: 170 },
    ],
    sort,
    (v) => {
      setSort(v);
      setPage(0);
    },
    { defaults: "title,asc" },
  );
  return (
    <>
      <div className="page-heading">
        <h1>{t("calibre.libraryHeading")}</h1>
        {auth.can("CALIBRE_SYNC") && (
          <Button loading={sync.isPending} onClick={() => sync.mutate()}>
            {t("calibre.sync")}
          </Button>
        )}
      </div>
      <p className="muted">{t("calibre.libraryDescription")}</p>
      {snapshot.data ? (
        <p>
          {t("calibre.library")}: {snapshot.data.library} ·{" "}
          {t("calibre.lastSync")}: {date(snapshot.data.syncedAt)}
        </p>
      ) : (
        !snapshot.isPending &&
        !snapshot.isError && <p>{t("calibre.notSynced")}</p>
      )}
      {snapshot.isError && (
        <Failure error={snapshot.error} retry={() => snapshot.refetch()} />
      )}
      <div className="filters jobs-filters">
        <TextInput
          label={t("filters.eplId")}
          value={eplId}
          inputMode="numeric"
          onChange={(e) => {
            setEplId(e.currentTarget.value.replace(/\D/g, ""));
            setPage(0);
          }}
        />
        <TextInput
          label={t("downloads.book")}
          value={title}
          maxLength={256}
          onChange={(e) => {
            setTitle(e.currentTarget.value);
            setPage(0);
          }}
        />
        <TextInput
          label={t("filters.author")}
          value={author}
          maxLength={256}
          onChange={(e) => {
            setAuthor(e.currentTarget.value);
            setPage(0);
          }}
        />
        <Select
          label={t("calibre.comparison")}
          clearable
          value={comparison || null}
          data={["CURRENT", "OUTDATED", "NEWER", "UNKNOWN"].map((value) => ({
            value,
            label: t(`calibre.filter.${value}`),
          }))}
          onChange={(v) => {
            setComparison(v ?? "");
            setPage(0);
          }}
        />
        <Select
          label={t("calibre.presence")}
          clearable
          value={presence || null}
          data={["PRESENT", "MISSING", "UNIDENTIFIED"].map((value) => ({
            value,
            label: t(`calibre.presenceValues.${value}`),
          }))}
          onChange={(v) => {
            setPresence(v ?? "");
            setPage(0);
          }}
        />
        <Button
          variant="subtle"
          onClick={() => {
            setTitle("");
            setAuthor("");
            setEplId("");
            setComparison("");
            setPresence("");
            setPage(0);
          }}
        >
          {t("catalog.clear")}
        </Button>
      </div>
      <section className="panel">
        <div className="table-toolbar">
          <div className="catalog-table-controls">{columns.controls}</div>
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <>
            <div className="table-scroll">
              <table
                className="download-record-table"
                style={{ width: columns.width }}
              >
                {columns.colgroup}
                <thead>{columns.headings}</thead>
                <tbody>
                  {result.data.items.map((book) => (
                    <tr key={book.calibreId}>
                      {columns.cells([
                        <td>{book.calibreId}</td>,
                        <td>
                          {book.eplId ? (
                            book.presence === "PRESENT" &&
                            auth.can("CATALOG_READ") ? (
                              <Link to={`/catalog/${book.eplId}`}>
                                {book.eplId}
                              </Link>
                            ) : (
                              book.eplId
                            )
                          ) : (
                            "—"
                          )}
                        </td>,
                        <td>
                          {book.presence === "PRESENT" && book.eplId ? (
                            auth.can("CATALOG_READ") ? (
                              <Link
                                className="book-title"
                                to={`/catalog/${book.eplId}`}
                              >
                                <BookCover
                                  book={{
                                    eplId: book.eplId,
                                    title: book.title,
                                    coverUrl: book.coverUrl,
                                    coverAvailable: book.coverAvailable,
                                  }}
                                />
                                <span>{book.title || "—"}</span>
                              </Link>
                            ) : (
                              <span className="book-title">
                                <BookCover
                                  book={{
                                    eplId: book.eplId,
                                    title: book.title,
                                    coverUrl: book.coverUrl,
                                    coverAvailable: book.coverAvailable,
                                  }}
                                />
                                <span>{book.title || "—"}</span>
                              </span>
                            )
                          ) : (
                            <span className="book-title">
                              <span
                                className="mini-book book-cover"
                                aria-hidden="true"
                              >
                                <BookOpen size={18} />
                              </span>
                              <span>{book.title || "—"}</span>
                            </span>
                          )}
                        </td>,
                        <td>{book.authors || "—"}</td>,
                        <td>
                          {book.revision == null ? "—" : number(book.revision)}
                        </td>,
                        <td>
                          {book.catalogRevision == null
                            ? "—"
                            : number(book.catalogRevision)}
                        </td>,
                        <td>{t(`calibre.filter.${book.comparison}`)}</td>,
                        <td>{t(`calibre.presenceValues.${book.presence}`)}</td>,
                      ])}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Paging
              meta={result.data.meta}
              page={page}
              size={size}
              maxSize={1000}
              onPage={setPage}
              onSize={(v) => {
                setSize(v);
                setPage(0);
              }}
            />
          </>
        )}
      </section>
    </>
  );
}
