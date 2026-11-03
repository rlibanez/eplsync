import { useState } from "react";
import { Button, Checkbox, Select, Textarea, Alert } from "@mantine/core";
import { SearchCheck } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";

type File = { recordId: string; index: number; name: string; size: number };
type Preview = { token: string; files: File[]; problem: string | null };
export function ResolveCalibreItem({
  job,
  item,
  name,
}: {
  job: string;
  item: string;
  name: string;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [action, setAction] = useState<string | null>("retry");
  const [file, setFile] = useState<string | null>(null);
  const [reason, setReason] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const url = `/calibre/transfers/${job}/items/${item}/resolution`;
  const preview = useQuery({
    queryKey: ["calibre-item-resolution", job, item],
    queryFn: ({ signal }) => get<Preview>(url, signal),
    enabled: opened,
    retry: false,
    staleTime: 0,
  });
  const mutation = useMutation({
    mutationFn: () => {
      const selected = preview.data?.files.find(
        (f) => `${f.recordId}:${f.index}` === file,
      );
      return post(url, {
        token: preview.data?.token,
        action,
        ...selected,
        reason,
        note,
        confirm: confirmed,
      });
    },
    onSuccess: () => {
      setOpened(false);
      for (const key of [
        "calibre-job",
        "calibre-job-items",
        "calibre-jobs",
        "job-retries",
        "job-retry-history",
        "job-retry-attempts",
        "job-retry-attempts-detail",
        "book-activity",
        "home-attention",
      ])
        void cache.invalidateQueries({ queryKey: [key] });
    },
  });
  return (
    <>
      <button
        type="button"
        className="table-filter-link"
        onClick={() => {
          setOpened(true);
          setAction("retry");
          setFile(null);
          setReason(null);
          setNote("");
          setConfirmed(false);
          mutation.reset();
        }}
      >
        {t("calibreItemResolution.title")}
      </button>
      <AppModal
        icon={SearchCheck}
        title={t("calibreItemResolution.title")}
        opened={opened}
        onClose={() => {
          if (!mutation.isPending) setOpened(false);
        }}
        closeOnEscape={!mutation.isPending}
        closeOnClickOutside={!mutation.isPending}
      >
        <p>
          <strong>{name}</strong>
        </p>
        <Select
          label={t("revisionCleanup.action")}
          value={action}
          disabled={mutation.isPending}
          onChange={(v) => {
            setAction(v);
            setConfirmed(false);
            mutation.reset();
          }}
          data={[
            { value: "retry", label: t("calibreItemResolution.retry") },
            { value: "omit", label: t("calibreItemResolution.omit") },
          ]}
        />
        {preview.isPending ? (
          <Loading />
        ) : preview.isError ? (
          <Failure error={preview.error} retry={() => preview.refetch()} />
        ) : (
          <>
            {action === "retry" ? (
              <>
                {preview.data.problem && (
                  <Alert mt="md" color="yellow">
                    {preview.data.problem}
                  </Alert>
                )}
                <Select
                  mt="md"
                  label={t("calibreItemResolution.epub")}
                  value={file}
                  searchable
                  disabled={mutation.isPending || !preview.data.files.length}
                  data={preview.data.files.map((f) => ({
                    value: `${f.recordId}:${f.index}`,
                    label: `${f.name} · ${f.size.toLocaleString()} B`,
                  }))}
                  onChange={(v) => {
                    setFile(v);
                    setConfirmed(false);
                  }}
                />
                <p className="muted">{t("calibreItemResolution.retryHelp")}</p>
              </>
            ) : (
              <>
                <Select
                  mt="md"
                  label={t("calibreItemResolution.reason")}
                  value={reason}
                  disabled={mutation.isPending}
                  onChange={(v) => {
                    setReason(v);
                    setConfirmed(false);
                  }}
                  data={["NOT_NEEDED", "MANUAL", "UNSUITABLE", "OTHER"].map(
                    (value) => ({
                      value,
                      label: t(`calibreItemResolution.reasons.${value}`),
                    }),
                  )}
                />
                <Textarea
                  mt="md"
                  label={t("calibreItemResolution.note")}
                  maxLength={500}
                  value={note}
                  disabled={mutation.isPending}
                  onChange={(e) => setNote(e.currentTarget.value)}
                />
                <p className="muted">{t("calibreItemResolution.omitHelp")}</p>
              </>
            )}
            <Checkbox
              mt="md"
              label={t(
                action === "retry"
                  ? "calibreItemResolution.confirmRetry"
                  : "calibreItemResolution.confirmOmit",
              )}
              checked={confirmed}
              disabled={mutation.isPending}
              onChange={(e) => setConfirmed(e.currentTarget.checked)}
            />
          </>
        )}
        {mutation.isError && (
          <Failure error={mutation.error} retry={() => mutation.mutate()} />
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={mutation.isPending}
            onClick={() => setOpened(false)}
          >
            {t("common.close")}
          </Button>
          <Button
            loading={mutation.isPending}
            disabled={
              !action ||
              !preview.data ||
              preview.isFetching ||
              !confirmed ||
              (action === "retry" ? !file : !reason)
            }
            onClick={() => mutation.mutate()}
          >
            {t(
              action === "retry"
                ? "calibreItemResolution.applyRetry"
                : "calibreItemResolution.omit",
            )}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
