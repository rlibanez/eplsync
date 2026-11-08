import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Group,
  Loader,
  Button,
  Checkbox,
  Fieldset,
  CheckIcon,
  Select,
  TextInput,
} from "@mantine/core";
import { HardcoverCover } from "./HardcoverCover";
import { HardcoverTable } from "./HardcoverTable";
import { ActionIcon } from "@mantine/core";
import {
  BookMarked,
  Check,
  Pencil,
  ExternalLink,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { AppModal, ModalActions } from "../../components/AppModal";
import { TableAction } from "../../components/TableAction";
import { get } from "../../api/catalog";
import { secureFetch } from "../auth/transport";
import { useAuth } from "../auth/Auth";
import { post } from "../downloads/shared";

type Field = "SLUG" | "BOOK_ID" | "EDITION_ID" | "ISBN10" | "ISBN13" | "ASIN";
type Mapping = Partial<Record<Field, string>>;
type Configuration = {
  configured: boolean;
  fields: Field[];
  mappings: Mapping;
};
type Match = {
  id: number;
  slug: string;
  title: string;
  authors: string[];
  coverUrl?: string;
  ratingsCount?: number | null;
};
type Matches = {
  error?: string | null;
  noMatch?: boolean;
  token: string;
  calibreId: number;
  title: string;
  authors: string[];
  items: Match[];
  hasNext: boolean;
  page: number;
  notices?: string[];
  queryIsbn?: string;
  queryAsin?: string;
  identifiers?: Record<string, string>;
  coverUrl?: string | null;
};
type Edition = {
  id: number;
  title: string;
  language: string;
  publisher: string;
  isbn10: string;
  isbn13: string;
  asin: string;
  coverUrl?: string;
  usersCount?: number | null;
};
type Editions = { items: Edition[]; hasNext: boolean };
type EditionLanguage = { code: string; name: string };
const browserEditionLanguage = () => {
  const code = navigator.language.toLowerCase().split(/[-_]/)[0];
  return /^[a-z]{2,3}$/.test(code) ? code : "";
};
type Preview = {
  token: string;
  calibreId: number;
  title: string;
  saved: boolean;
  values: {
    field: Field;
    identifier: string;
    existing: string;
    value: string;
    state: string;
  }[];
};
const suggested: Record<Field, string> = {
  SLUG: "hardcover",
  BOOK_ID: "hardcover_book",
  EDITION_ID: "hardcover_edition",
  ISBN10: "isbn10",
  ISBN13: "isbn",
  ASIN: "asin",
};

export function CompleteHardcover({
  selected,
  inline = false,
}: {
  selected: number[];
  inline?: boolean;
}) {
  const auth = useAuth();
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [opened, setOpened] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [configuration, setConfiguration] = useState<Configuration | null>(
      null,
    ),
    [mapping, setMapping] = useState<Mapping>({});
  const [ids, setIds] = useState<number[]>([]),
    [index, setIndex] = useState(0),
    [savedCount, setSavedCount] = useState(0);
  const [started, setStarted] = useState(false),
    [matches, setMatches] = useState<Matches | null>(null),
    [bookId, setBookId] = useState<string | null>(null);
  const [editions, setEditions] = useState<Editions>({
      items: [],
      hasNext: false,
    }),
    [editionPage, setEditionPage] = useState(1),
    [editionId, setEditionId] = useState<string | null>(null);
  const [editionLanguage, setEditionLanguage] = useState(
    browserEditionLanguage,
  );
  const [editionLanguages, setEditionLanguages] = useState<EditionLanguage[]>(
    [],
  );
  const preferredEditionLanguage = useRef(browserEditionLanguage());
  const [queryTitle, setQueryTitle] = useState(""),
    [queryAuthor, setQueryAuthor] = useState("");
  const savedIds = useRef(new Set<number>());
  const [queryIsbn, setQueryIsbn] = useState(""),
    [queryAsin, setQueryAsin] = useState("");
  const [editingEdition, setEditingEdition] = useState(false),
    [editionDraft, setEditionDraft] = useState("");
  const [manualEdition, setManualEdition] = useState(false);
  const replaceFields = useRef<Field[]>([]);
  const removeFields = useRef<Field[]>([]);
  const [editingSlug, setEditingSlug] = useState(false);
  const [coverExpanded, setCoverExpanded] = useState(false);
  const [slugDraft, setSlugDraft] = useState(""),
    [manualBook, setManualBook] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [draftValues, setDraftValues] = useState<
    Partial<Record<Field, string>>
  >({});
  const previewRef = useRef<Preview | null>(null);
  const selectionRef = useRef<{ book: string | null; edition: string | null }>({
    book: null,
    edition: null,
  });
  function rememberPreview(
    value: Preview,
    book?: string | null,
    edition?: string | null,
    preserveManualValues = false,
  ) {
    previewRef.current = value;
    if (book !== undefined)
      selectionRef.current = { book, edition: edition ?? null };
    setPreview(value);
    if (preserveManualValues)
      setDraftValues((current) =>
        Object.fromEntries(
          Object.entries(current).filter(
            ([field]) => !["SLUG", "BOOK_ID", "EDITION_ID"].includes(field),
          ),
        ),
      );
    else setDraftValues({});
  }
  function resetPreview() {
    previewRef.current = null;
    selectionRef.current = { book: null, edition: null };
    setPreview(null);
    setDraftValues({});
  }

  const [previewPending, setPreviewPending] = useState(false);
  const [loadingMore, setLoadingMore] = useState<"books" | "editions" | null>(
    null,
  );
  const loadingMoreRef = useRef(false),
    bookViewport = useRef<HTMLDivElement>(null),
    editionViewport = useRef<HTMLDivElement>(null);
  const lookupCache = useRef(new Map<string, unknown>());
  async function cachedGet<T>(url: string, signal: AbortSignal): Promise<T> {
    if (lookupCache.current.has(url)) return lookupCache.current.get(url) as T;
    const data = await get<T>(url, signal);
    if (!signal.aborted) {
      lookupCache.current.set(url, data);
      while (lookupCache.current.size > 128)
        lookupCache.current.delete(lookupCache.current.keys().next().value!);
    }
    return data;
  }
  const running = useRef<AbortController | null>(null),
    token = useRef<string | null>(null);
  const discard = () => {
    lookupCache.current.clear();
    const value = token.current;
    token.current = null;
    if (value)
      void post("/calibre/hardcover/discard", { token: value }).catch(
        () => undefined,
      );
  };
  useEffect(
    () => () => {
      running.current?.abort();
      discard();
    },
    [],
  );
  const retryOperation = useRef<
    ((signal: AbortSignal) => Promise<void>) | null
  >(null);
  function reportError(
    message: string,
    retry?: (signal: AbortSignal) => Promise<void>,
  ) {
    retryOperation.current = retry ?? null;
    setError(message);
  }
  async function execute(
    work: (signal: AbortSignal) => Promise<void>,
    background = false,
  ) {
    running.current?.abort();
    const request = new AbortController();
    running.current = request;
    if (!background) setBusy(true);
    reportError("");
    try {
      await work(request.signal);
    } catch (e) {
      if (!request.signal.aborted)
        reportError(
          (e as { details?: string }).details || t("hardcover.failed"),
          work,
        );
    } finally {
      if (running.current === request) {
        running.current = null;
        if (!background) setBusy(false);
      }
    }
  }
  function close() {
    running.current?.abort();
    running.current = null;
    discard();
    setBusy(false);
    setOpened(false);
    reportError("");
    void queryClient.invalidateQueries({ queryKey: ["calibre-library"] });
  }
  function open() {
    replaceFields.current = [];
    removeFields.current = [];
    preferredEditionLanguage.current = browserEditionLanguage();
    setEditionLanguage(preferredEditionLanguage.current);
    setEditionLanguages([]);
    setIds([...selected]);
    setIndex(0);
    setSavedCount(0);
    savedIds.current.clear();
    setStarted(false);
    resetPreview();
    setPreviewPending(false);
    setManualBook(false);
    setSlugDraft("");
    setEditingSlug(false);
    setEditingEdition(false);
    setManualEdition(false);
    setEditionDraft("");
    setMatches(null);
    setBookId(null);
    setEditionId(null);
    setEditions({ items: [], hasNext: false });
    setConfiguration(null);
    setOpened(true);
    void execute(async (signal) => {
      const data = await get<Configuration>("/calibre/hardcover", signal);
      if (signal.aborted) return;
      setConfiguration(data);
      setMapping(data.mappings);
    });
  }
  async function search(at: number, page = 1, custom = false) {
    const prior = page === 1 ? null : matches?.token;
    if (page === 1) {
      replaceFields.current = [];
      removeFields.current = [];
    }
    if (page === 1 && custom) setPreviewPending(true);
    if (page === 1 && !custom) {
      discard();
      setMatches(null);
      setManualBook(false);
      setBookId(null);
      setEditionId(null);
      setEditions({ items: [], hasNext: false });
      resetPreview();
    }
    await execute(async (signal) => {
      const data = await post<Matches>(
        "/calibre/hardcover/search",
        {
          calibreId: ids[at],
          token: prior,
          page,
          useTitle: custom ? !!queryTitle.trim() : true,
          useAuthor: custom ? !!queryAuthor.trim() : true,
          useIsbn: custom ? !!queryIsbn.trim() : true,
          useAsin: custom ? !!queryAsin.trim() : true,
          ...(custom ? { queryTitle, queryAuthor, queryIsbn, queryAsin } : {}),
        },
        signal,
      );
      if (signal.aborted) return;
      if (page === 1 && token.current && token.current !== data.token)
        discard();
      token.current = data.token;
      setMatches(data);
      setSlugDraft("");
      setEditionDraft("");
      setManualBook(false);
      setManualEdition(false);
      setEditingEdition(false);
      setEditingSlug(false);
      if (page === 1 && !custom) {
        setQueryTitle(data.title);
        setQueryAuthor(data.authors.join(", "));
        setQueryIsbn(data.queryIsbn ?? "");
        setQueryAsin(data.queryAsin ?? "");
      }
      setBookId(null);
      setEditionId(null);
      resetPreview();
      setEditions({ items: [], hasNext: false });
      setPreviewPending(false);
      if (data.error) throw { details: data.error };
      if (page === 1 && !data.hasNext && data.items.length === 1)
        await chooseBook(data, String(data.items[0].id), signal);
    });
  }
  async function loadMore(kind: "books" | "editions") {
    const viewport =
      kind === "books" ? bookViewport.current : editionViewport.current;
    if (
      busy ||
      running.current ||
      loadingMoreRef.current ||
      !matches ||
      !viewport ||
      viewport.scrollTop <= 0 ||
      (kind === "books" ? !matches.hasNext : !editions.hasNext || !bookId)
    )
      return;
    loadingMoreRef.current = true;
    setLoadingMore(kind);
    try {
      await execute(async (signal) => {
        if (kind === "books") {
          const next = await post<Matches>(
            "/calibre/hardcover/search",
            {
              calibreId: ids[index],
              token: matches.token,
              page: matches.page + 1,
            },
            signal,
          );
          if (next.error && !signal.aborted) throw { details: next.error };
          if (!signal.aborted)
            setMatches((current) =>
              current?.token === matches.token
                ? {
                    ...next,
                    items: [
                      ...current.items,
                      ...next.items.filter(
                        (item) =>
                          !current.items.some((old) => old.id === item.id),
                      ),
                    ],
                  }
                : current,
            );
        } else {
          const next = await cachedGet<Editions>(
            `/calibre/hardcover/editions?token=${encodeURIComponent(matches.token)}&bookId=${bookId}&page=${editionPage + 1}&language=${encodeURIComponent(editionLanguage)}`,
            signal,
          );
          if (!signal.aborted) {
            setEditions((current) => ({
              ...next,
              items: [
                ...current.items,
                ...next.items.filter(
                  (item) => !current.items.some((old) => old.id === item.id),
                ),
              ],
            }));
            setEditionPage(editionPage + 1);
          }
        }
      }, true);
    } finally {
      loadingMoreRef.current = false;
      setLoadingMore(null);
    }
  }
  async function initialEditions(
    source: string,
    book: string,
    signal: AbortSignal,
    requestedLanguage?: string,
  ) {
    const languages = await cachedGet<EditionLanguage[]>(
      `/calibre/hardcover/edition-languages?token=${encodeURIComponent(source)}&bookId=${book}`,
      signal,
    );
    const preferred = requestedLanguage ?? preferredEditionLanguage.current;
    const language = languages.some((l) => l.code === preferred)
      ? preferred
      : "";
    const data = await cachedGet<Editions>(
      `/calibre/hardcover/editions?token=${encodeURIComponent(source)}&bookId=${book}&page=1&language=${encodeURIComponent(language)}`,
      signal,
    );
    return { data, languages, language };
  }
  async function chooseBook(
    source: Matches,
    value: string,
    signal: AbortSignal,
  ) {
    replaceFields.current = [];
    removeFields.current = [];
    setManualEdition(false);
    setBookId(value);
    setEditionId(null);
    resetPreview();
    setEditions({ items: [], hasNext: false });
    setEditionPage(1);
    const setup = needsEdition
      ? await initialEditions(source.token, value, signal)
      : { data: { items: [], hasNext: false }, languages: [], language: "" };
    const data = setup.data;
    if (signal.aborted) return;
    const only =
      data.items.length === 1 && !data.hasNext
        ? String(data.items[0].id)
        : null;
    setEditions(data);
    setEditionLanguages(setup.languages);
    setEditionLanguage(setup.language);
    setEditionId(only);
    await refreshPreview(source.token, value, only, signal);
  }
  async function loadEditions(
    value: string,
    page: number,
    language = editionLanguage,
  ) {
    if (!matches) return;
    setManualEdition(false);
    setPreviewPending(true);
    setEditionId(null);
    await execute(async (signal) => {
      const data = await cachedGet<Editions>(
        `/calibre/hardcover/editions?token=${encodeURIComponent(matches.token)}&bookId=${value}&page=${page}&language=${encodeURIComponent(language)}`,
        signal,
      );
      if (!signal.aborted) {
        setEditions(data);
        setEditionPage(page);
        setEditionLanguage(language);
        const only =
          page === 1 && !data.hasNext && data.items.length === 1
            ? String(data.items[0].id)
            : null;
        setEditionId(only);
        await refreshPreview(matches.token, value, only, signal);
      }
    });
  }
  async function refreshPreview(
    source: string,
    book: string | null,
    edition: string | null,
    signal: AbortSignal,
  ) {
    setPreviewPending(true);
    const result = await post<Preview>(
      "/calibre/hardcover/preview",
      {
        token: source,
        bookId: book ? Number(book) : null,
        editionId: edition ? Number(edition) : null,
        mappings: mapping,
        ...(removeFields.current.length
          ? { removeFields: removeFields.current }
          : {}),
        ...(replaceFields.current.length
          ? { replaceFields: replaceFields.current }
          : {}),
      },
      signal,
    );
    if (!signal.aborted) {
      rememberPreview(result, book, edition);
      setPreviewPending(false);
      setEditingEdition(false);
      setEditionDraft(
        result.values.find((row) => row.field === "EDITION_ID")?.value ?? "",
      );
      setEditingSlug(false);
      setSlugDraft(
        result.values.find((row) => row.field === "SLUG")?.value ?? "",
      );
    }
  }
  async function resolveSlug(byId?: string) {
    if (!matches) return;
    if (!byId && !slugDraft.trim()) {
      setDraftValues((current) => ({ ...current, SLUG: "" }));
      setEditingSlug(false);
      return;
    }
    removeFields.current = removeFields.current.filter(
      (field) => field !== "SLUG",
    );
    await execute(async (signal) => {
      const book = await post<Match>(
        byId
          ? "/calibre/hardcover/resolve-book"
          : "/calibre/hardcover/resolve-slug",
        byId
          ? { token: matches.token, bookId: Number(byId) }
          : { token: matches.token, slug: slugDraft.trim() },
        signal,
      );
      if (signal.aborted) return;
      const setup = needsEdition
        ? await initialEditions(matches.token, String(book.id), signal)
        : { data: { items: [], hasNext: false }, languages: [], language: "" };
      const data = setup.data;
      if (signal.aborted) return;
      const only =
        data.items.length === 1 && !data.hasNext
          ? String(data.items[0].id)
          : null;
      const result = await post<Preview>(
        "/calibre/hardcover/preview",
        {
          token: matches.token,
          bookId: book.id,
          editionId: only ? Number(only) : null,
          mappings: mapping,
          ...(removeFields.current.length
            ? { removeFields: removeFields.current }
            : {}),
          replaceFields: Object.keys(mapping).filter((field) =>
            ["SLUG", "BOOK_ID", "EDITION_ID"].includes(field),
          ),
        },
        signal,
      );
      if (signal.aborted) return;
      setMatches((current) =>
        current
          ? {
              ...current,
              items: [...current.items.filter((b) => b.id !== book.id), book],
            }
          : current,
      );
      setBookId(String(book.id));
      setEditionId(only);
      setEditionPage(1);
      setEditions(data);
      setEditionLanguages(setup.languages);
      setEditionLanguage(setup.language);
      replaceFields.current = Object.keys(mapping).filter((field) =>
        ["SLUG", "BOOK_ID", "EDITION_ID"].includes(field),
      ) as Field[];
      setManualBook(true);
      setManualEdition(false);
      rememberPreview(result, String(book.id), only, true);
      setPreviewPending(false);
      setEditingEdition(false);
      setEditionDraft(
        result.values.find((row) => row.field === "EDITION_ID")?.value ?? "",
      );
      setEditingSlug(false);
      setSlugDraft(
        result.values.find((row) => row.field === "SLUG")?.value ?? "",
      );
    });
  }
  async function resolveEdition() {
    if (!editionDraft.trim()) {
      setDraftValues((current) => ({ ...current, EDITION_ID: "" }));
      setEditingEdition(false);
      return;
    }
    removeFields.current = removeFields.current.filter(
      (field) => field !== "EDITION_ID",
    );
    if (
      !matches ||
      !/^[1-9][0-9]{0,9}$/.test(editionDraft.trim()) ||
      Number(editionDraft) > 2147483647
    ) {
      reportError(t("hardcover.invalidEdition"));
      return;
    }
    await execute(async (signal) => {
      const linked = await post<{ book: Match; edition: Edition }>(
        "/calibre/hardcover/resolve-edition",
        { token: matches.token, editionId: Number(editionDraft) },
        signal,
      );
      const setup = await initialEditions(
        matches.token,
        String(linked.book.id),
        signal,
        linked.edition.language,
      );
      if (signal.aborted) return;
      const result = await post<Preview>(
        "/calibre/hardcover/preview",
        {
          token: matches.token,
          bookId: linked.book.id,
          editionId: linked.edition.id,
          mappings: mapping,
          ...(removeFields.current.length
            ? { removeFields: removeFields.current }
            : {}),
          replaceFields: Object.keys(mapping).filter((field) =>
            ["SLUG", "BOOK_ID", "EDITION_ID"].includes(field),
          ),
        },
        signal,
      );
      if (signal.aborted) return;
      setMatches((current) =>
        current
          ? {
              ...current,
              items: [
                ...current.items.filter((book) => book.id !== linked.book.id),
                linked.book,
              ],
            }
          : current,
      );
      setBookId(String(linked.book.id));
      setEditionId(String(linked.edition.id));
      setEditionPage(1);
      setEditionLanguages(setup.languages);
      setEditionLanguage(setup.language);
      setEditions({
        ...setup.data,
        items: setup.data.items.some(
          (edition) => edition.id === linked.edition.id,
        )
          ? setup.data.items
          : [linked.edition, ...setup.data.items],
      });
      rememberPreview(
        result,
        String(linked.book.id),
        String(linked.edition.id),
        true,
      );
      setPreviewPending(false);
      setEditingSlug(false);
      setEditingEdition(false);
      setManualBook(false);
      replaceFields.current = Object.keys(mapping).filter((field) =>
        ["SLUG", "BOOK_ID", "EDITION_ID"].includes(field),
      ) as Field[];
      setManualEdition(true);
      setSlugDraft(
        result.values.find((row) => row.field === "SLUG")?.value ?? "",
      );
      setEditionDraft(String(linked.edition.id));
    });
  }
  const mapped = Object.entries(mapping);
  const names = mapped.map(([, name]) => name);
  const valid =
    mapped.length > 0 &&
    names.every(
      (name) =>
        name &&
        /^[a-z][a-z0-9_-]{0,63}$/.test(name) &&
        !["epl", "revision", "uuid", "calibre"].includes(name),
    ) &&
    new Set(names).size === names.length;
  const needsEdition = mapped.some(([field]) =>
    ["EDITION_ID", "ISBN10", "ISBN13", "ASIN"].includes(field),
  );
  const bookOptions =
    matches?.items.map((book) => ({
      value: String(book.id),
      label: `${book.title} · ${book.authors.join(", ")} · ${book.id}${book.ratingsCount != null ? ` · ${t("hardcover.ratingsCount", { count: book.ratingsCount })}` : ""}`,
    })) ?? [];
  const editionOptions = editions.items.map((edition) => ({
    value: String(edition.id),
    label: `${edition.title} · ${edition.language || "—"} · ${edition.publisher || "—"} · ${edition.isbn13 || edition.isbn10 || edition.id}${edition.usersCount != null ? ` · ${t("hardcover.usersCount", { count: edition.usersCount })}` : ""}`,
  }));
  const bookTooltip = [
    bookOptions.find((option) => option.value === bookId)?.label,
    t(
      matches?.notices?.includes("FALLBACK_SEARCH")
        ? "hardcover.fallbackOrder"
        : "hardcover.bookOrder",
    ),
  ]
    .filter(Boolean)
    .join("\n\n");
  const editionTooltip = [
    editionOptions.find((option) => option.value === editionId)?.label,
    t("hardcover.editionOrder"),
  ]
    .filter(Boolean)
    .join("\n\n");
  const basePreview: Preview | null =
    preview ??
    (matches
      ? {
          token: "",
          calibreId: matches.calibreId,
          title: matches.title,
          saved: false,
          values: (Object.keys(suggested) as Field[]).flatMap((field) =>
            mapping[field]
              ? [
                  {
                    field,
                    identifier: mapping[field]!,
                    existing: matches.identifiers?.[mapping[field]!] ?? "",
                    value: matches.identifiers?.[mapping[field]!] ?? "",
                    state: "UNCHANGED",
                  },
                ]
              : [],
          ),
        }
      : null);
  if (!auth.can("CALIBRE_READ") || !auth.can("CALIBRE_SEND")) return null;
  const displayedPreview = basePreview
    ? {
        ...basePreview,
        values: basePreview.values.map((row) => {
          const value = draftValues[row.field] ?? row.value;
          return {
            ...row,
            value,
            state:
              draftValues[row.field] === undefined && row.state === "INVALID"
                ? "INVALID"
                : value === row.existing
                  ? value
                    ? "UNCHANGED"
                    : "MISSING"
                  : !value
                    ? "REMOVE"
                    : !row.existing
                      ? "READY"
                      : "REPLACE",
          };
        }),
      }
    : null;
  const hasChanges = !!displayedPreview?.values.some(
    (row) => row.value !== row.existing,
  );
  async function saveChanges() {
    if (!matches || !basePreview) return;
    const drafts = { ...draftValues };
    const handled = new Set<Field>();
    const resolvedValues = new Map<Field, string>();
    for (const field of ["SLUG", "BOOK_ID", "EDITION_ID"] as Field[]) {
      const value = drafts[field]?.trim();
      if (
        !value ||
        value === basePreview.values.find((row) => row.field === field)?.value
      )
        continue;
      const previous = previewRef.current;
      if (field === "SLUG") await resolveSlug();
      else if (field === "BOOK_ID") await resolveSlug(value);
      else await resolveEdition();
      if (previewRef.current === previous) return;
      handled.add(field);
      resolvedValues.set(
        field,
        previewRef.current?.values.find((row) => row.field === field)?.value ??
          "",
      );
    }
    if (
      [...resolvedValues].some(
        ([field, value]) =>
          previewRef.current?.values.find((row) => row.field === field)
            ?.value !== value,
      )
    ) {
      reportError(t("hardcover.inconsistentIdentifiers"));
      return;
    }
    await execute(async (signal) => {
      let plan = previewRef.current;
      if (Object.keys(drafts).length || !plan) {
        const values = Object.fromEntries(
          (plan ?? basePreview).values.map((row) => [row.field, row.value]),
        );
        for (const [field, value] of Object.entries(drafts))
          if (!handled.has(field as Field)) values[field] = value;
        const selection = selectionRef.current;
        plan = await post<Preview>(
          "/calibre/hardcover/preview",
          {
            token: matches.token,
            bookId: selection.book ? Number(selection.book) : null,
            editionId:
              drafts.EDITION_ID === ""
                ? null
                : selection.edition
                  ? Number(selection.edition)
                  : null,
            mappings: mapping,
            values,
          },
          signal,
        );
        if (signal.aborted) return;
        rememberPreview(plan);
      }
      const result = await post<Preview>(
        "/calibre/hardcover/apply",
        { token: plan.token },
        signal,
      );
      if (signal.aborted) return;
      rememberPreview(result);
      setMatches((current) =>
        current ? { ...current, noMatch: false } : current,
      );
      void queryClient.invalidateQueries({
        queryKey: ["calibre-library"],
        refetchType: "none",
      });
      savedIds.current.add(ids[index]);
      setSavedCount(savedIds.current.size);
    });
  }

  return (
    <>
      {inline ? (
        <TableAction
          label={t("hardcover.open")}
          disabled={!selected.length}
          onClick={open}
        >
          <BookMarked size={16} aria-hidden="true" />
        </TableAction>
      ) : (
        <Button
          variant="light"
          leftSection={<BookMarked size={16} />}
          disabled={!selected.length}
          onClick={open}
        >
          {t("hardcover.open")}
        </Button>
      )}
      <AppModal
        opened={opened}
        onClose={close}
        closeOnEscape={!editingSlug && !editingEdition && !coverExpanded}
        title={t("hardcover.open")}
        icon={BookMarked}
        width="wide"
      >
        {error && (
          <Alert color="red" role="alert">
            <Group
              justify="space-between"
              align="center"
              wrap="nowrap"
              gap="sm"
            >
              <span style={{ flex: 1, minWidth: 0 }}>{error}</span>
              {retryOperation.current && (
                <Button
                  variant="light"
                  color="red"
                  disabled={busy}
                  onClick={() => {
                    const retry = retryOperation.current;
                    if (retry) void execute(retry);
                  }}
                >
                  {t("feedback.retry")}
                </Button>
              )}
            </Group>
          </Alert>
        )}
        {!started && configuration && (
          <>
            {!configuration.configured && (
              <Alert color="orange">
                <Trans
                  i18nKey="hardcover.noKey"
                  components={{
                    settings:
                      auth.user?.role === "ADMIN" ? (
                        <Link to="/settings/calibre#hardcover" />
                      ) : (
                        <span />
                      ),
                  }}
                />
              </Alert>
            )}
            <Fieldset
              legend={t("hardcover.destinationIdentifiers")}
              style={{ minWidth: 0 }}
            >
              <HardcoverTable
                label={t("hardcover.mapping")}
                headings={[
                  t("hardcover.use"),
                  t("hardcover.data"),
                  t("hardcover.identifier"),
                ]}
              >
                {configuration.fields.map((field) => (
                  <tr key={field}>
                    <td>
                      <Checkbox
                        aria-label={t("hardcover.useField", {
                          field: t(`hardcover.fields.${field}`),
                        })}
                        checked={mapping[field] !== undefined}
                        disabled={busy}
                        onChange={(event) => {
                          const checked = event.currentTarget.checked;
                          setMapping((current) => {
                            const next = { ...current };
                            if (checked) next[field] = suggested[field];
                            else delete next[field];
                            return next;
                          });
                        }}
                      />
                    </td>
                    <td>{t(`hardcover.fields.${field}`)}</td>
                    <td>
                      <TextInput
                        aria-label={t("hardcover.identifierFor", {
                          field: t(`hardcover.fields.${field}`),
                        })}
                        value={mapping[field] ?? ""}
                        disabled={busy || mapping[field] === undefined}
                        onChange={(event) => {
                          const value = event.currentTarget.value;
                          setMapping((current) => ({
                            ...current,
                            [field]: value,
                          }));
                        }}
                      />
                    </td>
                  </tr>
                ))}
              </HardcoverTable>
              {!valid && (
                <Alert color="orange">{t("hardcover.invalidMapping")}</Alert>
              )}
            </Fieldset>
          </>
        )}
        {started && (
          <>
            <Fieldset
              className="hardcover-section"
              legend={t("hardcover.localBook")}
            >
              <div className="hardcover-current-book">
                <HardcoverCover
                  onExpandedChange={setCoverExpanded}
                  url={
                    auth.can("CALIBRE_FILES_READ")
                      ? matches?.coverUrl
                      : undefined
                  }
                  label={t("hardcover.currentCover")}
                />
                <div className="hardcover-local-details">
                  <p className="hardcover-book-summary">
                    <span className="hardcover-book-title">
                      {matches?.title}
                    </span>
                    {matches?.authors.length
                      ? t("hardcover.byAuthor", {
                          author: matches.authors.join(", "),
                        })
                      : ""}
                    {" · "}
                    {t("hardcover.calibreId", { id: ids[index] })}
                  </p>
                  {matches && (
                    <>
                      <div className="hardcover-search-fields">
                        <TextInput
                          label={t("hardcover.queryTitle")}
                          value={queryTitle}
                          disabled={busy}
                          maxLength={500}
                          onChange={(e) => setQueryTitle(e.currentTarget.value)}
                        />
                        <TextInput
                          label={t("hardcover.queryAuthor")}
                          value={queryAuthor}
                          disabled={busy}
                          maxLength={1000}
                          onChange={(e) =>
                            setQueryAuthor(e.currentTarget.value)
                          }
                        />
                        <TextInput
                          label={t("hardcover.queryIsbn")}
                          value={queryIsbn}
                          disabled={busy}
                          maxLength={1000}
                          onChange={(e) => setQueryIsbn(e.currentTarget.value)}
                        />
                        <TextInput
                          label={t("hardcover.queryAsin")}
                          value={queryAsin}
                          disabled={busy}
                          maxLength={50}
                          onChange={(e) => setQueryAsin(e.currentTarget.value)}
                        />
                        <Button
                          variant="light"
                          ml="auto"
                          disabled={
                            busy ||
                            (!queryTitle.trim() &&
                              !queryAuthor.trim() &&
                              !queryIsbn.trim() &&
                              !queryAsin.trim())
                          }
                          onClick={() => void search(index, 1, true)}
                        >
                          {t("hardcover.customSearch")}
                        </Button>
                      </div>
                      {!!matches.notices?.filter(
                        (notice) =>
                          !["ISBN_UNAVAILABLE", "ASIN_UNAVAILABLE"].includes(
                            notice,
                          ),
                      ).length && (
                        <p className="muted" role="status">
                          {matches.notices
                            .filter(
                              (notice) =>
                                ![
                                  "ISBN_UNAVAILABLE",
                                  "ASIN_UNAVAILABLE",
                                ].includes(notice),
                            )
                            .map((notice) => t(`hardcover.notices.${notice}`))
                            .join(" · ")}
                        </p>
                      )}
                    </>
                  )}
                </div>
              </div>
            </Fieldset>
            {matches && (
              <>
                <Fieldset
                  className="hardcover-section"
                  legend={t("hardcover.book")}
                >
                  <div className="hardcover-subsection-title">
                    {t("hardcover.workSection")}
                  </div>
                  <div className="hardcover-book-selector">
                    <HardcoverCover
                      onExpandedChange={setCoverExpanded}
                      url={
                        matches.items.find((book) => String(book.id) === bookId)
                          ?.coverUrl
                      }
                      label={t("hardcover.bookCover")}
                    />
                    <div className="hardcover-book-controls">
                      <Select
                        aria-label={t("hardcover.book")}
                        title={bookTooltip}
                        scrollAreaProps={{
                          viewportRef: bookViewport,
                          onBottomReached: () => void loadMore("books"),
                        }}
                        rightSection={
                          loadingMore === "books" ? (
                            <Loader size={16} />
                          ) : undefined
                        }
                        value={bookId}
                        disabled={busy}
                        searchable
                        data={bookOptions}
                        renderOption={({ option, checked }) => (
                          <>
                            {checked && (
                              <CheckIcon size={12} style={{ flexShrink: 0 }} />
                            )}
                            <span title={option.label}>{option.label}</span>
                          </>
                        )}
                        onChange={(value) => {
                          setManualBook(false);
                          setManualEdition(false);
                          if (value)
                            void execute((signal) =>
                              chooseBook(matches, value, signal),
                            );
                        }}
                      />
                      <Button
                        component="a"
                        href={
                          bookId &&
                          matches.items.find((b) => String(b.id) === bookId)
                            ?.slug
                            ? `https://hardcover.app/books/${encodeURIComponent(matches.items.find((b) => String(b.id) === bookId)!.slug)}`
                            : undefined
                        }
                        target="_blank"
                        rel="noopener noreferrer"
                        variant="light"
                        className="hardcover-open-link"
                        aria-label={t("hardcover.openLink")}
                        leftSection={<ExternalLink size={16} />}
                        disabled={
                          !bookId ||
                          !matches.items.find((b) => String(b.id) === bookId)
                            ?.slug
                        }
                      >
                        <span className="hardcover-open-label">
                          {t("hardcover.openLink")}
                        </span>
                      </Button>
                    </div>
                  </div>
                  {!matches.error &&
                    !matches.items.length &&
                    !matches.notices?.includes("NO_CRITERIA") && (
                      <Alert>{t("hardcover.noMatches")}</Alert>
                    )}
                  {manualBook && (
                    <p className="muted hardcover-selection-note">
                      {t("hardcover.manualSelection")}
                    </p>
                  )}
                  {bookId && needsEdition && (
                    <div className="hardcover-edition-section">
                      <div className="hardcover-subsection-title">
                        {t("hardcover.editionSection")}
                      </div>
                      <div className="hardcover-edition-selectors">
                        <HardcoverCover
                          onExpandedChange={setCoverExpanded}
                          url={
                            editions.items.find(
                              (edition) => String(edition.id) === editionId,
                            )?.coverUrl
                          }
                          label={t("hardcover.editionCover")}
                        />
                        <div className="hardcover-edition-controls">
                          <div className="hardcover-language-selector">
                            <div
                              className="hardcover-language-measure"
                              aria-hidden="true"
                            >
                              <span>
                                {editionLanguage
                                  ? editionLanguage.toUpperCase()
                                  : t("hardcover.allLanguages")}
                              </span>
                            </div>
                            <Select
                              className="hardcover-language-input"
                              aria-label={t("hardcover.editionLanguage")}
                              title={t("hardcover.editionLanguage")}
                              value={editionLanguage}
                              disabled={busy}
                              data={[
                                {
                                  value: "",
                                  label: t("hardcover.allLanguages"),
                                },
                                ...editionLanguages.map((language) => ({
                                  value: language.code,
                                  label: language.code.toUpperCase(),
                                })),
                              ]}
                              onChange={(value) => {
                                const language = value ?? "";
                                preferredEditionLanguage.current = language;
                                void loadEditions(bookId, 1, language);
                              }}
                            />
                          </div>
                          <Select
                            className="hardcover-edition-input"
                            aria-label={t("hardcover.edition")}
                            title={editionTooltip}
                            scrollAreaProps={{
                              viewportRef: editionViewport,
                              onBottomReached: () => void loadMore("editions"),
                            }}
                            rightSection={
                              loadingMore === "editions" ? (
                                <Loader size={16} />
                              ) : undefined
                            }
                            placeholder={t("hardcover.bookOnly")}
                            clearable
                            searchable
                            disabled={busy}
                            value={editionId}
                            data={editionOptions}
                            renderOption={({ option, checked }) => (
                              <>
                                {checked && (
                                  <CheckIcon
                                    size={12}
                                    style={{ flexShrink: 0 }}
                                  />
                                )}
                                <span title={option.label}>{option.label}</span>
                              </>
                            )}
                            onChange={(value) => {
                              removeFields.current =
                                removeFields.current.filter(
                                  (field) => field !== "EDITION_ID",
                                );
                              if (!value && mapping.EDITION_ID)
                                removeFields.current.push("EDITION_ID");
                              replaceFields.current =
                                replaceFields.current.filter(
                                  (field) => field !== "EDITION_ID",
                                );
                              if (value && mapping.EDITION_ID)
                                replaceFields.current.push("EDITION_ID");
                              setEditionId(value);
                              setManualEdition(false);
                              setPreviewPending(true);
                              void execute((signal) =>
                                refreshPreview(
                                  matches.token,
                                  bookId,
                                  value,
                                  signal,
                                ),
                              );
                            }}
                          />
                          <Button
                            component="a"
                            href={
                              editionId &&
                              matches.items.find((b) => String(b.id) === bookId)
                                ?.slug
                                ? `https://hardcover.app/books/${encodeURIComponent(matches.items.find((b) => String(b.id) === bookId)!.slug)}/editions/${encodeURIComponent(editionId)}`
                                : undefined
                            }
                            target="_blank"
                            rel="noopener noreferrer"
                            variant="light"
                            className="hardcover-open-link"
                            aria-label={t("hardcover.openLink")}
                            leftSection={<ExternalLink size={16} />}
                            disabled={
                              !editionId ||
                              !matches.items.find(
                                (b) => String(b.id) === bookId,
                              )?.slug
                            }
                          >
                            <span className="hardcover-open-label">
                              {t("hardcover.openLink")}
                            </span>
                          </Button>
                        </div>
                      </div>
                      {!busy && editions.items.length === 0 && (
                        <p className="muted">
                          {t("hardcover.noEditionsLanguage")}
                        </p>
                      )}
                      {manualEdition && (
                        <p className="muted hardcover-selection-note">
                          {t("hardcover.manualEditionSelection")}
                        </p>
                      )}
                    </div>
                  )}
                </Fieldset>
              </>
            )}
            {displayedPreview && (
              <HardcoverTable
                label={t("hardcover.preview")}
                busy={busy && previewPending}
                headings={[
                  t("hardcover.identifier"),
                  t("hardcover.existing"),
                  t("hardcover.proposed"),
                  t("hardcover.result"),
                ]}
              >
                {displayedPreview.values.map((row) => (
                  <tr key={row.field}>
                    <td title={row.identifier}>{row.identifier}</td>
                    <td title={row.existing}>{row.existing || "—"}</td>
                    <td title={row.value}>
                      {!displayedPreview.saved ? (
                        <TextInput
                          variant="unstyled"
                          size="sm"
                          w="100%"
                          aria-label={
                            row.field === "SLUG"
                              ? t("hardcover.manualSlug")
                              : row.field === "EDITION_ID"
                                ? t("hardcover.manualEdition")
                                : t("hardcover.manualValue", {
                                    identifier: row.identifier,
                                  })
                          }
                          value={row.value}
                          disabled={busy}
                          onChange={(event) => {
                            const value = event.currentTarget.value;
                            setDraftValues((current) => ({
                              ...current,
                              [row.field]: value,
                            }));
                            if (row.field === "SLUG") {
                              setSlugDraft(value);
                              setEditingSlug(true);
                            }
                            if (row.field === "EDITION_ID") {
                              setEditionDraft(value);
                              setEditingEdition(true);
                            }
                          }}
                          onKeyDown={(event) => {
                            if (event.key === "Enter" && row.field === "SLUG") {
                              event.preventDefault();
                              void resolveSlug();
                            }
                            if (
                              event.key === "Enter" &&
                              row.field === "EDITION_ID"
                            ) {
                              event.preventDefault();
                              void resolveEdition();
                            }
                            if (event.key === "Escape") {
                              event.preventDefault();
                              event.stopPropagation();
                              setDraftValues((current) => {
                                const next = { ...current };
                                delete next[row.field];
                                return next;
                              });
                              setEditingSlug(false);
                              setEditingEdition(false);
                            }
                          }}
                          rightSection={
                            row.field === "SLUG" ||
                            row.field === "EDITION_ID" ? (
                              <ActionIcon
                                variant="subtle"
                                size="sm"
                                color={
                                  (
                                    row.field === "SLUG"
                                      ? editingSlug
                                      : editingEdition
                                  )
                                    ? "green"
                                    : undefined
                                }
                                aria-label={t(
                                  row.field === "SLUG"
                                    ? editingSlug
                                      ? "hardcover.checkSlug"
                                      : "hardcover.editSlug"
                                    : editingEdition
                                      ? "hardcover.checkEdition"
                                      : "hardcover.editEdition",
                                )}
                                disabled={busy}
                                onClick={() => {
                                  if (row.field === "SLUG") {
                                    if (editingSlug) void resolveSlug();
                                    else {
                                      setSlugDraft(row.value);
                                      setEditingSlug(true);
                                    }
                                  } else {
                                    if (editingEdition) void resolveEdition();
                                    else {
                                      setEditionDraft(row.value);
                                      setEditingEdition(true);
                                    }
                                  }
                                }}
                              >
                                {(
                                  row.field === "SLUG"
                                    ? editingSlug
                                    : editingEdition
                                ) ? (
                                  <Check size={16} />
                                ) : (
                                  <Pencil size={16} />
                                )}
                              </ActionIcon>
                            ) : undefined
                          }
                        />
                      ) : (
                        row.value || "—"
                      )}
                    </td>
                    <td
                      title={t(
                        `hardcover.states.${displayedPreview.saved && ["READY", "REPLACE", "REMOVE"].includes(row.state) ? "SAVED" : row.state}`,
                      )}
                    >
                      {t(
                        `hardcover.states.${displayedPreview.saved && ["READY", "REPLACE", "REMOVE"].includes(row.state) ? "SAVED" : row.state}`,
                      )}
                    </td>
                  </tr>
                ))}
              </HardcoverTable>
            )}
          </>
        )}
        {busy && <p role="status">{t("hardcover.loading")}</p>}
        <ModalActions
          summary={
            started ? (
              <div className="hardcover-footer-summary">
                <span className="hardcover-footer-counts">
                  <span>
                    {t("hardcover.position", {
                      current: index + 1,
                      total: ids.length,
                    })}
                  </span>
                  <span aria-hidden="true">{"\u00a0·\u00a0"}</span>
                  <span>
                    {t("hardcover.savedCount", { count: savedCount })}
                  </span>
                </span>
              </div>
            ) : undefined
          }
        >
          <Button variant="default" onClick={close}>
            {t("common.close")}
          </Button>
          {!started && (
            <Button
              disabled={busy || !valid || !configuration?.configured}
              onClick={() =>
                void execute(async (signal) => {
                  const response = await secureFetch("/api/calibre/hardcover", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(mapping),
                    signal,
                  });
                  const data = await response.json();
                  if (!response.ok) throw data;
                  if (signal.aborted) return;
                  setStarted(true);
                  setBookId(null);
                  setEditionId(null);
                  resetPreview();
                  setEditions({ items: [], hasNext: false });
                  // Start the first bounded lookup after saving this user's identifier mapping.
                  const result = await post<Matches>(
                    "/calibre/hardcover/search",
                    {
                      calibreId: ids[index],
                      page: 1,
                      useTitle: true,
                      useAuthor: true,
                      useIsbn: true,
                      useAsin: true,
                    },
                    signal,
                  );
                  if (!signal.aborted) {
                    if (token.current && token.current !== result.token)
                      discard();
                    token.current = result.token;
                    setMatches(result);
                    setQueryTitle(result.title);
                    setQueryAuthor(result.authors.join(", "));
                    setQueryIsbn(result.queryIsbn ?? "");
                    setQueryAsin(result.queryAsin ?? "");
                    if (result.error) throw { details: result.error };
                    if (!result.hasNext && result.items.length === 1)
                      await chooseBook(
                        result,
                        String(result.items[0].id),
                        signal,
                      );
                  }
                })
              }
            >
              {t("hardcover.search")}
            </Button>
          )}
          {started && matches && (
            <Button
              variant="outline"
              color="orange"
              disabled={busy || !!preview?.saved}
              onClick={() =>
                void execute(async (signal) => {
                  const noMatch = !matches.noMatch;
                  await post(
                    "/calibre/hardcover/review",
                    { token: matches.token, noMatch },
                    signal,
                  );
                  if (signal.aborted) return;
                  setMatches((current) =>
                    current ? { ...current, noMatch } : current,
                  );
                  void queryClient.invalidateQueries({
                    queryKey: ["calibre-library"],
                    refetchType: "none",
                  });
                  if (noMatch && index + 1 < ids.length) {
                    const next = index + 1;
                    setIndex(next);
                    await search(next);
                  }
                })
              }
            >
              {t(
                matches.noMatch
                  ? "hardcover.clearNoMatch"
                  : "hardcover.noMatch",
              )}
            </Button>
          )}
          {displayedPreview && !displayedPreview.saved && (
            <Button
              disabled={busy || previewPending || !hasChanges}
              onClick={() => void saveChanges()}
            >
              {t("hardcover.save")}
            </Button>
          )}
          {started && index > 0 && (
            <Button
              variant="light"
              leftSection={<ChevronLeft size={16} />}
              disabled={busy}
              onClick={() => {
                setIndex(index - 1);
                void search(index - 1);
              }}
            >
              {t("hardcover.previousBook")}
            </Button>
          )}
          {started && index + 1 < ids.length && (
            <Button
              variant="light"
              rightSection={<ChevronRight size={16} />}
              disabled={busy}
              onClick={() => {
                const next = index + 1;
                setIndex(next);
                void search(next);
              }}
            >
              {t("hardcover.nextBook")}
            </Button>
          )}
        </ModalActions>
      </AppModal>
    </>
  );
}
