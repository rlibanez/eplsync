import { useState } from "react";
import { Button, Checkbox } from "@mantine/core";
import { BadgeCheck } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";

type Preview = {
  token: string;
  calibreId: number;
  eplId: number;
  epubRevision: string;
  currentRevision: string;
  targetRevision: string;
};
export function ConfirmCalibreRevision({
  job,
  item,
  revision,
}: {
  job: string;
  item: string;
  revision: string;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const url = `/calibre/transfers/${job}/items/${item}/revision-confirmation`;
  const preview = useQuery({
    queryKey: ["calibre-revision-confirmation", job, item],
    queryFn: ({ signal }) => get<Preview>(url, signal),
    enabled: opened,
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const mutation = useMutation({
    mutationFn: () =>
      post(url, { token: preview.data?.token, confirm: confirmed }),
    retry: false,
    onSuccess: () => {
      setOpened(false);
      for (const key of [
        "calibre-jobs",
        "calibre-job",
        "calibre-job-items",
        "calibre-library",
        "book-activity",
      ])
        void cache.invalidateQueries({ queryKey: [key] });
    },
    onError: () =>
      void cache.invalidateQueries({ queryKey: ["book-activity"] }),
  });
  return (
    <>
      <button
        type="button"
        className="table-filter-link"
        onClick={() => {
          mutation.reset();
          setConfirmed(false);
          setOpened(true);
        }}
      >
        {t("calibreRevision.action", { revision })}
      </button>
      <AppModal
        icon={BadgeCheck}
        opened={opened}
        onClose={() => {
          if (!mutation.isPending) setOpened(false);
        }}
        title={t("calibreRevision.action", { revision })}
      >
        <p>{t("calibreRevision.help")}</p>
        {preview.isFetching ? (
          <Loading />
        ) : preview.isError ? (
          <Failure error={preview.error} retry={() => void preview.refetch()} />
        ) : (
          preview.data && (
            <>
              <p>{t("calibreRevision.identity", preview.data)}</p>
              <dl>
                <dt>{t("calibreRevision.internal")}</dt>
                <dd>r{preview.data.epubRevision}</dd>
                <dt>{t("calibreRevision.current")}</dt>
                <dd>r{preview.data.currentRevision}</dd>
                <dt>{t("calibreRevision.target")}</dt>
                <dd>r{preview.data.targetRevision}</dd>
              </dl>
              <Checkbox
                mt="md"
                label={t("calibreRevision.consent", {
                  revision: preview.data.targetRevision,
                })}
                checked={confirmed}
                onChange={(e) => setConfirmed(e.currentTarget.checked)}
                disabled={mutation.isPending}
              />
            </>
          )
        )}
        {mutation.isError && (
          <Failure
            error={mutation.error}
            retry={() => {
              setConfirmed(false);
              mutation.reset();
              void preview.refetch();
            }}
          />
        )}
        <ModalActions>
          <Button
            variant="default"
            disabled={mutation.isPending}
            onClick={() => setOpened(false)}
          >
            {t("common.cancel")}
          </Button>
          <Button
            loading={mutation.isPending}
            disabled={
              !confirmed ||
              !preview.data ||
              preview.isFetching ||
              preview.isError ||
              mutation.isError
            }
            onClick={() => mutation.mutate()}
          >
            {t("calibreRevision.apply")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
