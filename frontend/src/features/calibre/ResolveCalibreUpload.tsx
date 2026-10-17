import { SearchCheck } from "lucide-react";
import { useState } from "react";
import { Button, Checkbox, Select, TextInput } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Failure } from "../../components/Feedback";
import { post } from "../downloads/shared";

export function ResolveCalibreUpload({
  jobId,
  itemId,
}: {
  jobId: string;
  itemId: string;
}) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [opened, setOpened] = useState(false),
    [action, setAction] = useState("verify"),
    [remote, setRemote] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  const resolve = useMutation({
    mutationFn: () =>
      post(`/calibre/transfers/${jobId}/resolution`, {
        itemId,
        action,
        calibreId: action === "verify" ? Number(remote) : null,
        confirm: confirmed,
      }),
    retry: false,
    onSuccess: () => {
      setOpened(false);
      for (const key of [
        "calibre-jobs",
        "calibre-job",
        "calibre-job-items",
        "job-retries",
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
          resolve.reset();
          setConfirmed(false);
          setRemote("");
          setAction("verify");
          setOpened(true);
        }}
      >
        {t("calibreSend.resolve")}
      </button>
      <AppModal
        icon={SearchCheck}
        opened={opened}
        onClose={() => {
          if (!resolve.isPending) setOpened(false);
        }}
        title={t("calibreSend.resolve")}
      >
        <p>{t("calibreSend.resolveHelp")}</p>
        <Select
          label={t("revisionCleanup.action")}
          value={action}
          onChange={(value) => {
            setAction(value ?? "verify");
            setConfirmed(false);
          }}
          data={[
            { value: "verify", label: t("calibreSend.resolveVerify") },
            { value: "retry", label: t("calibreSend.resolveRetry") },
          ]}
          disabled={resolve.isPending}
        />
        {action === "verify" && (
          <TextInput
            label={t("calibreSend.remoteId")}
            value={remote}
            inputMode="numeric"
            onChange={(event) => setRemote(event.currentTarget.value)}
            disabled={resolve.isPending}
          />
        )}
        <Checkbox
          mt="md"
          label={t(
            action === "verify"
              ? "calibreSend.resolveConfirmBook"
              : "calibreSend.resolveConfirmAbsent",
          )}
          checked={confirmed}
          onChange={(event) => setConfirmed(event.currentTarget.checked)}
          disabled={resolve.isPending}
        />
        {resolve.isError && (
          <Failure error={resolve.error} retry={() => resolve.mutate()} />
        )}
        <ModalActions>
          <Button
            variant="default"
            onClick={() => setOpened(false)}
            disabled={resolve.isPending}
          >
            {t("calibreSend.cancel")}
          </Button>
          <Button
            onClick={() => resolve.mutate()}
            loading={resolve.isPending}
            disabled={
              !confirmed ||
              (action === "verify" &&
                (!/^\d+$/.test(remote) ||
                  !Number.isSafeInteger(Number(remote)) ||
                  Number(remote) <= 0))
            }
          >
            {t("calibreSend.resolveApply")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
