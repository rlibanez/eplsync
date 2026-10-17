import { Badge, Group } from "@mantine/core";
import { Download, Library } from "lucide-react";
import type { Book } from "../../api/catalog";
import { useAuth } from "../auth/Auth";
import { useLocale } from "../../locales/useLocale";

export function LibraryBadges({ book }: { book: Book }) {
  const auth = useAuth();
  const { status, revision } = useLocale();
  const torrent = auth.can("BOOK_HISTORY_READ") ? book.download?.latest : null;
  const calibre =
    auth.can("CALIBRE_READ") && (book.calibre?.totalItems ?? 0) > 0
      ? book.calibre
      : null;
  if (!torrent && !calibre) return null;
  const color =
    torrent?.status === "ERROR"
      ? "red"
      : torrent?.status === "DOWNLOADED"
        ? "teal"
        : "blue";
  return (
    <Group gap="xs" mt="sm" className="catalog-library-badges">
      {torrent && (
        <Badge
          variant="light"
          color={color}
          size="md"
          tt="none"
          leftSection={<Download size={13} />}
        >
          {status(torrent.status)}
          {torrent.revision != null && ` · r${revision(torrent.revision)}`}
        </Badge>
      )}
      {calibre && (
        <Badge
          variant="light"
          color="grape"
          size="md"
          tt="none"
          leftSection={<Library size={13} />}
        >
          Calibre
          {calibre.revision != null && ` · r${revision(calibre.revision)}`}
        </Badge>
      )}
    </Group>
  );
}
