import { useState, type ComponentType } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Progress,
  Select,
  Stepper,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { Eraser } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { Loading, Failure } from "../../components/Feedback";
import type { PreviewOrder } from "../../components/PreviewSort";
import { get } from "../../api/catalog";
import { post, type Page } from "../downloads/shared";
import type { CleanupJobData, CleanupItem } from "./RevisionCleanup";

type Preparation = {
  job: CleanupJobData;
  state: string;
  processed: number;
  total: number;
  version: number;
  reviewRequired: number;
  message: string | null;
};
type Copy = { id: string; title: string; formats: string[] };
type CopyGroup = {
  key: string;
  eplId: number;
  revision: string;
  retainedId: string;
  title: string;
  formats: string[];
  requiresChoice: boolean;
};
type PreviewTable = ComponentType<{
  rows: CleanupItem[];
  offset?: number;
  selected?: string[];
  onSelect?: (ids: string[]) => void;
  selectionDisabled?: boolean;
  showReason?: boolean;
  order?: PreviewOrder;
  toggle?: (key: string) => void;
}>;
function Pager({
  page,
  total,
  setPage,
  label,
  disabled = false,
}: {
  page: number;
  total: number;
  setPage: (page: number) => void;
  label: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  if (total <= 1) return null;
  return (
    <nav className="pagination cleanup-preview-pagination" aria-label={label}>
      <Button
        variant="subtle"
        disabled={disabled || page === 0}
        onClick={() => setPage(page - 1)}
      >
        {t("catalog.previous")}
      </Button>
      <span>
        {page + 1} / {total}
      </span>
      <Button
        variant="subtle"
        disabled={disabled || page + 1 >= total}
        onClick={() => setPage(page + 1)}
      >
        {t("catalog.next")}
      </Button>
    </nav>
  );
}
function CopyPicker({
  id,
  version,
  group,
  disabled,
  choose,
}: {
  id: string;
  version: number;
  group: CopyGroup;
  disabled: boolean;
  choose: (group: string, copy: string) => void;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [term] = useDebouncedValue(search, 200);
  const copies = useQuery({
    queryKey: ["library-cleanup-copies", id, version, group.key, page, term],
    queryFn: ({ signal }) =>
      get<Page<Copy>>(
        `/revision-cleanups/${id}/library-copies?version=${version}&group=${encodeURIComponent(group.key)}&page=${page}&size=20&search=${encodeURIComponent(term)}`,
        signal,
      ),
  });
  const data = new Map([
    [
      group.retainedId,
      { id: group.retainedId, title: group.title, formats: group.formats },
    ],
    ...(copies.data?.items ?? []).map((copy) => [copy.id, copy] as const),
  ]);
  return (
    <div className="library-cleanup-copy">
      <Select
        label={`EPL ${group.eplId} · r${group.revision}`}
        value={group.retainedId}
        searchable
        searchValue={search}
        onSearchChange={(value) => {
          setSearch(value);
          setPage(0);
        }}
        allowDeselect={false}
        disabled={disabled}
        data={[...data.values()].map((copy) => ({
          value: copy.id,
          label: `Calibre ID ${copy.id} · ${copy.title} · ${copy.formats.join(", ") || "—"}`,
        }))}
        onOptionSubmit={(value) => choose(group.key, value)}
      />
      {group.requiresChoice && (
        <Alert color="yellow" mt="xs">
          {t("libraryCleanup.differentFormats")}
        </Alert>
      )}
      {copies.isError && (
        <Failure error={copies.error} retry={() => void copies.refetch()} />
      )}
      <Pager
        page={page}
        total={copies.data?.meta.totalPages ?? 0}
        setPage={setPage}
        label={t("libraryCleanup.copyPages")}
        disabled={disabled || copies.isFetching}
      />
    </div>
  );
}
export function LibraryCleanupWizard({
  selected,
  close,
  Table,
}: {
  selected: string[];
  close: () => void;
  Table: PreviewTable;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const [step, setStep] = useState(0);
  const [oldRevisions, setOldRevisions] = useState(true);
  const [duplicates, setDuplicates] = useState(true);
  const [scope, setScope] = useState(selected.length ? "selected" : "all");
  const [draft, setDraft] = useState<Preparation>();
  const [page, setPage] = useState(0);
  const [groupPage, setGroupPage] = useState(0);
  const [order, setOrder] = useState<PreviewOrder>({
    key: "eplId",
    descending: false,
  });
  const [confirm, setConfirm] = useState(false);
  const [destructive, setDestructive] = useState(false);
  const key = ["library-cleanup-preparation", draft?.job.id];
  const preparation = useQuery({
    queryKey: key,
    queryFn: ({ signal }) =>
      get<Preparation>(
        `/revision-cleanups/${draft!.job.id}/preparation`,
        signal,
      ),
    enabled: !!draft,
    refetchInterval: (query) =>
      ["QUEUED", "RUNNING"].includes(
        query.state.data?.state ?? draft?.state ?? "",
      )
        ? 1000
        : false,
  });
  const status = preparation.data ?? draft;
  const id = status?.job.id;
  const version = status?.version ?? 0;
  const ready = status?.state === "READY";
  const rows = useQuery({
    queryKey: ["library-cleanup-items", id, version, page, order],
    queryFn: ({ signal }) =>
      get<Page<CleanupItem>>(
        `/revision-cleanups/${id}/library-items?version=${version}&page=${page}&size=20&sort=${order?.key ?? "eplId"},${order?.descending ? "desc" : "asc"}`,
        signal,
      ),
    enabled: ready,
  });
  const groups = useQuery({
    queryKey: ["library-cleanup-groups", id, version, groupPage],
    queryFn: ({ signal }) =>
      get<Page<CopyGroup>>(
        `/revision-cleanups/${id}/library-groups?version=${version}&page=${groupPage}&size=5`,
        signal,
      ),
    enabled: ready,
  });
  function update(value: Preparation) {
    cache.setQueryData(["library-cleanup-preparation", value.job.id], value);
    setDraft(value);
  }
  const prepare = useMutation({
    mutationFn: () =>
      post<Preparation>("/revision-cleanups/calibre-library/prepare", {
        oldRevisions,
        duplicates,
        ids: scope === "selected" ? selected : [],
        retained: {},
        previewId: draft?.job.id ?? null,
      }),
    onSuccess: (value) => {
      update(value);
      setPage(0);
      setGroupPage(0);
      setConfirm(false);
      setDestructive(false);
      setStep(1);
    },
  });
  const selection = useMutation({
    mutationFn: async (
      input: { all?: boolean; ids?: string[]; selected: boolean }[],
    ) => {
      let value: Preparation | undefined;
      for (const change of input)
        value = await post<Preparation>(
          `/revision-cleanups/${id}/library-selection`,
          { version, all: false, ...change },
        );
      return value!;
    },
    onSuccess: (value) => {
      update(value);
      void cache.invalidateQueries({ queryKey: ["library-cleanup-items", id] });
    },
  });
  const retained = useMutation({
    mutationFn: (input: { groupKey: string; copyId: string }) =>
      post<Preparation>(`/revision-cleanups/${id}/library-retained`, {
        version,
        ...input,
      }),
    onSuccess: (value) => {
      update(value);
      setPage(0);
      setGroupPage(0);
      setConfirm(false);
      setDestructive(false);
    },
  });
  const start = useMutation({
    mutationFn: () =>
      post<CleanupJobData>(`/revision-cleanups/${id}/library-confirm`, {
        version,
        confirm,
        confirmDestructive: destructive,
      }),
    onSuccess: (job) => {
      ["revision-cleanups", "jobs", "calibre-jobs"].forEach(
        (queryKey) => void cache.invalidateQueries({ queryKey: [queryKey] }),
      );
      close();
      navigate(`/calibre/jobs/cleanup/${job.id}`);
    },
  });
  const closing = useMutation({
    mutationFn: async () => {
      if (id) await post(`/revision-cleanups/${id}/library-cancel`, {});
    },
    onSettled: () => close(),
  });
  const busy =
    prepare.isPending ||
    selection.isPending ||
    retained.isPending ||
    start.isPending ||
    closing.isPending;
  const pageSelected =
    rows.data?.items
      .filter((item) => item.previewSelected)
      .map((item) => item.id) ?? [];
  const error =
    prepare.error || selection.error || retained.error || start.error;
  return (
    <AppModal
      opened
      onClose={() => {
        if (!busy) closing.mutate();
      }}
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      title={t("libraryCleanup.title")}
      icon={Eraser}
      width="wide"
    >
      <Stepper active={step} size="xs" allowNextStepsSelect={false}>
        <Stepper.Step label={t("revisionCleanup.configure")} />
        <Stepper.Step label={t("revisionCleanup.preview")} />
        <Stepper.Step label={t("revisionCleanup.confirm")} />
      </Stepper>
      {step === 0 && (
        <>
          <p>{t("libraryCleanup.description")}</p>
          <p className="muted">{t("libraryCleanup.backgroundInfo")}</p>
          <Checkbox
            checked={oldRevisions}
            label={t("libraryCleanup.oldRevisions")}
            onChange={(event) => setOldRevisions(event.currentTarget.checked)}
          />
          <Checkbox
            mt="sm"
            checked={duplicates}
            label={t("libraryCleanup.duplicates")}
            onChange={(event) => setDuplicates(event.currentTarget.checked)}
          />
          {!!selected.length && (
            <Select
              label={t("revisionCleanup.scope")}
              value={scope}
              allowDeselect={false}
              onChange={(value) => value && setScope(value)}
              data={[
                {
                  value: "selected",
                  label: t("revisionCleanup.selectedScope", {
                    count: selected.length,
                  }),
                },
                { value: "all", label: t("revisionCleanup.allScope") },
              ]}
            />
          )}
        </>
      )}
      {step === 1 && (
        <>
          {!ready && status && (
            <>
              <p>
                {t("libraryCleanup.preparing", {
                  processed: status.processed,
                  total: status.total,
                })}
              </p>
              {["QUEUED", "RUNNING"].includes(status.state) && (
                <Progress
                  value={
                    status.total
                      ? Math.min(99, (status.processed / status.total) * 100)
                      : 0
                  }
                  animated
                />
              )}
              {status.message && (
                <Alert
                  color={status.state === "FAILED" ? "red" : "blue"}
                  mt="sm"
                >
                  {status.message}
                </Alert>
              )}
            </>
          )}
          {preparation.isError && (
            <Failure
              error={preparation.error}
              retry={() => void preparation.refetch()}
            />
          )}
          {ready && (
            <>
              <p>
                {t("libraryCleanup.previewSummary", {
                  ready: status.job.ready,
                  excluded: status.job.excluded,
                })}
              </p>
              {!!groups.data?.items.length && (
                <fieldset className="send-options-group library-cleanup-copies">
                  <legend>{t("libraryCleanup.retainedCopies")}</legend>
                  {groups.data.items.map((group) => (
                    <CopyPicker
                      key={`${version}:${group.key}`}
                      id={id!}
                      version={version}
                      group={group}
                      disabled={busy}
                      choose={(groupKey, copyId) =>
                        retained.mutate({ groupKey, copyId })
                      }
                    />
                  ))}
                  <Pager
                    page={groupPage}
                    total={groups.data.meta.totalPages}
                    setPage={setGroupPage}
                    label={t("libraryCleanup.groupPages")}
                    disabled={busy || groups.isFetching}
                  />
                </fieldset>
              )}
              {groups.isError && (
                <Failure
                  error={groups.error}
                  retry={() => void groups.refetch()}
                />
              )}
              <fieldset className="send-options-group cleanup-preview">
                <legend>{t("revisionCleanup.previewBooks")}</legend>
                <Group gap="xs" mb="sm">
                  <Button
                    variant="subtle"
                    disabled={busy}
                    onClick={() =>
                      selection.mutate([{ all: true, selected: true }])
                    }
                  >
                    {t("revisionCleanup.selectAll")}
                  </Button>
                  <Button
                    variant="subtle"
                    disabled={busy}
                    onClick={() =>
                      selection.mutate([{ all: true, selected: false }])
                    }
                  >
                    {t("revisionCleanup.clear", { count: status.job.selected })}
                  </Button>
                </Group>
                {rows.isPending ? (
                  <Loading />
                ) : rows.isError ? (
                  <Failure
                    error={rows.error}
                    retry={() => void rows.refetch()}
                  />
                ) : (
                  <Table
                    rows={rows.data.items}
                    offset={page * 20}
                    selected={pageSelected}
                    showReason
                    selectionDisabled={busy || rows.isFetching}
                    onSelect={(next) => {
                      const added = next.filter(
                        (value) => !pageSelected.includes(value),
                      );
                      const removed = pageSelected.filter(
                        (value) => !next.includes(value),
                      );
                      const changes = [];
                      if (added.length)
                        changes.push({ ids: added, selected: true });
                      if (removed.length)
                        changes.push({ ids: removed, selected: false });
                      if (changes.length) selection.mutate(changes);
                    }}
                    order={order}
                    toggle={(field) => {
                      setOrder({
                        key: field,
                        descending:
                          order?.key === field ? !order.descending : false,
                      });
                      setPage(0);
                    }}
                  />
                )}
                <Pager
                  page={page}
                  total={rows.data?.meta.totalPages ?? 0}
                  setPage={setPage}
                  label={t("revisionCleanup.previewPages")}
                  disabled={busy || rows.isFetching}
                />
              </fieldset>
            </>
          )}
        </>
      )}
      {step === 2 && (
        <>
          <p>
            <strong>
              {t("libraryCleanup.confirmCount", {
                count: status?.job.selected ?? 0,
              })}
            </strong>
          </p>
          <Checkbox
            label={t("libraryCleanup.confirmSelection")}
            checked={confirm}
            onChange={(event) => setConfirm(event.currentTarget.checked)}
          />
          <Checkbox
            mt="sm"
            label={t("revisionCleanup.confirmDestructive")}
            checked={destructive}
            onChange={(event) => setDestructive(event.currentTarget.checked)}
          />
        </>
      )}
      {error && (
        <Alert color="red" mt="sm">
          {error instanceof Error ? error.message : t("revisionCleanup.error")}
        </Alert>
      )}
      <ModalActions
        summary={
          step === 1
            ? t("libraryCleanup.count", { count: status?.job.selected ?? 0 })
            : undefined
        }
      >
        {step > 0 && (ready || status?.state === "FAILED") && (
          <Button
            variant="default"
            disabled={busy}
            onClick={() => {
              setStep(step - 1);
              setConfirm(false);
              setDestructive(false);
            }}
          >
            {t("revisionCleanup.back")}
          </Button>
        )}
        <Button
          variant="default"
          disabled={busy}
          onClick={() => closing.mutate()}
        >
          {t("common.cancel")}
        </Button>
        {step === 0 ? (
          <Button
            disabled={!oldRevisions && !duplicates}
            loading={prepare.isPending}
            onClick={() => prepare.mutate()}
          >
            {t("revisionCleanup.preview")}
          </Button>
        ) : step === 1 ? (
          <Button
            disabled={
              !ready ||
              busy ||
              rows.isFetching ||
              !status?.job.selected ||
              !!status.reviewRequired
            }
            onClick={() => setStep(2)}
          >
            {t("revisionCleanup.next")}
          </Button>
        ) : (
          <Button
            color="red"
            disabled={busy || !confirm || !destructive}
            loading={start.isPending}
            onClick={() => start.mutate()}
          >
            {t("revisionCleanup.create")}
          </Button>
        )}
      </ModalActions>
    </AppModal>
  );
}
