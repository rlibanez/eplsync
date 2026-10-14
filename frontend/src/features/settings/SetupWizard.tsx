import { useMediaQuery } from "@mantine/hooks";
import { Button, Group, Stepper, Alert } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { post } from "../downloads/shared";
import { Loading, Failure } from "../../components/Feedback";
import { ImportCatalog } from "../maintenance/ImportCatalog";
import { useImport } from "../maintenance/ImportProvider";
import { TorrentSettings } from "./TorrentSettings";
import { CalibreSettings } from "./CalibreSettings";
import { useSetupProgress, type SetupProgress } from "./setupState";

export function SetupWizard() {
  const { t } = useTranslation();
  const mobile = useMediaQuery("(max-width: 700px)");
  const navigate = useNavigate();
  const cache = useQueryClient();
  const state = useSetupProgress();
  const { operation, restoring } = useImport();
  const catalog = useQuery({
    queryKey: ["catalog-metadata"],
    queryFn: ({ signal }) =>
      get<{ metadata: unknown | null }>("/catalog/import/metadata", signal),
  });
  const torrent = useQuery({
    queryKey: ["downloads", "sync-state"],
    queryFn: ({ signal }) =>
      get<{ syncedAt: string | null }>("/torrent/downloads/sync-state", signal),
    enabled: state.data?.step === 1,
  });
  const calibre = useQuery({
    queryKey: ["calibre-snapshot"],
    queryFn: ({ signal }) =>
      get<{ snapshot: Record<string, unknown> | null }>(
        "/settings/calibre/snapshot",
        signal,
      ).then((data) => data.snapshot),
    enabled: state.data?.step === 2,
  });
  const save = useMutation({
    mutationFn: (value: SetupProgress) =>
      post<SetupProgress>("/settings/setup", value),
    onSuccess: (value) => cache.setQueryData(["installation-setup"], value),
  });
  const sync = useMutation({
    retry: false,
    mutationFn: (step: number) =>
      step === 1
        ? post("/torrent/downloads/sync", {
            dryRun: false,
            includeDetails: false,
          })
        : post("/settings/calibre/sync", {}),
    onSuccess: async (_, step) => {
      await cache.invalidateQueries({
        queryKey: step === 1 ? ["downloads"] : ["calibre-snapshot"],
      });
      await cache.invalidateQueries({ queryKey: ["catalog"] });
      await cache.invalidateQueries({ queryKey: ["book"] });
      if (step === 2)
        await cache.invalidateQueries({ queryKey: ["calibre-library"] });
      if (state.data)
        save.mutate({
          ...state.data,
          [step === 1 ? "torrent" : "calibre"]: "done",
        });
    },
  });
  if (state.isPending) return <Loading />;
  if (state.isError)
    return <Failure error={state.error} retry={() => state.refetch()} />;
  if (!state.data) return null;
  const value = state.data;
  const step = value.step;
  const busy =
    save.isPending || sync.isPending || restoring || !!operation?.pending;
  const ready =
    step === 0
      ? !!catalog.data?.metadata
      : step === 1
        ? value.torrent === "done" || !!torrent.data?.syncedAt
        : step === 2
          ? value.calibre === "done" || !!calibre.data
          : true;
  async function advance(skip = false) {
    const next: SetupProgress = { ...value, status: "active", step: step + 1 };
    if (step === 1) next.torrent = skip ? "skipped" : "done";
    if (step === 2) next.calibre = skip ? "skipped" : "done";
    await save.mutateAsync(next).catch(() => null);
    sync.reset();
  }
  return (
    <>
      <h1>{t("setup.title")}</h1>
      <p className="muted">{t("setup.description")}</p>
      <Stepper
        active={step}
        mb="lg"
        size="sm"
        orientation={mobile ? "vertical" : "horizontal"}
      >
        {["catalog", "torrent", "calibre", "summary"].map((key) => (
          <Stepper.Step key={key} label={t(`setup.${key}`)} />
        ))}
      </Stepper>
      {step === 0 && <ImportCatalog guided />}
      {step === 1 && (
        <>
          <p>{t("setup.torrentHelp")}</p>
          <TorrentSettings />
        </>
      )}
      {step === 2 && (
        <>
          <p>{t("setup.calibreHelp")}</p>
          <CalibreSettings />
        </>
      )}
      {step === 3 && (
        <section className="panel settings-section">
          <h2>{t("setup.summary")}</h2>
          <dl className="import-summary">
            <div>
              <dt>{t("setup.catalog")}</dt>
              <dd>
                {t(catalog.data?.metadata ? "setup.done" : "setup.pending")}
              </dd>
            </div>
            <div>
              <dt>{t("setup.torrent")}</dt>
              <dd>{t(`setup.${value.torrent}`)}</dd>
            </div>
            <div>
              <dt>{t("setup.calibre")}</dt>
              <dd>{t(`setup.${value.calibre}`)}</dd>
            </div>
          </dl>
        </section>
      )}
      {step === 0 &&
        !operation?.pending &&
        (operation?.mode === "update" || operation?.mode === "apply") &&
        operation.result?.success &&
        operation.result.errors === 0 && (
          <Alert color="green" mt="md">
            {t("setup.imported")}
          </Alert>
        )}
      {sync.isSuccess && step < 3 && (
        <Alert color="green" mt="md">
          {t("setup.synced")}
        </Alert>
      )}
      {(sync.isError ||
        save.isError ||
        catalog.isError ||
        (step === 1 && torrent.isError) ||
        (step === 2 && calibre.isError)) && (
        <Failure
          error={
            sync.error ??
            save.error ??
            catalog.error ??
            torrent.error ??
            calibre.error!
          }
          retry={() => {
            sync.reset();
            void catalog.refetch();
            if (step === 1) void torrent.refetch();
            if (step === 2) void calibre.refetch();
          }}
        />
      )}
      <Group justify="space-between" mt="lg" mb="lg">
        <Group>
          <Button
            variant="subtle"
            disabled={save.isPending || sync.isPending}
            onClick={async () => {
              const saved = await save
                .mutateAsync({ ...value, status: "deferred" })
                .catch(() => null);
              if (saved) navigate("/");
            }}
          >
            {t("setup.close")}
          </Button>
          {step > 0 && (
            <Button
              variant="default"
              disabled={busy}
              onClick={() => {
                sync.reset();
                save.mutate({ ...value, status: "active", step: step - 1 });
              }}
            >
              {t("setup.back")}
            </Button>
          )}
        </Group>
        <Group>
          {(step === 1 || step === 2) && (
            <>
              <Button
                variant="subtle"
                disabled={busy}
                onClick={() => void advance(true)}
              >
                {t("setup.skip")}
              </Button>
              <Button
                variant="light"
                loading={sync.isPending}
                disabled={busy}
                onClick={() => sync.mutate(step)}
              >
                {t("setup.sync")}
              </Button>
            </>
          )}
          {step < 3 ? (
            <Button disabled={busy || !ready} onClick={() => void advance()}>
              {t("setup.next")}
            </Button>
          ) : (
            <Button
              disabled={busy}
              onClick={async () => {
                const saved = await save
                  .mutateAsync({ ...value, status: "completed" })
                  .catch(() => null);
                if (saved) navigate("/");
              }}
            >
              {t("setup.finish")}
            </Button>
          )}
        </Group>
      </Group>
    </>
  );
}
