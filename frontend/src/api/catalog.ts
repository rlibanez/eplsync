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
  constructor(public status: number) {
    super(`HTTP ${status}`);
  }
}
export class NetworkError extends Error {}
export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      signal,
      headers: { Accept: "application/json" },
    });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new NetworkError("Network request failed");
  }
  if (!response.ok) throw new ApiError(response.status);
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
  "title,asc": "titleAsc",
  "title,desc": "titleDesc",
  "author,asc": "authorAsc",
  "publicationYear,desc": "yearDesc",
  "insertDate,desc": "addedDesc",
  "eplId,asc": "idAsc",
};
export function catalogParams(input: URLSearchParams) {
  const params = new URLSearchParams();
  for (const [key, max] of [
    ["title", 512],
    ["author", 255],
    ["genres", 512],
    ["collection", 255],
    ["publicationYearFrom", 4],
    ["publicationYearTo", 4],
    ["publicationDate", 10],
    ["publicationDateFrom", 10],
    ["publicationDateTo", 10],
    ["addedFrom", 10],
    ["addedTo", 10],
    ["publicationStatus", 20],
    ["publicationYear", 4],
    ["eplId", 19],
    ["revision", 24],
  ] as const) {
    const value = input.get(key)?.trim();
    if (value) params.set(key, value.slice(0, max));
  }
  for (const status of input.getAll("status")) params.append("status", status);
  const language = input.get("language");
  if (language && languages.includes(language))
    params.set("language", language);
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
  const sort = input.get("sort") ?? "title,asc";
  params.set("sort", Object.hasOwn(sorts, sort) ? sort : "title,asc");
  if (params.get("sort") !== "eplId,asc") params.append("sort", "eplId,asc");
  return params;
}
