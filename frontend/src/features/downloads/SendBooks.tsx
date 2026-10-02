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
import {
  Link,
  NavLink,
  useLocation,
  useNavigate,
  useSearchParams,
} from "react-router-dom";
import { useTranslation } from "react-i18next";
import { get, languages, type Book, type BookPage } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, post, type Job } from "./shared";
interface Sent {
  eplId: number;
  hash: string;
  client: string;
  status: string;
}
export function SendBooks() {
  const { t } = useTranslation();
  const { language, status } = useLocale();
  const navigate = useNavigate();
  const location = useLocation();
  const [search] = useSearchParams();
  const bulk = location.pathname.endsWith("multiple");
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
  const [selected, setSelected] = useState<Book | null>(null);
  const [hash, setHash] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [all, setAll] = useState(false);
  const [start, setStart] = useState("inherit");
  const [auto, setAuto] = useState("inherit");
  const [rename, setRename] = useState("inherit");
  const [path, setPath] = useState("");
  const [pattern, setPattern] = useState("");
  const [category, setCategory] = useState("");
  const [overrideCategory, setOverrideCategory] = useState(false);
  const [tags, setTags] = useState("");
  const [overrideTags, setOverrideTags] = useState(false);
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
  useEffect(() => {
    if (
      !bulk &&
      search.get("eplId") &&
      books.data?.items.length === 1 &&
      !selected
    )
      setSelected(books.data.items[0]);
  }, [bulk, search, books.data, selected]);
  const magnets = useQuery({
    queryKey: ["magnets", selected?.eplId],
    queryFn: ({ signal }) =>
      get<string[]>(`/catalog/books/${selected!.eplId}/magnets`, signal),
    enabled: !!selected && !bulk,
  });
  const hashes = [
    ...new Set(
      (magnets.data ?? []).flatMap((m) =>
        new URLSearchParams(m.slice(m.indexOf("?") + 1))
          .getAll("xt")
          .filter((x) => x.startsWith("urn:btih:"))
          .map((x) => x.slice(9)),
      ),
    ),
  ];
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
      post<Job | Sent>(url, body),
    onSuccess: (data) => {
      void cache.invalidateQueries({ queryKey: ["downloads"] });
      void cache.invalidateQueries({ queryKey: ["download-summary"] });
      void cache.invalidateQueries({ queryKey: ["book"] });
      void cache.invalidateQueries({ queryKey: ["jobs"] });
      if ("jobId" in data) navigate(`/downloads/jobs/${data.jobId}`);
    },
  });
  function options() {
    const value: Record<string, unknown> = {};
    if (start !== "inherit") value.start = start === "true";
    if (path.trim()) value.savePath = path.trim();
    const qb: Record<string, unknown> = {};
    if (auto !== "inherit") qb.autoManagement = auto === "true";
    if (overrideCategory) qb.category = category;
    if (overrideTags)
      qb.tags = tags
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);
    if (Object.keys(qb).length) value.qbittorrent = qb;
    if (rename !== "inherit" || pattern.trim())
      value.rename = {
        ...(rename !== "inherit" ? { enabled: rename === "true" } : {}),
        ...(pattern.trim() ? { pattern: pattern.trim() } : {}),
      };
    return value;
  }
  function send() {
    setConfirmed(false);
    const payload = options();
    if (!bulk) {
      const chosen = hash ?? hashes[0];
      if (chosen) payload.hash = chosen;
      mutation.mutate({
        url: `/torrent/books/${selected!.eplId}`,
        body: payload,
      });
    } else {
      const query = new URLSearchParams(filters!);
      query.append("sort", "title,asc");
      query.append("sort", "eplId,asc");
      if (!filters!.size) query.set("all", "true");
      mutation.mutate({
        url: `/torrent/books?${query}`,
        body: {
          options: payload,
          ...(concurrency ? { concurrency: Number(concurrency) } : {}),
          ...(batchSize ? { batchSize: Number(batchSize) } : {}),
          ...(interval.trim() ? { interval: interval.trim() } : {}),
          ...(multipleHashes !== "inherit" ? { multipleHashes } : {}),
        },
      });
    }
  }
  const invalidPath = !!path.trim() && auto !== "false";
  const canSend = bulk
    ? !!books.data?.meta.totalItems && (!filters?.size ? all : true)
    : !!selected &&
      magnets.isSuccess &&
      hashes.length > 0 &&
      (hashes.length === 1 || !!hash);
  const choices = [
    { value: "inherit", label: t("send.inherit") },
    { value: "true", label: t("downloads.yes") },
    { value: "false", label: t("downloads.no") },
  ];
  return (
    <>
      <h1>{t("nav.send")}</h1>
      <nav className="section-tabs" aria-label={t("nav.send")}>
        <NavLink to="/downloads/send" end>
          {t("send.individual")}
        </NavLink>
        <NavLink to="/downloads/send/multiple">{t("send.multiple")}</NavLink>
      </nav>
      <p className="muted">
        {t(bulk ? "send.multipleNote" : "send.individualNote")}
      </p>
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
            setSelected(null);
            setHash(null);
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
                      {!bulk && <th>{t("send.select")}</th>}
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
                        {!bulk && (
                          <td>
                            <Button
                              variant={
                                selected?.eplId === book.eplId
                                  ? "filled"
                                  : "default"
                              }
                              aria-pressed={selected?.eplId === book.eplId}
                              onClick={() => {
                                setSelected(book);
                                setHash(null);
                                mutation.reset();
                              }}
                            >
                              {t("send.select")}
                            </Button>
                          </td>
                        )}
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
              {bulk && <Alert>{t("send.allMatchesNote")}</Alert>}
              {bulk && !filters.size && (
                <Checkbox
                  mt="md"
                  label={t("send.allCatalog")}
                  checked={all}
                  onChange={(e) => setAll(e.currentTarget.checked)}
                />
              )}
            </>
          ))}
        {!bulk && selected && (
          <div className="selection-total">
            <strong>
              {selected.title} · EPL {selected.eplId}
            </strong>
            {magnets.isPending ? (
              <Loading />
            ) : magnets.isError ? (
              <Failure error={magnets.error} retry={() => magnets.refetch()} />
            ) : hashes.length ? (
              <Select
                label={t("send.hash")}
                value={hash ?? (hashes.length === 1 ? hashes[0] : null)}
                data={hashes}
                onChange={setHash}
                allowDeselect={false}
              />
            ) : (
              <Alert color="yellow">{t("send.noHash")}</Alert>
            )}
          </div>
        )}
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
          <div className="form-grid">
            <Select
              label={t("send.start")}
              value={start}
              data={choices}
              onChange={(v) => setStart(v ?? "inherit")}
            />
            <Select
              label={t("send.auto")}
              value={auto}
              data={choices}
              onChange={(v) => setAuto(v ?? "inherit")}
            />
            <TextInput
              label={t("send.path")}
              value={path}
              onChange={(e) => setPath(e.currentTarget.value)}
              error={invalidPath ? t("send.pathError") : undefined}
            />
          </div>
          <details>
            <summary>{t("send.advanced")}</summary>
            <div className="form-grid">
              <Select
                label={t("send.rename")}
                value={rename}
                data={choices}
                onChange={(v) => setRename(v ?? "inherit")}
              />
              <TextInput
                label={t("send.pattern")}
                value={pattern}
                onChange={(e) => setPattern(e.currentTarget.value)}
              />
              <div>
                <Checkbox
                  label={t("send.overrideCategory")}
                  checked={overrideCategory}
                  onChange={(e) => setOverrideCategory(e.currentTarget.checked)}
                />
                <TextInput
                  label={t("send.category")}
                  disabled={!overrideCategory}
                  value={category}
                  onChange={(e) => setCategory(e.currentTarget.value)}
                />
              </div>
              <div>
                <Checkbox
                  label={t("send.overrideTags")}
                  checked={overrideTags}
                  onChange={(e) => setOverrideTags(e.currentTarget.checked)}
                />
                <TextInput
                  label={t("send.tags")}
                  disabled={!overrideTags}
                  value={tags}
                  onChange={(e) => setTags(e.currentTarget.value)}
                />
              </div>
            </div>
            <p className="muted">{t("send.emptyOverrides")}</p>
          </details>
          {bulk && (
            <div className="form-grid">
              <TextInput
                label={t("downloads.concurrency")}
                type="number"
                min={1}
                max={16}
                value={concurrency}
                onChange={(e) => setConcurrency(e.currentTarget.value)}
              />
              <TextInput
                label={t("downloads.batchSize")}
                type="number"
                min={1}
                max={1000}
                value={batchSize}
                onChange={(e) => setBatchSize(e.currentTarget.value)}
              />
              <TextInput
                label={t("send.interval")}
                placeholder="500ms"
                value={interval}
                onChange={(e) => setInterval(e.currentTarget.value)}
              />
              <Select
                label={t("send.multipleHashes")}
                value={multipleHashes}
                onChange={(v) => setMultipleHashes(v ?? "inherit")}
                data={[
                  { value: "inherit", label: t("send.inherit") },
                  ...["all", "first", "skip"].map((value) => ({
                    value,
                    label: t(`send.${value}`),
                  })),
                ]}
              />
            </div>
          )}
        </section>
        <Button
          type="submit"
          loading={active}
          disabled={
            !canSend || invalidPath || books.isFetching || selectionDirty
          }
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
          {bulk
            ? t("send.confirmMultiple", {
                count: books.data?.meta.totalItems ?? 0,
              })
            : t("send.confirmIndividual", { title: selected?.title ?? "" })}
        </p>
        <p className="muted">{t("send.liveSelection")}</p>
        {bulk && (
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
        )}
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
          {path && (
            <div>
              <dt>{t("send.path")}</dt>
              <dd>{path}</dd>
            </div>
          )}
          {bulk && (
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
          )}
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
