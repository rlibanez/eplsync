import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { Send } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  useQuery,
  useMutation,
  useQueryClient,
  useIsMutating,
} from "@tanstack/react-query";
import { Button, TextInput, Select, Checkbox, Alert } from "@mantine/core";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get, languages, type BookPage } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, post, type Job } from "./shared";
import { OptionLabel, type SendDefaults } from "./SendOptions";
export function SendBooks() {
  const { t } = useTranslation();
  const { language, status } = useLocale();
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const cache = useQueryClient();
  const [filters, setFilters] = useState<URLSearchParams | null>(() =>
    search.get("eplId")
      ? new URLSearchParams({ eplId: search.get("eplId")! })
      : null,
  );
  const selectionForm = useRef<HTMLFormElement>(null);
  const [selectionDirty, setSelectionDirty] = useState(false);
  const [previewPage, setPreviewPage] = useState(0);
  const [previewSize, setPreviewSize] = useState(20);
  const [confirmed, setConfirmed] = useState(false);
  const [all, setAll] = useState(false);
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
  const preview = new URLSearchParams(filters ?? undefined);
  preview.set("page", String(previewPage));
  preview.set("size", String(previewSize));
  preview.append("sort", "title,asc");
  preview.append("sort", "eplId,asc");
  const books = useQuery({
    queryKey: ["send-preview", preview.toString()],
    queryFn: ({ signal }) => get<BookPage>(`/catalog/books?${preview}`, signal),
    enabled: filters !== null,
  });
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
    setConfirmed(false);
    const selectedFilters: Record<string, unknown> = Object.fromEntries(
      filters!,
    );
    for (const key of [
      "eplId",
      "publicationYear",
      "publicationYearFrom",
      "publicationYearTo",
    ])
      if (selectedFilters[key])
        selectedFilters[key] = Number(selectedFilters[key]);
    if (filters!.has("status"))
      selectedFilters.status = filters!.getAll("status");
    mutation.mutate({
      url: "/torrent/books",
      body: {
        dryRun: false,
        filters: selectedFilters,
        sort: ["title,asc", "eplId,asc"],
        all: !filters!.size,
        options: options(),
        concurrency: Number(concurrency),
        batchSize: Number(batchSize),
        interval,
        multipleHashes,
      },
    });
  }
  const canSend =
    initialized.current &&
    defaults.isSuccess &&
    !!books.data?.meta.totalItems &&
    (!filters?.size ? all : true);
  const choices = [
    { value: "true", label: t("downloads.yes") },
    { value: "false", label: t("downloads.no") },
  ];
  return (
    <>
      <h1>{t("nav.send")}</h1>
      <p className="muted">{t("send.multipleNote")}</p>
      <section className="panel settings-section">
        <h2>{t("send.selection")}</h2>
        <form
          ref={selectionForm}
          onChange={() => setSelectionDirty(true)}
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            const next = new URLSearchParams();
            for (const [key, value] of f) {
              if (String(value).trim()) next.set(key, String(value).trim());
            }
            setFilters(next);
            setSelectionDirty(false);
            setPreviewPage(0);
            setAll(false);
            mutation.reset();
          }}
        >
          <div className="form-grid">
            <TextInput
              name="eplId"
              label="EPL ID"
              type="number"
              min={1}
              defaultValue={search.get("eplId") ?? ""}
            />
            <TextInput name="title" label={t("catalog.title")} />
            <TextInput name="author" label={t("catalog.author")} />
            <Select
              name="language"
              onChange={() => setSelectionDirty(true)}
              label={t("catalog.language")}
              data={languages.map((value) => ({
                value,
                label: language(value),
              }))}
              clearable
            />
            <TextInput name="genres" label={t("directory.genres")} />
            <TextInput
              name="publicationYear"
              type="number"
              min={0}
              max={3000}
              label={t("directory.years")}
            />
          </div>
          <details>
            <summary>{t("send.moreFilters")}</summary>
            <div className="form-grid">
              <TextInput name="collection" label={t("send.collection")} />
              {["publicationYearFrom", "publicationYearTo"].map((key) => (
                <TextInput
                  key={key}
                  name={key}
                  type="number"
                  min={0}
                  max={3000}
                  label={t(`send.${key}`)}
                />
              ))}
              {["publicationDateFrom", "publicationDateTo"].map((key) => (
                <TextInput
                  key={key}
                  name={key}
                  type="date"
                  label={t(`send.${key}`)}
                />
              ))}
              <Select
                name="status"
                onChange={() => setSelectionDirty(true)}
                label={t("downloads.status")}
                clearable
                data={["DISPONIBLE", "VERIFICADO", "DESCONOCIDO"].map(
                  (value) => ({
                    value,
                    label: status(value),
                  }),
                )}
              />
            </div>
          </details>
          <Button type="submit" disabled={active}>
            {t("send.preview")}
          </Button>
        </form>
        {filters !== null &&
          (books.isPending ? (
            <Loading />
          ) : books.isError ? (
            <Failure error={books.error} retry={() => books.refetch()} />
          ) : (
            <>
              <p className="selection-total">
                {t("send.matches", { count: books.data.meta.totalItems })}
              </p>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>{t("downloads.book")}</th>
                      <th>{t("catalog.author")}</th>
                      <th>{t("catalog.language")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {books.data.items.map((book) => (
                      <tr key={book.eplId}>
                        <td>
                          <Link to={`/catalog/${book.eplId}`}>
                            {book.title}
                          </Link>
                          <small className="hash-text">EPL {book.eplId}</small>
                        </td>
                        <td>{book.author}</td>
                        <td>{language(book.language)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paging
                meta={books.data.meta}
                page={previewPage}
                size={previewSize}
                onPage={setPreviewPage}
                onSize={(n) => {
                  setPreviewSize(n);
                  setPreviewPage(0);
                }}
              />
              {<Alert>{t("send.allMatchesNote")}</Alert>}
              {!filters.size && (
                <Checkbox
                  mt="md"
                  label={t("send.allCatalog")}
                  checked={all}
                  onChange={(e) => setAll(e.currentTarget.checked)}
                />
              )}
            </>
          ))}
      </section>
      {selectionDirty && <Alert color="yellow">{t("send.dirty")}</Alert>}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const current = new URLSearchParams();
          for (const [key, value] of new FormData(selectionForm.current!))
            if (String(value).trim()) current.set(key, String(value).trim());
          if (current.toString() !== filters?.toString()) {
            setSelectionDirty(true);
            return;
          }
          setConfirmed(true);
        }}
      >
        <section className="panel settings-section">
          <h2>{t("send.options")}</h2>
          <p className="muted">{t("send.defaults")}</p>
          {defaults.isPending && <Loading />}
          {defaults.isError && (
            <Failure error={defaults.error} retry={() => defaults.refetch()} />
          )}
          <fieldset
            disabled={!defaults.isSuccess || !initialized.current}
            style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}
          >
            <h3>{t("send.jobOptions")}</h3>
            <div className="form-grid">
              <TextInput
                label={label("downloads.concurrency", "concurrency")}
                type="number"
                min={1}
                max={16}
                required
                value={concurrency}
                onChange={(e) => setConcurrency(e.currentTarget.value)}
              />
              <TextInput
                label={label("downloads.batchSize", "batchSize")}
                type="number"
                min={1}
                max={1000}
                required
                value={batchSize}
                onChange={(e) => setBatchSize(e.currentTarget.value)}
              />
              <TextInput
                label={label("send.interval", "interval")}
                required
                value={interval}
                onChange={(e) => setInterval(e.currentTarget.value)}
              />
              <Select
                label={label("send.multipleHashes", "multipleHashes")}
                value={multipleHashes}
                allowDeselect={false}
                onChange={(v) => setMultipleHashes(v!)}
                data={["all", "first", "skip"].map((value) => ({
                  value,
                  label: t(`send.${value}`),
                }))}
              />
            </div>
            <h3 style={{ marginTop: "1.5rem" }}>{t("send.clientOptions")}</h3>
            <div className="form-grid">
              <Select
                label={label("send.start", "start")}
                value={start}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setStart(v!)}
              />
              <Select
                label={label("send.auto", "auto")}
                value={auto}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setAuto(v!)}
              />
              <TextInput
                label={label("send.path", "path")}
                value={path}
                disabled={auto === "true"}
                onChange={(e) => setPath(e.currentTarget.value)}
              />
              <Select
                label={label("send.rename", "rename")}
                value={rename}
                data={choices}
                allowDeselect={false}
                onChange={(v) => setRename(v!)}
              />
              <TextInput
                label={label("send.pattern", "pattern")}
                value={pattern}
                required={rename === "true"}
                disabled={rename !== "true"}
                onChange={(e) => setPattern(e.currentTarget.value)}
              />
              <TextInput
                label={label("send.category", "category")}
                value={category}
                onChange={(e) => setCategory(e.currentTarget.value)}
              />
              <TextInput
                label={label("send.tags", "tags")}
                value={tags}
                onChange={(e) => setTags(e.currentTarget.value)}
              />
            </div>
            <Button
              variant="subtle"
              mt="md"
              onClick={() => defaults.data && restoreDefaults(defaults.data)}
            >
              {t("send.restoreDefaults")}
            </Button>
          </fieldset>
        </section>
        <Button
          type="submit"
          loading={active}
          disabled={!canSend || books.isFetching || selectionDirty}
        >
          {t("send.review")}
        </Button>
      </form>
      <Modal
        icon={Send}
        opened={confirmed}
        onClose={() => setConfirmed(false)}
        title={t("send.confirmTitle")}
        centered
      >
        <p>
          {t("send.confirmMultiple", {
            count: books.data?.meta.totalItems ?? 0,
          })}
        </p>
        <p className="muted">{t("send.liveSelection")}</p>
        {
          <dl className="import-summary">
            {Array.from(filters?.entries() ?? []).map(([key, value]) => (
              <div key={key}>
                <dt>
                  {t(
                    (
                      {
                        eplId: "send.eplId",
                        title: "catalog.title",
                        author: "catalog.author",
                        language: "catalog.language",
                        genres: "directory.genres",
                        publicationYear: "directory.years",
                        collection: "send.collection",
                        status: "downloads.status",
                      } as Record<string, string>
                    )[key] ?? `send.${key}`,
                  )}
                </dt>
                <dd>
                  {key === "language"
                    ? language(value)
                    : key === "status"
                      ? status(value)
                      : value}
                </dd>
              </div>
            ))}
          </dl>
        }
        <dl className="import-summary">
          <div>
            <dt>{t("send.start")}</dt>
            <dd>
              {t(
                start === "inherit"
                  ? "send.inherit"
                  : start === "true"
                    ? "downloads.yes"
                    : "downloads.no",
              )}
            </dd>
          </div>
          <div>
            <dt>{t("send.auto")}</dt>
            <dd>
              {t(
                auto === "inherit"
                  ? "send.inherit"
                  : auto === "true"
                    ? "downloads.yes"
                    : "downloads.no",
              )}
            </dd>
          </div>
          {path && auto === "false" && (
            <div>
              <dt>{t("send.path")}</dt>
              <dd>{path}</dd>
            </div>
          )}
          {
            <>
              <div>
                <dt>{t("downloads.concurrency")}</dt>
                <dd>{concurrency || t("send.inherit")}</dd>
              </div>
              <div>
                <dt>{t("send.multipleHashes")}</dt>
                <dd>{t(`send.${multipleHashes}`)}</dd>
              </div>
            </>
          }
        </dl>
        <p>{t("send.defaults")}</p>
        <ModalActions>
          <Button variant="default" onClick={() => setConfirmed(false)}>
            {t("import.cancel")}
          </Button>
          <Button disabled={active || !canSend} onClick={send}>
            {t("send.confirm")}
          </Button>
        </ModalActions>
      </Modal>
    </>
  );
}
