import { SuggestionInput, type SuggestionKind } from "../../components/SuggestionInput";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Button, TextInput, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, type Page } from "../downloads/shared";
const fields = {
  authors: "author",
  collections: "collection",
  genres: "genres",
  languages: "language",
  years: "publicationYear",
} as const;
export function Directory() {
  const [search, setSearch] = useSearchParams();
  const value = search.get("section") ?? "authors";
  const kind = Object.hasOwn(fields, value)
    ? (value as keyof typeof fields)
    : "authors";
  return <DirectoryView key={`${kind}:${search.toString()}`} kind={kind} onSection={section => setSearch({ section })} />;
}
function DirectoryView({ kind, onSection }: { kind: keyof typeof fields; onSection: (section: string) => void }) {
  const { t } = useTranslation();
  const { language } = useLocale();
  const alphabetical = ["authors", "collections", "genres"].includes(kind);
  const [search, setSearch] = useSearchParams();
  const initialValue = search.get("initial") ?? "";
  const initial = alphabetical && /^[A-ZÑ#]$/.test(initialValue) ? initialValue : "";
  const requestedCentury = search.get("century") ?? "";
  const century = kind === "years" && /^(0|[1-9]|1[0-9]|2[01])$/.test(requestedCentury) ? requestedCentury : "";
  const centuries = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI"];
  const q = search.get("q") ?? "";
  const [draft, setDraft] = useState(q);
  const requestedPage = Number(search.get("page") ?? 0);
  const page = Number.isSafeInteger(requestedPage) && requestedPage >= 0 ? requestedPage : 0;
  const requestedSize = Number(search.get("size") ?? 20);
  const size = [10, 20, 50, 100, 200, 500, 1000].includes(requestedSize) ? requestedSize : 20;
  const updateSearch = (values: Record<string, string | number>) => {
    const next = new URLSearchParams(search);
    for (const [key, value] of Object.entries(values)) {
      if (value === "" || (key === "page" && value === 0)) next.delete(key);
      else next.set(key, String(value));
    }
    setSearch(next);
  };
  const result = useQuery({
    queryKey: ["directory", kind, q, initial, century, page, size],
    queryFn: ({ signal }) =>
      get<Page<{ value: string; initial?: string }>>(
        `/catalog/directory/${kind}?${new URLSearchParams({ q, initial, ...(century !== "" ? { century } : {}), page: String(page), size: String(size) })}`,
        signal,
      ),
  });
  return (
    <>
      <h1>{t("nav.directory")}</h1>
      <nav className="section-tabs" aria-label={t("nav.directory")}>
        {Object.keys(fields).map((key) => (
          <button
            key={key}
            className={kind === key ? "active" : ""}
            onClick={() => onSection(key)}
          >
            {t(`directory.${key}`)}
          </button>
        ))}
      </nav>
      <p className="muted">{t("directory.note")}</p>
      <form
        className="filters directory-filters"
        key={kind}
        onSubmit={(e) => {
          e.preventDefault();
          updateSearch({ q: draft.trim(), page: 0 });
        }}
      >
        {alphabetical ? <SuggestionInput kind={kind as SuggestionKind} name="q" label={t("catalog.search")} maxLength={512}
          value={draft} onChange={setDraft} onSelect={setDraft} /> :
          <TextInput name="q" label={t("catalog.search")} maxLength={512} value={draft} onChange={e => setDraft(e.currentTarget.value)} />}
        <Button type="submit">{t("catalog.search")}</Button>
        <Button variant="subtle" onClick={() => { setDraft(""); updateSearch({ q: "", initial: "", century: "", page: 0 }); }}>{t("catalog.clear")}</Button>
      </form>
      {alphabetical && (
        <nav className="directory-initials" aria-label={t("directory.initialFilter")}>
          {["", ..."ABCDEFGHIJKLMNÑOPQRSTUVWXYZ", "#"].map(letter => (
            <Button key={letter} size="compact-sm" variant={initial === letter ? "filled" : "subtle"}
              aria-pressed={initial === letter}
              onClick={() => { updateSearch({ initial: letter, page: 0 }); }}>
              {letter || t("directory.all")}
            </Button>
          ))}
        </nav>
      )}
      {kind === "years" && (
        <nav className="directory-initials" aria-label={t("directory.centuryFilter")}>
          {["", "0", ...centuries.map((_, index) => String(index + 1))].map(value => (
            <Tooltip key={value} label={value === "" ? t("directory.all")
              : value === "0" ? t("directory.nonPositiveYears")
              : t("directory.centuryRange", { century: centuries[Number(value) - 1], from: (Number(value) - 1) * 100 + 1, to: Number(value) * 100 })}>
              <Button size="compact-sm" variant={century === value ? "filled" : "subtle"}
                aria-pressed={century === value}
                onClick={() => updateSearch({ century: value, page: 0 })}>
                {value === "" ? t("directory.all") : value === "0" ? "≤0" : centuries[Number(value) - 1]}
              </Button>
            </Tooltip>
          ))}
        </nav>
      )}
      <section className="panel">
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <div>
            {!result.data.items.length && <p className="directory-empty">{t("downloads.empty")}</p>}
            {Array.from(result.data.items.reduce((groups, entry) => {
              const letter = alphabetical ? entry.initial ?? "#" : "";
              const values = groups.get(letter) ?? [];
              values.push(entry);
              groups.set(letter, values);
              return groups;
            }, new Map<string, { value: string; initial?: string }[]>())).map(([letter, entries]) => (
              <section className="directory-group" key={letter} aria-label={letter || undefined}>
                {alphabetical && <h2>{letter}</h2>}
                <div className="directory-grid">
                  {entries.map(entry => (
                    <Link key={entry.value} to={`/catalog?${new URLSearchParams({ [fields[kind]]: entry.value })}`}>
                      {kind === "languages" ? language(entry.value) : entry.value}
                    </Link>
                  ))}
                </div>
              </section>
            ))}
          </div>
        )}
        <Paging
          meta={result.data?.meta}
          page={page}
          size={size}
          onPage={(n) => updateSearch({ page: n })}
          onSize={(n) => {
            updateSearch({ size: n, page: 0 });
          }}
        />
      </section>
    </>
  );
}
