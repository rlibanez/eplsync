import { useAuth } from "../auth/Auth";
import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { Send } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  useQuery,
  useMutation,
  useQueryClient,
  useIsMutating,
} from "@tanstack/react-query";
import { Button, TextInput, Select, Checkbox } from "@mantine/core";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
import { post, type Job } from "./shared";
import { OptionLabel, type SendDefaults } from "./SendOptions";
export function SendSelection({
  filters,
  revisionStates,
  single,
  count,
  allResults,
  onClose,
}: {
  revisionStates?: string[];
  single?: { eplId: number; hash: string };
  filters: Record<string, unknown>;
  count: number;
  allResults: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const [previousVersions, setPreviousVersions] = useState("keep");
  const [cleanupTiming, setCleanupTiming] = useState("afterDownload");
  const [confirmFiles, setConfirmFiles] = useState(false);
  const [start, setStart] = useState("inherit");
  const [auto, setAuto] = useState("inherit");
  const [rename, setRename] = useState("inherit");
  const [path, setPath] = useState("");
  const [pattern, setPattern] = useState("");
  const [category, setCategory] = useState("");
  const [tags, setTags] = useState("");
  const [concurrency, setConcurrency] = useState("");
  const [batchSize, setBatchSize] = useState("");
  const [interval, setInterval] = useState("");
  const [multipleHashes, setMultipleHashes] = useState("inherit");
  const defaults = useQuery({
    queryKey: ["send-defaults"],
    queryFn: ({ signal }) => get<SendDefaults>("/torrent/options", signal),
  });
  const categories = useQuery({
    queryKey: ["torrent-categories"],
    queryFn: ({ signal }) =>
      get<string[]>("/torrent/client/categories", signal),
    staleTime: 0,
    refetchOnMount: "always",
    refetchOnWindowFocus: false,
    retry: false,
  });
  const defaultCategory = categories.data?.includes(
    defaults.data?.category ?? "",
  )
    ? (defaults.data?.category ?? "")
    : "";
  const ready =
    defaults.isSuccess && categories.isSuccess && !categories.isFetching;
  const initialized = useRef(false);
  function restoreDefaults(d: SendDefaults) {
    setPreviousVersions("keep");
    setConfirmFiles(false);
    setStart(String(d.start));
    setAuto(String(d.autoManagement));
    setPath(d.savePath ?? "");
    setRename(String(d.rename.enabled));
    setPattern(d.rename.pattern ?? "");
    setCategory(
      categories.data?.includes(d.category ?? "") ? (d.category ?? "") : "",
    );
    setTags(d.tags.join(", "));
    setConcurrency(String(d.concurrency));
    setBatchSize(String(d.batchSize));
    setInterval(d.interval);
    setMultipleHashes(d.multipleHashes);
  }
  useEffect(() => {
    if (defaults.data && ready && !initialized.current) {
      restoreDefaults(defaults.data);
      initialized.current = true;
    }
  }, [defaults.data, categories.data, ready]);
  function label(key: string, help: string) {
    return <OptionLabel text={t(key)} help={t(`send.help.${help}`)} />;
  }
  function modified(value: string, original: unknown) {
    return {
      input:
        defaults.data && value.trim() !== String(original ?? "").trim()
          ? "send-option-modified"
          : undefined,
    };
  }
  const active = useIsMutating({ mutationKey: ["send-books"] }) > 0;
  const mutation = useMutation({
    mutationKey: ["send-books"],
    meta: {
      backendEvents: true,
      notice: {
        title: "nav.send",
        success: "send.acceptedNote",
        href: "/downloads",
      },
    },
    retry: false,
    mutationFn: ({ url, body }: { url: string; body: unknown }) =>
      post<Job>(url, body),
    onSuccess: (data) => {
      void cache.invalidateQueries({ queryKey: ["downloads"] });
      void cache.invalidateQueries({ queryKey: ["download-summary"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      void cache.invalidateQueries({ queryKey: ["book-history"] });
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      void cache.invalidateQueries({ queryKey: ["revision-updates"] });
      onClose();
      if ("jobId" in data && auth.can("TORRENT_JOBS_MANAGE"))
        navigate(`/downloads/jobs/${data.jobId}`);
    },
  });
  function options() {
    return {
      start: start === "true",
      savePath: auto === "true" ? "" : path.trim(),
      rename: { enabled: rename === "true", pattern: pattern.trim() },
      qbittorrent: {
        autoManagement: auto === "true",
        category: category.trim(),
        tags: tags
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean),
      },
    };
  }
  function send() {
    mutation.mutate({
      url: revisionStates
        ? "/torrent/revision-updates/send"
        : single
          ? `/torrent/books/${single.eplId}`
          : "/torrent/books",
      body: {
        dryRun: false,
        ...(revisionStates
          ? {
              ids: filters.eplId,
              states: revisionStates,
              previousVersions,
              cleanupTiming:
                previousVersions === "keep" ? "afterDownload" : cleanupTiming,
              confirmFiles,
            }
          : {}),
        ...(!single && !revisionStates
          ? {
              filters,
              all: allResults,
              sort: ["title,asc", "eplId,asc"],
              multipleHashes,
            }
          : {}),
        options: { ...options(), ...(single ? { hash: single.hash } : {}) },
        concurrency: Number(concurrency),
        batchSize: Number(batchSize),
        interval,
        ...(revisionStates ? { multipleHashes } : {}),
      },
    });
  }
  const choices = [
    { value: "true", label: t("downloads.yes") },
    { value: "false", label: t("downloads.no") },
  ];
  const settingsAction = auth.can("SETTINGS_MANAGE") ? (
    <Button
      component={Link}
      to="/settings/torrent"
      variant="light"
      onClick={onClose}
    >
      {t("send.openTorrentSettings")}
    </Button>
  ) : undefined;
  return (
    <Modal
      opened
      onClose={() => !active && onClose()}
      title={t("selection.send")}
      icon={Send}
      size="xl"
      centered
    >
      <p>{t("selection.selected", { count })}</p>
      {allResults && <p className="muted">{t("send.liveSelection")}</p>}
      {(defaults.isPending || categories.isFetching) && <Loading />}
      {categories.isError && (
        <Failure
          error={categories.error}
          retry={() => categories.refetch()}
          actions={settingsAction}
        />
      )}
      {defaults.isError && (
        <Failure
          error={defaults.error}
          retry={() => defaults.refetch()}
          actions={settingsAction}
        />
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (
            !active &&
            ready &&
            initialized.current &&
            (previousVersions !== "removeTorrentAndFiles" || confirmFiles)
          )
            send();
        }}
      >
        <fieldset
          disabled={!ready || !initialized.current}
          style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}
        >
          {revisionStates && (
            <fieldset className="send-options-group">
              <legend>{t("send.previousVersions")}</legend>
              <Select
                label={t("send.previousAction")}
                value={previousVersions}
                allowDeselect={false}
                data={[
                  { value: "keep", label: t("send.keepPrevious") },
                  ...(auth.can("TORRENT_CLEANUP")
                    ? [
                        {
                          value: "removeTorrent",
                          label: t("send.removePrevious"),
                        },
                      ]
                    : []),
                  ...(auth.can("TORRENT_CLEANUP") &&
                  auth.can("TORRENT_FILES_DELETE")
                    ? [
                        {
                          value: "removeTorrentAndFiles",
                          label: t("send.removePreviousFiles"),
                        },
                      ]
                    : []),
                ]}
                onChange={(value) => {
                  setPreviousVersions(value ?? "keep");
                  setConfirmFiles(false);
                }}
              />
              {previousVersions !== "keep" && (
                <>
                  <Select
                    label={t("send.cleanupTiming")}
                    value={cleanupTiming}
                    allowDeselect={false}
                    data={[
                      { value: "immediate", label: t("send.cleanupImmediate") },
                      {
                        value: "afterDownload",
                        label: t("send.cleanupAfterDownload"),
                      },
                    ]}
                    onChange={(value) =>
                      setCleanupTiming(value ?? "afterDownload")
                    }
                  />
                  <p className="muted">
                    {t(
                      cleanupTiming === "immediate"
                        ? "send.cleanupImmediateHelp"
                        : "send.cleanupAfterCompletion",
                    )}
                  </p>
                </>
              )}
              {previousVersions === "removeTorrentAndFiles" && (
                <Checkbox
                  label={t("send.confirmPreviousFiles")}
                  checked={confirmFiles}
                  onChange={(event) =>
                    setConfirmFiles(event.currentTarget.checked)
                  }
                />
              )}
            </fieldset>
          )}
          <fieldset className="send-options-group">
            <legend>{t("send.jobOptions")}</legend>
            <div className="send-options-grid">
              <TextInput
                label={label("downloads.concurrency", "concurrency")}
                type="number"
                min={1}
                max={16}
                required
                classNames={modified(concurrency, defaults.data?.concurrency)}
                value={concurrency}
                onChange={(e) => setConcurrency(e.currentTarget.value)}
              />
              <TextInput
                label={label("downloads.batchSize", "batchSize")}
                type="number"
                min={1}
                max={1000}
                required
                classNames={modified(batchSize, defaults.data?.batchSize)}
                value={batchSize}
                onChange={(e) => setBatchSize(e.currentTarget.value)}
              />
              <TextInput
                label={label("send.interval", "interval")}
                required
                classNames={modified(interval, defaults.data?.interval)}
                value={interval}
                onChange={(e) => setInterval(e.currentTarget.value)}
              />
              {!single && (
                <Select
                  label={label("send.multipleHashes", "multipleHashes")}
                  classNames={modified(
                    multipleHashes,
                    defaults.data?.multipleHashes,
                  )}
                  value={multipleHashes}
                  allowDeselect={false}
                  onChange={(v) => setMultipleHashes(v!)}
                  data={["all", "first", "skip"].map((value) => ({
                    value,
                    label: t(`send.${value}`),
                  }))}
                />
              )}
            </div>
          </fieldset>
          <fieldset className="send-options-group">
            <legend>{t("send.clientOptions")}</legend>
            <div className="send-options-grid">
              <Select
                label={label("send.start", "start")}
                classNames={modified(start, defaults.data?.start)}
                value={start}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setStart(v!)}
              />
              <Select
                label={label("send.auto", "auto")}
                classNames={modified(auto, defaults.data?.autoManagement)}
                value={auto}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setAuto(v!)}
              />
              <TextInput
                label={label("send.path", "path")}
                classNames={modified(path, defaults.data?.savePath)}
                value={path}
                disabled={auto === "true"}
                onChange={(e) => setPath(e.currentTarget.value)}
              />
              <Select
                label={label("send.rename", "rename")}
                classNames={modified(rename, defaults.data?.rename.enabled)}
                value={rename}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setRename(v!)}
              />
              <TextInput
                className="send-pattern-field"
                label={label("send.pattern", "pattern")}
                classNames={modified(pattern, defaults.data?.rename.pattern)}
                value={pattern}
                required={rename === "true"}
                disabled={rename !== "true"}
                onChange={(e) => setPattern(e.currentTarget.value)}
              />
              <Select
                label={label("send.category", "category")}
                classNames={modified(category, defaultCategory)}
                value={category}
                searchable
                allowDeselect={false}
                data={[
                  { value: "", label: t("send.noCategory") },
                  ...(categories.data ?? []).map((name) => ({
                    value: name,
                    label: name,
                  })),
                ]}
                onChange={(value) => setCategory(value ?? "")}
              />
              <TextInput
                label={label("send.tags", "tags")}
                classNames={modified(tags, defaults.data?.tags.join(", "))}
                value={tags}
                onChange={(e) => setTags(e.currentTarget.value)}
              />
            </div>
          </fieldset>
          <Button
            variant="subtle"
            mt="md"
            onClick={() => defaults.data && restoreDefaults(defaults.data)}
          >
            {t("send.restoreDefaults")}
          </Button>
        </fieldset>
        <ModalActions>
          <Button variant="default" disabled={active} onClick={onClose}>
            {t("import.cancel")}
          </Button>
          <Button
            type="submit"
            loading={active}
            disabled={
              !ready ||
              !initialized.current ||
              count === 0 ||
              (previousVersions === "removeTorrentAndFiles" && !confirmFiles)
            }
          >
            {t("selection.createJob")}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
