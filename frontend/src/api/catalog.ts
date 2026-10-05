import { secureFetch } from "../features/auth/transport";
export interface Book {
  eplId: number;
  title: string;
  author: string;
  revision: number;
  language: string | null;
  genres: string | null;
  collection: string | null;
  volume: number | null;
  publicationYear: number | null;
  synopsis: string | null;
  pages: number | null;
  publicationStatus: string | null;
  publicationDate: string | null;
  insertDate: string | null;
  lastModifiedDate: string | null;
  status: string | null;
  rating: number | null;
  votesCount: number | null;
  links: string | null;
  coverUrl: string | null;
  coverAvailable: boolean | null;
  download: {
    totalItems: number;
    statuses: string[];
    items: {
      id: string;
      revision: number | null;
      status: string;
      completed: boolean;
    }[];
  };
}
export interface BookPage {
  items: Book[];
  meta: {
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
    hasNext: boolean;
    hasPrevious: boolean;
  };
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public eventOperationId?: string | null,
    public details?: string,
  ) {
    super(`HTTP ${status}`);
  }
}
export class NetworkError extends Error {}
export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await secureFetch(`/api${path}`, {
      signal,
      headers: { Accept: "application/json" },
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new NetworkError("Network request failed");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(
      response.status,
      response.headers.get("X-EPLSync-Operation-Id"),
      typeof body?.details === "string" ? body.details : undefined,
    );
  }
  return response.json();
}
export const languages = [
  "es",
  "en",
  "ca",
  "gl",
  "eu",
  "fr",
  "it",
  "pt",
  "de",
  "eo",
  "sv",
  "other",
];
export const sorts: Record<string, string> = {
  "pages,asc": "pagesAsc",
  "pages,desc": "pagesDesc",
  "title,asc": "titleAsc",
  "title,desc": "titleDesc",
  "author,asc": "authorAsc",
  "author,desc": "authorDesc",
  "publicationYear,asc": "yearAsc",
  "publicationYear,desc": "yearDesc",
  "insertDate,asc": "addedAsc",
  "insertDate,desc": "addedDesc",
  "eplId,asc": "idAsc",
  "eplId,desc": "idDesc",
  "collection,asc": "collectionAsc",
  "collection,desc": "collectionDesc",
  "genres,asc": "genresAsc",
  "genres,desc": "genresDesc",
  "language,asc": "languageAsc",
  "language,desc": "languageDesc",
  "status,asc": "statusAsc",
  "status,desc": "statusDesc",
  "publicationStatus,asc": "publicationStatusAsc",
  "publicationStatus,desc": "publicationStatusDesc",
  "revision,asc": "revisionAsc",
  "revision,desc": "revisionDesc",
  "publicationDate,asc": "publishedAsc",
  "publicationDate,desc": "publishedDesc",
};
export function catalogSorts(input: URLSearchParams): string[] {
  const seen = new Set<string>();
  const values = input.getAll("sort").filter((value) => {
    const field = value.split(",")[0];
    if (!Object.hasOwn(sorts, value) || seen.has(field)) return false;
    seen.add(field);
    return true;
  });
  return values.length ? values : ["title,asc"];
}
export function catalogParams(
  input: URLSearchParams,
  includeTieBreaker = true,
) {
  const params = new URLSearchParams();
  for (const [key, max] of [
    ["title", 512],
    ["author", 255],
    ["genres", 512],
    ["collection", 255],
    ["pagesFrom", 10],
    ["pagesTo", 10],
    ["publicationYearFrom", 11],
    ["publicationYearTo", 11],
    ["publicationDate", 10],
    ["publicationDateFrom", 10],
    ["publicationDateTo", 10],
    ["addedFrom", 10],
    ["addedTo", 10],
    ["publicationStatus", 20],
    ["publicationYear", 11],
    ["eplId", 19],
    ["revision", 24],
  ] as const) {
    const multiple = [
      "title",
      "author",
      "genres",
      "collection",
      "publicationStatus",
      "eplId",
      "revision",
    ].includes(key);
    const values = multiple ? input.getAll(key) : [input.get(key) ?? ""];
    for (const raw of new Set(values)) {
      const value = raw.trim();
      if (value)
        params.append(
          key,
          key === "revision" ? value.replace(",", ".") : value.slice(0, max),
        );
    }
  }
  for (const status of input.getAll("status")) params.append("status", status);
  for (const language of new Set(input.getAll("language")))
    if (languages.includes(language)) params.append("language", language);
  const page = Number(input.get("page") ?? 0);
  params.set(
    "page",
    String(
      Number.isSafeInteger(page) && page >= 0 && page < 2147483647 ? page : 0,
    ),
  );
  const size = Number(input.get("size") ?? 20);
  params.set(
    "size",
    String([10, 20, 50, 100, 200, 500, 1000].includes(size) ? size : 20),
  );
  const ordering = catalogSorts(input);
  ordering.forEach((value) => params.append("sort", value));
  if (
    includeTieBreaker &&
    !ordering.some((value) => value.startsWith("eplId,"))
  )
    params.append("sort", "eplId,asc");
  return params;
}
