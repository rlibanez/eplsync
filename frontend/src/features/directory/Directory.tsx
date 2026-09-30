import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Button, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { Paging, type Page } from "../downloads/shared";
const fields = {
  authors: "author",
  languages: "language",
  genres: "genres",
  years: "publicationYear",
} as const;
export function Directory() {
  const { t } = useTranslation();
  const { language } = useLocale();
  const [search, setSearch] = useSearchParams();
  const value = search.get("section") ?? "authors";
  const kind = Object.hasOwn(fields, value)
    ? (value as keyof typeof fields)
    : "authors";
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [q, setQ] = useState("");
  const result = useQuery({
    queryKey: ["directory", kind, q, page, size],
    queryFn: ({ signal }) =>
      get<Page<{ value: string }>>(
        `/catalog/directory/${kind}?${new URLSearchParams({ q, page: String(page), size: String(size) })}`,
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
            onClick={() => {
              setSearch({ section: key });
              setPage(0);
              setQ("");
            }}
          >
            {t(`directory.${key}`)}
          </button>
        ))}
      </nav>
      <p className="muted">{t("directory.note")}</p>
      <form
        className="filters"
        key={kind}
        onSubmit={(e) => {
          e.preventDefault();
          setQ(String(new FormData(e.currentTarget).get("q") ?? ""));
          setPage(0);
        }}
      >
        <TextInput name="q" label={t("catalog.search")} maxLength={512} />
        <Button type="submit">{t("catalog.search")}</Button>
      </form>
      <section className="panel">
        {result.isPending ? (
          <Loading />
        ) : result.isError ? (
          <Failure error={result.error} retry={() => result.refetch()} />
        ) : (
          <div className="directory-grid">
            {result.data.items.map((entry) => (
              <Link
                key={entry.value}
                to={`/catalog?${new URLSearchParams({ [fields[kind]]: entry.value })}`}
              >
                {kind === "languages" ? language(entry.value) : entry.value}
              </Link>
            ))}
            {!result.data.items.length && <p>{t("downloads.empty")}</p>}
          </div>
        )}
        <Paging
          meta={result.data?.meta}
          page={page}
          size={size}
          onPage={setPage}
          onSize={(n) => {
            setSize(n);
            setPage(0);
          }}
        />
      </section>
    </>
  );
}
