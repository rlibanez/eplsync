import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { Send } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  useQuery,
  useMutation,
  useQueryClient,
  useIsMutating,
} from "@tanstack/react-query";
import { Button, TextInput, Select } from "@mantine/core";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Loading, Failure } from "../../components/Feedback";
import { post, type Job } from "./shared";
import { OptionLabel, type SendDefaults } from "./SendOptions";
export function SendSelection({
  filters,
  count,
  allResults,
  onClose,
}: {
  filters: Record<string, unknown>;
  count: number;
  allResults: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const cache = useQueryClient();
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
  const initialized = useRef(false);
  function restoreDefaults(d: SendDefaults) {
    setStart(String(d.start));
    setAuto(String(d.autoManagement));
    setPath(d.savePath ?? "");
    setRename(String(d.rename.enabled));
    setPattern(d.rename.pattern ?? "");
    setCategory(d.category ?? "");
    setTags(d.tags.join(", "));
    setConcurrency(String(d.concurrency));
    setBatchSize(String(d.batchSize));
    setInterval(d.interval);
    setMultipleHashes(d.multipleHashes);
  }
  useEffect(() => {
    if (defaults.data && !initialized.current) {
      restoreDefaults(defaults.data);
      initialized.current = true;
    }
  }, [defaults.data]);
  function label(key: string, help: string) {
    return <OptionLabel text={t(key)} help={t(`send.help.${help}`)} />;
  }
  function modified(value: string, original: unknown) {
    return {
      input: defaults.data && value.trim() !== String(original ?? "").trim()
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
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      onClose();
      if ("jobId" in data) navigate(`/downloads/jobs/${data.jobId}`);
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
      url: "/torrent/books",
      body: {
        dryRun: false,
        filters,
        all: allResults,
        sort: ["title,asc", "eplId,asc"],
        options: options(),
        concurrency: Number(concurrency),
        batchSize: Number(batchSize),
        interval,
        multipleHashes,
      },
    });
  }
  const choices = [
    { value: "true", label: t("downloads.yes") },
    { value: "false", label: t("downloads.no") },
  ];
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
      {defaults.isPending && <Loading />}
      {defaults.isError && (
        <Failure error={defaults.error} retry={() => defaults.refetch()} />
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!active && defaults.isSuccess && initialized.current) send();
        }}
      >
        <fieldset
          disabled={!defaults.isSuccess || !initialized.current}
          style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}
        >
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
            <Select
              label={label("send.multipleHashes", "multipleHashes")}
              classNames={modified(multipleHashes, defaults.data?.multipleHashes)}
              value={multipleHashes}
              allowDeselect={false}
              onChange={(v) => setMultipleHashes(v!)}
              data={["all", "first", "skip"].map((value) => ({
                value,
                label: t(`send.${value}`),
              }))}
            />
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
            <TextInput
              label={label("send.category", "category")}
              classNames={modified(category, defaults.data?.category)}
              value={category}
              onChange={(e) => setCategory(e.currentTarget.value)}
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
              !defaults.isSuccess || !initialized.current || count === 0
            }
          >
            {t("selection.createJob")}
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
