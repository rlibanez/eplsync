import { CalibreSendProvider, SendToCalibre } from "../calibre/SendToCalibre";
import { Group } from "@mantine/core";
import { EpubDownload } from "../downloads/EpubDownload";
import { usePageSizeChoice } from "../../components/PageSizeSelect";
import { DownloadActions } from "../downloads/DownloadActions";
import { useAuth } from "../auth/Auth";
import { Checkbox } from "@mantine/core";
import { useDownloadColumns } from "../downloads/DownloadTable";
import { sortQuery } from "../downloads/TableControls";
import type { Download } from "../downloads/DownloadState";
import { useState, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, type Page } from "../downloads/shared";

type HistoryItem = Pick<
  Download,
  | "id"
  | "revision"
  | "hash"
  | "status"
  | "client"
  | "clientInstanceId"
  | "origin"
  | "lastCheckedAt"
  | "completedAt"
  | "lastError"
>;

export function BookHistory({ id }: { id: number }) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const selectable =
    (can("CALIBRE_SEND") && can("DOWNLOADS_FILES_READ")) ||
    can("TORRENT_SYNC") ||
    can("TORRENT_CLEANUP") ||
    can("DOWNLOADS_DELETE");
  const [selected, setSelected] = useState<string[]>([]);
  const { status, date, revision } = useLocale();
  const [sort, setSort] = useState("revision,desc");
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const pageSizeChoice = usePageSizeChoice(size);
  const columns = useDownloadColumns(
    "eplsync.history.columns.v2",
    [
      { field: "revision", label: "downloads.revision", width: 100 },
      { field: "status", label: "downloads.status", width: 160 },
      { field: "client", label: "downloads.client", width: 160 },
      { field: "lastCheckedAt", label: "downloads.lastChecked", width: 200 },
      { field: "completedAt", label: "downloads.completed", width: 220 },
      { field: "hash", label: "downloads.hash", width: 360 },
      { field: "lastError", label: "downloads.error", width: 250 },
      {
        field: "actions",
        label: "historyActions.actions",
        width: 120,
        sortable: false,
      },
    ],
    sort,
    (value) => {
      setSort(value);
      setPage(0);
    },
    {
      defaults: "revision,desc",
      selection: selectable,
      hidden: can("DOWNLOADS_FILES_READ") ? [] : ["actions"],
    },
  );
  const result = useQuery<Page<HistoryItem>>({
    queryKey: ["book-history", id, page, size, sort],
    placeholderData: (previous, query) =>
      query?.queryKey[1] === id ? previous : undefined,
    queryFn: ({ signal }) =>
      get<Page<HistoryItem>>(
        `/catalog/books/${id}/history?page=${page}&size=${size}&${sortQuery(sort)}`,
        signal,
      ),
  });
  useEffect(() => setSelected([]), [page, size, sort, id]);
  useEffect(() => {
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
  }, [result.data]);
  return (
    <CalibreSendProvider>
      <section className="panel book-history">
        <div className="table-toolbar book-history-toolbar">
          <h2>{t("detail.history")}</h2>
          {result.data && result.data.meta.totalItems > 0 && (
            <div className="catalog-table-controls">{columns.controls}</div>
          )}
        </div>
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : result.data.meta.totalItems === 0 ? null : (
          <>
            <DownloadActions
              eplId={id}
              selected={selected}
              rows={result.data.items}
              onDone={() => setSelected([])}
              extraActions={<SendToCalibre ids={selected} />}
            />
            <div className="table-scroll">
              <table
                className="download-record-table"
                style={{ width: columns.width }}
              >
                {columns.colgroup}
                <caption className="sr-only">{t("detail.history")}</caption>
                <thead>
                  {columns.selectionHeadings(
                    selectable && (
                      <th className="history-selection">
                        <Checkbox
                          aria-label={t("historyActions.selectPage")}
                          checked={
                            result.data.items.length > 0 &&
                            result.data.items.every((r) =>
                              selected.includes(r.id),
                            )
                          }
                          indeterminate={
                            selected.length > 0 &&
                            !result.data.items.every((r) =>
                              selected.includes(r.id),
                            )
                          }
                          disabled={!result.data.items.length}
                          onChange={(e) =>
                            setSelected(
                              e.currentTarget.checked
                                ? result.data.items.map((r) => r.id)
                                : [],
                            )
                          }
                        />
                      </th>
                    ),
                  )}
                </thead>
                <tbody>
                  {result.data.items.map((item) => (
                    <tr key={item.id}>
                      {selectable && (
                        <td className="history-selection">
                          <Checkbox
                            aria-label={t("historyActions.selectRevision", {
                              revision: revision(item.revision),
                            })}
                            checked={selected.includes(item.id)}
                            onChange={(e) => {
                              const checked = e.currentTarget.checked;
                              setSelected((previous) =>
                                checked
                                  ? [...previous, item.id]
                                  : previous.filter((id) => id !== item.id),
                              );
                            }}
                          />
                        </td>
                      )}
                      {columns.cells([
                        <td>{revision(item.revision)}</td>,
                        <td>
                          <span className="badge">{status(item.status)}</span>
                        </td>,
                        <td title={item.clientInstanceId}>
                          {item.client || "—"}
                          <small className="hash-text">{item.origin}</small>
                        </td>,
                        <td>{date(item.lastCheckedAt)}</td>,
                        <td>{date(item.completedAt)}</td>,
                        <td>
                          <small className="hash-text" title={item.hash}>
                            {item.hash || "—"}
                          </small>
                        </td>,
                        <td>{item.lastError || "—"}</td>,
                        <td>
                          <Group gap={4} wrap="nowrap">
                            <EpubDownload id={item.id} />
                            <SendToCalibre ids={[item.id]} inline />
                          </Group>
                        </td>,
                      ])}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Paging
              choice={pageSizeChoice}
              meta={result.data.meta}
              page={page}
              size={size}
              onPage={setPage}
              onSize={(value) => {
                setSize(value);
                setPage(0);
              }}
            />
          </>
        )}
      </section>
    </CalibreSendProvider>
  );
}
