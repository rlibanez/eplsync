import { useState } from "react";
import { BookOpen } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { Book } from "../../api/catalog";

export function BookCover({
  book,
  detail = false,
}: {
  book: Pick<Book, "eplId" | "title" | "coverUrl" | "coverAvailable">;
  detail?: boolean;
}) {
  const { t } = useTranslation();
  const [failedUrl, setFailedUrl] = useState<string>();
  const candidate =
    (book.coverAvailable !== false && book.coverUrl?.trim()) ||
    `https://images.epublibre.org/libros/${book.eplId}.jpg`;
  let url: string | undefined;
  try {
    const parsed = new URL(candidate);
    if (["https:", "http:"].includes(parsed.protocol)) url = parsed.href;
  } catch {
    // Invalid stored URLs retain the placeholder; only absent URLs use ePubLibre.
  }
  const visible = url && failedUrl !== url;
  const content = visible ? (
    <img
      src={url}
      alt={detail ? t("detail.cover", { title: book.title }) : ""}
      loading={detail ? "eager" : "lazy"}
      decoding="async"
      referrerPolicy="no-referrer"
      onError={() => setFailedUrl(url)}
    />
  ) : (
    <>
      <BookOpen size={detail ? 52 : 18} aria-hidden="true" />
      {detail && <span>EPL {book.eplId}</span>}
    </>
  );
  const className = `${detail ? "detail-cover" : "mini-book"} book-cover${visible ? " has-image" : ""}`;
  return detail && url ? (
    <a
      className={className}
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={t("detail.openCover", { title: book.title })}
      title={t("detail.openCover", { title: book.title })}
    >
      {content}
    </a>
  ) : (
    <span className={className} aria-hidden="true">
      {content}
    </span>
  );
}
