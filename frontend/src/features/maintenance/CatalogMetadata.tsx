import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { Failure } from "../../components/Feedback";
export interface Metadata {
  sourceUrl: string;
  sourceFileName: string;
  sourceModifiedAt: string | null;
  importedAt: string;
  importMode: string;
  totalRows: number;
  insertedRows: number;
  updatedRows: number;
  unchangedRows: number;
  errorRows: number;
  missingRows?: number | null;
  durationMs: number;
  sourceSha256: string;
}
export function MetadataDetails({ metadata }: { metadata: Metadata }) {
  const { t, i18n } = useTranslation();
  return (
    <dl className="import-summary catalog-metadata">
      {(
        [
          "sourceModifiedAt",
          "importedAt",
          "importMode",
          "durationMs",
          "totalRows",
          "insertedRows",
          "updatedRows",
          "unchangedRows",
          "errorRows",
          "missingRows",
          "sourceFileName",
          "sourceUrl",
          "sourceSha256",
        ] as const
      ).map((key) => {
        const value = metadata[key];
        let display = value == null ? t("metadata.unknown") : String(value);
        // ZIP dates are local wall times. Do not parse them as browser-local instants.
        if (key === "sourceModifiedAt" && value)
          display = String(value).replace("T", " ");
        else if (key === "importedAt" && value)
          display = new Date(String(value)).toLocaleString(i18n.language);
        else if (key === "importMode")
          display = t(`metadata.modes.${value}`, {
            defaultValue: String(value),
          });
        else if (key === "durationMs")
          display = `${(Number(value) / 1000).toLocaleString(i18n.language)} s`;
        else if (typeof value === "number")
          display = value.toLocaleString(i18n.language);
        return (
          <div key={key}>
            <dt>{t(`metadata.${key}`)}</dt>
            <dd>{display}</dd>
          </div>
        );
      })}
    </dl>
  );
}
export function CurrentCatalog() {
  const { t } = useTranslation();
  const query = useQuery({
    queryKey: ["catalog-metadata"],
    queryFn: ({ signal }) =>
      get<{ metadata: Metadata | null }>("/catalog/import/metadata", signal),
  });
  return (
    <section className="panel settings-section">
      <h2>{t("metadata.title")}</h2>
      {query.isPending ? (
        <p>{t("metadata.loading")}</p>
      ) : query.isError ? (
        <Failure error={query.error} retry={() => query.refetch()} />
      ) : query.data.metadata ? (
        <MetadataDetails metadata={query.data.metadata} />
      ) : (
        <p className="muted">{t("metadata.empty")}</p>
      )}
    </section>
  );
}
