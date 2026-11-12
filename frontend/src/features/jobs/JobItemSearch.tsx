import { useEffect, useState } from "react";
import { CloseButton, TagsInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { stateOperatorProps } from "../../components/StateOperator";
import { useBookViewState } from "../catalog/useBookViewState";

type Search = {
  ids: string[];
  texts: string[];
  excludeIds: boolean;
  excludeText: boolean;
  details: string[];
  excludeDetail: boolean;
  authors?: string[];
  excludeAuthor?: boolean;
};
const empty: Search = {
  ids: [],
  texts: [],
  excludeIds: false,
  excludeText: false,
  details: [],
  excludeDetail: false,
};
function normalizeEplId(value: string) {
  const id = value.trim().replace(/^0+/, "");
  return /^[0-9]{1,19}$/.test(id) && BigInt(id) <= 9223372036854775807n
    ? id
    : "";
}

function useLiveText(
  setPage: (page: number) => void,
  normalize = (value: string) => value.trim(),
) {
  const [draft, setDraft] = useState("");
  const [term, setTerm] = useState("");
  const trimmed = normalize(draft);

  useEffect(() => {
    if (!trimmed || trimmed === term) return;
    const timer = window.setTimeout(() => {
      setTerm(trimmed);
      setPage(0);
    }, 400);
    return () => window.clearTimeout(timer);
  }, [trimmed, term, setPage]);

  function change(next: string) {
    setDraft(next);
    if (!normalize(next) && term) {
      setTerm("");
      setPage(0);
    }
  }

  return {
    term,
    clear: () => change(""),
    inputProps: (label: string, hasTags: boolean) => ({
      searchValue: draft,
      onSearchChange: change,
      maxLength: 4096,
      rightSection:
        draft && !hasTags ? (
          <CloseButton
            aria-label={label}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => change("")}
          />
        ) : undefined,
    }),
  };
}

function terms(tags: string[], draft: string) {
  return draft && !tags.includes(draft) ? [...tags, draft] : tags;
}

export function useJobItemSearch(
  textKey: "book" | "file",
  textLabel: string,
  setPage: (page: number) => void,
  detailLabel?: string,
  authorLabel?: string,
  separateDetail = false,
) {
  const { t } = useTranslation();
  const [value, setValue] = useBookViewState<Search>("jobItemSearch", empty);
  const change = (next: Search) => {
    setValue(next);
    setPage(0);
  };
  const liveId = useLiveText(setPage, normalizeEplId);
  const liveText = useLiveText(setPage);
  const liveAuthor = useLiveText(setPage);
  const liveDetail = useLiveText(setPage);
  const ids = terms(value.ids, liveId.term);
  const texts = terms(value.texts, liveText.term);
  const authors = terms(value.authors ?? [], liveAuthor.term);
  const details = terms(value.details ?? [], liveDetail.term);
  const clearLabel = (label: string) => `${t("catalog.clear")}: ${label}`;

  const params = new URLSearchParams();
  ids.forEach((id) => params.append("eplId", id));
  texts.forEach((text) => params.append(textKey, text));
  if (ids.length && value.excludeIds) params.set("eplIdOperator", "ne");
  if (texts.length && value.excludeText) params.set(`${textKey}Operator`, "ne");
  if (authorLabel) {
    authors.forEach((author) => params.append("author", author));
    if (authors.length && value.excludeAuthor)
      params.set("authorOperator", "ne");
  }
  if (detailLabel) {
    details.forEach((text) => params.append("detail", text));
    if (details.length && value.excludeDetail)
      params.set("detailOperator", "ne");
  }
  const detailField = detailLabel && (
    <TagsInput
      label={detailLabel}
      value={value.details ?? []}
      {...liveDetail.inputProps(
        clearLabel(detailLabel),
        !!value.details?.length,
      )}
      clearable
      splitChars={[]}
      clearButtonProps={{
        "aria-hidden": false,
        tabIndex: 0,
        "aria-label": `${t("catalog.clear")}: ${detailLabel}`,
      }}
      {...stateOperatorProps(value.excludeDetail ?? false, () =>
        change({ ...value, excludeDetail: !value.excludeDetail }),
      )}
      onChange={(details) =>
        change({
          ...value,
          details,
          excludeDetail: details.length ? value.excludeDetail : false,
        })
      }
    />
  );
  return {
    query: params.toString(),
    detailField,
    clear: () => {
      liveId.clear();
      liveText.clear();
      liveAuthor.clear();
      liveDetail.clear();
      change(empty);
    },
    selectAuthor: (author: string) => {
      if (authorLabel && author.trim()) {
        liveAuthor.clear();
        change({ ...value, authors: [author.trim()], excludeAuthor: false });
      }
    },
    fields: (
      <>
        <TagsInput
          label="EPL ID"
          value={value.ids}
          {...liveId.inputProps(clearLabel("EPL ID"), !!value.ids.length)}
          clearable
          inputMode="numeric"
          splitChars={[",", ";", " "]}
          clearButtonProps={{
            "aria-hidden": false,
            tabIndex: 0,
            "aria-label": `${t("catalog.clear")}: EPL ID`,
          }}
          {...stateOperatorProps(value.excludeIds, () =>
            change({ ...value, excludeIds: !value.excludeIds }),
          )}
          onChange={(ids) =>
            change({
              ...value,
              ids,
              excludeIds: ids.length ? value.excludeIds : false,
            })
          }
        />
        <TagsInput
          label={textLabel}
          value={value.texts}
          {...liveText.inputProps(clearLabel(textLabel), !!value.texts.length)}
          clearable
          splitChars={[]}
          clearButtonProps={{
            "aria-hidden": false,
            tabIndex: 0,
            "aria-label": `${t("catalog.clear")}: ${textLabel}`,
          }}
          {...stateOperatorProps(value.excludeText, () =>
            change({ ...value, excludeText: !value.excludeText }),
          )}
          onChange={(texts) =>
            change({
              ...value,
              texts,
              excludeText: texts.length ? value.excludeText : false,
            })
          }
        />
        {authorLabel && (
          <TagsInput
            label={authorLabel}
            value={value.authors ?? []}
            {...liveAuthor.inputProps(
              clearLabel(authorLabel),
              !!value.authors?.length,
            )}
            clearable
            splitChars={[]}
            clearButtonProps={{
              "aria-hidden": false,
              tabIndex: 0,
              "aria-label": `${t("catalog.clear")}: ${authorLabel}`,
            }}
            {...stateOperatorProps(value.excludeAuthor ?? false, () =>
              change({ ...value, excludeAuthor: !value.excludeAuthor }),
            )}
            onChange={(authors) =>
              change({
                ...value,
                authors,
                excludeAuthor: authors.length ? value.excludeAuthor : false,
              })
            }
          />
        )}
        {!separateDetail && detailField}
      </>
    ),
  };
}
