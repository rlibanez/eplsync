import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Alert } from "@mantine/core";
import { Files } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import { useLocale } from "../../locales/useLocale";
import { get } from "../../api/catalog";
import { Paging, type Page } from "../downloads/shared";
import { usePageSizeChoice } from "../../components/PageSizeSelect";

type InventoryFile = {
  index: number;
  name: string;
  extension: string;
  size: number;
};
type Inventory = Page<InventoryFile> & {
  inventoryState: string;
  inventoryCheckedAt: string | null;
};

export function DownloadInventory({
  eplId,
  recordId,
  onClose,
}: {
  eplId: number;
  recordId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const { number, date } = useLocale();
  const [page, setPage] = useState(0),
    [size, setSize] = useState(20);
  const choice = usePageSizeChoice(size);
  const result = useQuery({
    queryKey: ["book-download-inventory", eplId, recordId, page, size],
    queryFn: ({ signal }) =>
      get<Inventory>(
        `/catalog/books/${eplId}/history/${encodeURIComponent(recordId)}/files?page=${page}&size=${size}`,
        signal,
      ),
    retry: false,
    refetchInterval: (query) =>
      query.state.status !== "error" &&
      query.state.data &&
      query.state.data.inventoryState !== "CHECKED"
        ? 30000
        : false,
  });
  useEffect(() => {
    if (result.data)
      setPage((current) =>
        Math.min(current, Math.max(0, result.data.meta.totalPages - 1)),
      );
  }, [result.data]);
  function fileSize(bytes: number) {
    const unit = Math.min(
      3,
      Math.floor(Math.log(Math.max(1, bytes)) / Math.log(1024)),
    );
    return `${number(bytes / 1024 ** unit, { maximumFractionDigits: unit ? 2 : 0 })} ${["B", "KiB", "MiB", "GiB"][unit]}`;
  }
  return (
    <AppModal
      opened
      onClose={onClose}
      title={t("downloadInventory.title")}
      icon={Files}
      width="wide"
    >
      <p>{t("downloadInventory.help")}</p>
      {result.isPending ? (
        <Loading />
      ) : result.isError ? (
        <Failure error={result.error} retry={() => result.refetch()} />
      ) : (
        <>
          {result.data.inventoryState !== "CHECKED" && (
            <Alert
              color={result.data.inventoryState === "ERROR" ? "orange" : "gray"}
              role="status"
            >
              {t(
                result.data.inventoryState === "ERROR"
                  ? result.data.meta.totalItems
                    ? "downloadInventory.errorCached"
                    : "downloads.inventoryError"
                  : "downloads.inventoryUnknown",
              )}
            </Alert>
          )}
          {result.data.meta.totalItems > 0 ? (
            <fieldset className="cleanup-preview">
              <legend>{t("downloadInventory.files")}</legend>
              <div className="table-scroll revision-cleanup-table">
                <table className="download-record-table">
                  <caption className="sr-only">
                    {t("downloadInventory.files")}
                  </caption>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>{t("downloadInventory.name")}</th>
                      <th>{t("downloads.extension")}</th>
                      <th>{t("downloadInventory.size")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.data.items.map((file) => (
                      <tr key={file.index}>
                        <td>{file.index + 1}</td>
                        <td
                          style={{ maxWidth: "none", overflowWrap: "anywhere" }}
                        >
                          {file.name}
                        </td>
                        <td>
                          {file.extension.toUpperCase() ||
                            t("downloads.noExtension")}
                        </td>
                        <td
                          title={`${number(file.size)} B`}
                          style={{ whiteSpace: "nowrap" }}
                        >
                          {fileSize(file.size)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paging
                choice={choice}
                meta={result.data.meta}
                page={page}
                size={size}
                onPage={setPage}
                onSize={(value) => {
                  setSize(value);
                  setPage(0);
                }}
              />
            </fieldset>
          ) : result.data.inventoryState === "CHECKED" ? (
            <p>{t("downloadInventory.empty")}</p>
          ) : null}
        </>
      )}
      <ModalActions
        summary={
          result.data?.inventoryCheckedAt ? (
            <span className="muted">
              {t("downloadInventory.checked", {
                date: date(result.data.inventoryCheckedAt),
              })}
            </span>
          ) : undefined
        }
      >
        <Button variant="default" onClick={onClose}>
          {t("common.close")}
        </Button>
      </ModalActions>
    </AppModal>
  );
}
