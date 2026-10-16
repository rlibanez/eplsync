import { TableAction } from "../../components/TableAction";
import { Checkbox, Group } from "@mantine/core";
import { EpubBatchDownload } from "../downloads/EpubBatchDownload";
import { BookOpen, ExternalLink } from "lucide-react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { BookCover } from "../catalog/BookCover";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";
import {
  useDownloadColumns,
  type TableColumn,
} from "../downloads/DownloadTable";
export interface CalibreBook {
  downloadAvailability?: string;
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
export const calibreColumns: TableColumn[] = [
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
  {
    field: "actions",
    label: "historyActions.actions",
    width: 110,
    sortable: false,
  },
];
calibreColumns.push({
  field: "downloadAvailability",
  label: "calibreUpdateSend.availability",
  width: 210,
});
export function CalibreTable({
  items,
  offset = 0,
  columns,
  emptyMessage,
  showCovers = true,
  excludedFields = ["downloadAvailability"],
  selected,
  onSelection,
}: {
  items: CalibreBook[];
  offset?: number;
  emptyMessage?: string;
  selected?: number[];
  onSelection?: (ids: number[]) => void;
  showCovers?: boolean;
  excludedFields?: string[];
  columns: ReturnType<typeof useDownloadColumns>;
}) {
  const { t } = useTranslation();
  const auth = useAuth();
  const { revision } = useLocale();
  const selectable =
    (auth.can("CALIBRE_FILES_READ") || auth.can("CALIBRE_SEND")) &&
    selected !== undefined &&
    !!onSelection;
  return (
    <div className="table-scroll">
      <table className="download-record-table" style={{ width: columns.width }}>
        {columns.colgroup}
        <thead>
          {columns.selectionHeadings(
            selectable && (
              <th className="history-selection">
                <Checkbox
                  aria-label={t("historyActions.selectPage")}
                  disabled={!items.length}
                  checked={
                    items.length > 0 &&
                    items.every((b) => selected!.includes(b.calibreId))
                  }
                  indeterminate={
                    selected!.length > 0 &&
                    !items.every((b) => selected!.includes(b.calibreId))
                  }
                  onChange={(e) =>
                    onSelection!(
                      e.currentTarget.checked
                        ? items.map((b) => b.calibreId)
                        : [],
                    )
                  }
                />
              </th>
            ),
          )}
        </thead>
        <tbody>
          {items.length === 0 && emptyMessage && (
            <tr>
              <td colSpan={columns.visible.length + (selectable ? 1 : 0)}>
                <p className="empty-list">{emptyMessage}</p>
              </td>
            </tr>
          )}
          {items.map((book, rowIndex) => (
            <tr key={book.calibreId}>
              {selectable && (
                <td className="history-selection">
                  <Checkbox
                    aria-label={t("calibre.selectBook", { id: book.calibreId })}
                    checked={selected!.includes(book.calibreId)}
                    onChange={(e) =>
                      onSelection!(
                        e.currentTarget.checked
                          ? [...selected!, book.calibreId]
                          : selected!.filter((id) => id !== book.calibreId),
                      )
                    }
                  />
                </td>
              )}
              {columns.cells(
                [
                  <td>
                    <a
                      href={`/api/calibre/books/${book.calibreId}/open`}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {book.calibreId}
                    </a>
                  </td>,
                  <td>
                    {book.eplId ? (
                      book.presence === "PRESENT" &&
                      auth.can("CATALOG_READ") ? (
                        <Link to={`/catalog/${book.eplId}`}>{book.eplId}</Link>
                      ) : (
                        book.eplId
                      )
                    ) : (
                      "—"
                    )}
                  </td>,
                  <td className={showCovers ? "catalog-title-cell" : undefined}>
                    {book.presence === "PRESENT" && book.eplId ? (
                      auth.can("CATALOG_READ") ? (
                        <Link
                          className="book-title"
                          to={`/catalog/${book.eplId}`}
                        >
                          {showCovers && (
                            <BookCover
                              book={{
                                eplId: book.eplId,
                                title: book.title,
                                coverUrl: book.coverUrl,
                                coverAvailable: book.coverAvailable,
                              }}
                            />
                          )}
                          <span>{book.title || "—"}</span>
                        </Link>
                      ) : (
                        <span className="book-title">
                          {showCovers && (
                            <BookCover
                              book={{
                                eplId: book.eplId,
                                title: book.title,
                                coverUrl: book.coverUrl,
                                coverAvailable: book.coverAvailable,
                              }}
                            />
                          )}
                          <span>{book.title || "—"}</span>
                        </span>
                      )
                    ) : (
                      <span className="book-title">
                        {showCovers && (
                          <span
                            className="mini-book book-cover"
                            aria-hidden="true"
                          >
                            <BookOpen size={18} />
                          </span>
                        )}
                        <span>{book.title || "—"}</span>
                      </span>
                    )}
                  </td>,
                  <td>{book.authors || "—"}</td>,
                  <td>
                    {book.revision == null ? "—" : revision(book.revision)}
                  </td>,
                  <td>
                    {book.catalogRevision == null
                      ? "—"
                      : revision(book.catalogRevision)}
                  </td>,
                  <td>{t(`calibre.filter.${book.comparison}`)}</td>,
                  <td>{t(`calibre.presenceValues.${book.presence}`)}</td>,
                  <td>
                    <Group gap={4} wrap="nowrap">
                      {auth.can("CALIBRE_FILES_READ") && (
                        <EpubBatchDownload
                          selected={[book.calibreId]}
                          endpoint="/calibre/epub-downloads"
                          inline
                        />
                      )}
                      <TableAction
                        label={t("calibre.open")}
                        href={`/api/calibre/books/${book.calibreId}/open`}
                      >
                        <ExternalLink size={16} aria-hidden="true" />
                      </TableAction>
                    </Group>
                  </td>,
                  <td>
                    {t(
                      `calibreUpdateSend.availabilityValues.${book.downloadAvailability ?? "UNKNOWN"}`,
                    )}
                  </td>,
                ].filter(
                  (_, index) =>
                    !excludedFields.includes(calibreColumns[index].field),
                ), offset + rowIndex + 1
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
