import {
  DataExportButton,
  type DataExportConfig,
} from "../catalog/CatalogDataExport";

const config: DataExportConfig = {
  endpoint: "/api/calibre/data/export",
  storageKey: "eplsync.calibre.dataExport.v1",
  filename: "calibre",
  defaultColumns: ["calibreId", "eplId", "title", "authors", "revision"],
  labels: {
    calibreId: "calibre.id",
    eplId: "filters.eplId",
    title: "downloads.book",
    authors: "filters.author",
    formats: "downloads.extension",
    revision: "calibre.libraryRevision",
    catalogRevision: "calibre.catalogRevision",
    comparison: "calibre.comparison",
    presence: "calibre.presence",
    hardcover: "hardcover.fields.SLUG",
    hardcoverBook: "hardcover.fields.BOOK_ID",
    hardcoverEdition: "hardcover.fields.EDITION_ID",
    hardcoverNoMatch: "hardcover.noMatch",
  },
};
export function CalibreDataExportButton({
  selected,
  ordering,
}: {
  selected: number[];
  ordering: string[];
}) {
  return (
    <DataExportButton
      config={config}
      filters={{ ids: selected }}
      count={selected.length}
      ordering={ordering}
    />
  );
}
