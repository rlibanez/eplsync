import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { get, type Book } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import { Paging, type Page } from "../downloads/shared";
import {
  CalibreTable,
  calibreColumns,
  type CalibreBook,
} from "../calibre/CalibreTable";

export function BookCalibre({ book }: { book: Book }) {
  const [sort, setSort] = useState("revision,desc"),
    [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const columns = useDownloadColumns(
    "eplsync.book-calibre.columns",
    calibreColumns,
    sort,
    (v) => {
      setSort(v);
      setPage(0);
    },
    { defaults: "revision,desc" },
  );
  const result = useQuery<Page<CalibreBook>>({
    queryKey: ["calibre-library", book.eplId, page, size, sort],
    placeholderData: (previous, query) =>
      query?.queryKey[1] === book.eplId ? previous : undefined,
    queryFn: ({ signal }) =>
      get<Page<CalibreBook>>(
        `/calibre/books?eplId=${book.eplId}&page=${page}&size=${size}&${sortQuery(sort)}`,
        signal,
      ),
    enabled: !!book.calibre && book.calibre.totalItems > 0,
  });
  return (
    <section className="panel book-calibre">
      <div className="table-toolbar book-history-toolbar">
        <h2>Calibre</h2>
        {result.data && result.data.meta.totalItems > 0 && (
          <div className="catalog-table-controls">{columns.controls}</div>
        )}
      </div>
      {!!book.calibre &&
        book.calibre.totalItems > 0 &&
        (result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : result.data.meta.totalItems > 0 ? (
          <>
            <CalibreTable items={result.data.items} columns={columns} />
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
        ) : null)}
    </section>
  );
}
