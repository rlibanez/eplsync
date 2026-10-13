import { Button } from "@mantine/core";
import { Download } from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Failure } from "../../components/Feedback";
import { post } from "./shared";

export function EpubBatchDownload({ selected }: { selected: string[] }) {
  const { t } = useTranslation();
  const batch = useMutation({
    retry: false,
    mutationFn: (ids: string[]) =>
      post<{ url: string }>("/torrent/downloads/epub-batches", { ids }),
    onSuccess: ({ url }) => {
      const link = document.createElement("a");
      link.href = url;
      document.body.appendChild(link);
      link.click();
      link.remove();
    },
  });
  if (!selected.length) return null;
  return (
    <>
      <div className="catalog-selection-bar has-selection">
        <div className="catalog-selection-info">
          <span className="catalog-selection-count" aria-live="polite">
            {t("historyActions.selected", { count: selected.length })}
          </span>
        </div>
        <div className="catalog-selection-actions">
          <Button
            leftSection={<Download size={16} />}
            loading={batch.isPending}
            onClick={() => batch.mutate(selected)}
          >
            {t("epubDownload.download")}
          </Button>
        </div>
      </div>
      {batch.isError && batch.variables?.join("|") === selected.join("|") && (
        <Failure error={batch.error} retry={() => batch.mutate(selected)} />
      )}
    </>
  );
}
