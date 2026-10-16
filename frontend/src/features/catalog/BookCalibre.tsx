import { SelectionToolbar } from "../../components/SelectionToolbar";
import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { useAuth } from "../auth/Auth";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { useEffect, useState } from "react";
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

const excludedFields = [
  "downloadAvailability",
  "eplId",
  "title",
  "authors",
  "presence",
];

export function BookCalibre({ book }: { book: Book }) {
  const [sort, setSort] = useState("revision,desc"),
    [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const selectable = useAuth().can("CALIBRE_FILES_READ");
  const [selected, setSelected] = useState<number[]>([]);
  useEffect(() => setSelected([]), [book.eplId, page, size, sort]);
  const pageSizeChoice = usePageSizeChoice(size);
  const columns = useDownloadColumns(
    "eplsync.book-calibre.columns",
    calibreColumns.filter((column) => !excludedFields.includes(column.field)),
    sort,
    (v) => {
      setSort(v);
      setPage(0);
    },
    {
      defaults: "revision,desc",
      selection: selectable,
    }, (page + 1) * size,
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
  useEffect(() => {
    if (result.data)
      setSelected((previous) =>
        previous.filter((id) =>
          result.data.items.some((item) => item.calibreId === id),
        ),
      );
  }, [result.data]);
  return (
    <section className="panel book-calibre">
      <div className="book-section-heading">
        <h2>Calibre</h2>
      </div>
      {result.data && result.data.meta.totalItems > 0 && (
        <SelectionToolbar
          count={selected.length}
          onClear={() => setSelected([])}
          controls={columns.controls}
          actions={
            selectable && (
              <EpubBatchDownload
                selected={selected}
                endpoint="/calibre/epub-downloads"
                toolbar={false}
              />
            )
          }
        />
      )}
      {!!book.calibre &&
        book.calibre.totalItems > 0 &&
        (result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : result.data.meta.totalItems > 0 ? (
          <>
            <CalibreTable
              offset={page * size}
              items={result.data.items}
              columns={columns}
              selected={selectable ? selected : undefined}
              onSelection={setSelected}
              showCovers={false}
              excludedFields={excludedFields}
            />
            <Paging
              choice={pageSizeChoice}
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
