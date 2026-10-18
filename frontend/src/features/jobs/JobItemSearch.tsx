import { TagsInput } from "@mantine/core";
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
};
const empty: Search = {
  ids: [],
  texts: [],
  excludeIds: false,
  excludeText: false,
  details: [],
  excludeDetail: false,
};
export function useJobItemSearch(
  textKey: "book" | "file",
  textLabel: string,
  resetPage: () => void,
  detailLabel?: string,
) {
  const { t } = useTranslation();
  const [value, setValue] = useBookViewState<Search>("jobItemSearch", empty);
  const change = (next: Search) => {
    setValue(next);
    resetPage();
  };
  const params = new URLSearchParams();
  value.ids.forEach((id) => params.append("eplId", id));
  value.texts.forEach((text) => params.append(textKey, text));
  if (value.ids.length && value.excludeIds) params.set("eplIdOperator", "ne");
  if (value.texts.length && value.excludeText)
    params.set(`${textKey}Operator`, "ne");
  if (detailLabel) {
    value.details?.forEach((text) => params.append("detail", text));
    if (value.details?.length && value.excludeDetail)
      params.set("detailOperator", "ne");
  }
  return {
    query: params.toString(),
    clear: () => change(empty),
    fields: (
      <>
        <TagsInput
          label="EPL ID"
          value={value.ids}
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
        {detailLabel && (
          <TagsInput
            label={detailLabel}
            value={value.details ?? []}
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
        )}
      </>
    ),
  };
}
