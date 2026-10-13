import { TableAction } from "../../components/TableAction";
import { AppModal } from "../../components/AppModal";
import { Button } from "@mantine/core";
import { Download } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Failure } from "../../components/Feedback";
import { post } from "./shared";

export function EpubBatchDownload({
  selected,
  endpoint = "/torrent/downloads/epub-batches",
  inline = false,
  toolbar = true,
  selectionLabel = "historyActions.selected",
}: {
  selected: (string | number)[];
  endpoint?: string;
  inline?: boolean;
  toolbar?: boolean;
  selectionLabel?: string;
}) {
  const { t } = useTranslation();
  const batch = useMutation({
    retry: false,
    mutationFn: (ids: (string | number)[]) =>
      post<{ url: string }>(endpoint, { ids }),
    onSuccess: ({ url }) => {
      const link = document.createElement("a");
      link.href = url;
      document.body.appendChild(link);
      link.click();
      link.remove();
    },
  });
  if (!selected.length) return null;
  const button = inline ? (
    <TableAction
      label={t("epubDownload.button")}
      loading={batch.isPending}
      onClick={() => batch.mutate(selected)}
    >
      <Download size={16} aria-hidden="true" />
    </TableAction>
  ) : (
    <Button
      variant="filled"
      size="sm"
      leftSection={<Download size={16} />}
      loading={batch.isPending}
      onClick={() => batch.mutate(selected)}
    >
      {t("epubDownload.download")}
    </Button>
  );
  return (
    <>
      {inline || !toolbar ? (
        button
      ) : (
        <div className="catalog-selection-bar has-selection">
          <div className="catalog-selection-info">
            <span className="catalog-selection-count" aria-live="polite">
              {t(selectionLabel, { count: selected.length })}
            </span>
          </div>
          <div className="catalog-selection-actions">{button}</div>
        </div>
      )}
      {batch.isError &&
        batch.variables?.join("|") === selected.join("|") &&
        (inline || !toolbar ? (
          <AppModal
            opened
            onClose={() => batch.reset()}
            title={t("epubDownload.title")}
            icon={Download}
          >
            <Failure error={batch.error} retry={() => batch.mutate(selected)} />
          </AppModal>
        ) : (
          <Failure error={batch.error} retry={() => batch.mutate(selected)} />
        ))}
    </>
  );
}
