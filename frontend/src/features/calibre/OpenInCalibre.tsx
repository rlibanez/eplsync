import { useState } from "react";
import { Button, Menu } from "@mantine/core";
import { ExternalLink } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { get, type Book } from "../../api/catalog";
import { useLocale } from "../../locales/useLocale";
import { Loading, Failure } from "../../components/Feedback";
import { type CalibreBook } from "./CalibreTable";
import { type Page } from "../downloads/shared";
export function OpenInCalibre({ book }: { book: Book }) {
  const { t } = useTranslation();
  const { revision } = useLocale();
  const [opened, setOpened] = useState(false),
    [page, setPage] = useState(0);
  const copies = useQuery({
    queryKey: ["calibre-library", "open", book.eplId, page],
    queryFn: ({ signal }) =>
      get<Page<CalibreBook>>(
        `/calibre/books?eplId=${book.eplId}&size=100&page=${page}&sort=revision,desc`,
        signal,
      ),
    enabled: opened,
  });
  if (!book.calibre?.totalItems) return null;
  if (book.calibre.totalItems === 1 && book.calibre.items[0])
    return (
      <Button
        component="a"
        href={`/api/calibre/books/${book.calibre.items[0].calibreId}/open`}
        target="_blank"
        rel="noopener noreferrer"
        variant="light"
        leftSection={<ExternalLink size={16} />}
      >
        {t("calibre.open")}
      </Button>
    );
  return (
    <Menu opened={opened} onChange={setOpened} position="bottom-start">
      <Menu.Target>
        <Button variant="light" leftSection={<ExternalLink size={16} />}>
          {t("calibre.open")}
        </Button>
      </Menu.Target>
      <Menu.Dropdown style={{ maxHeight: 400, overflowY: "auto" }}>
        {copies.isPending ? (
          <Loading />
        ) : copies.isError ? (
          <Failure error={copies.error} retry={() => copies.refetch()} />
        ) : (
          <>
            {copies.data.items.map((item) => (
              <Menu.Item
                component="a"
                key={item.calibreId}
                href={`/api/calibre/books/${item.calibreId}/open`}
                target="_blank"
                rel="noopener noreferrer"
              >
                {t("calibre.copyLabel", {
                  revision:
                    item.revision == null ? "—" : revision(item.revision),
                  id: item.calibreId,
                })}
              </Menu.Item>
            ))}
            {copies.data.meta.hasPrevious && (
              <Menu.Item
                closeMenuOnClick={false}
                onClick={() => setPage(page - 1)}
              >
                {t("catalog.previous")}
              </Menu.Item>
            )}
            {copies.data.meta.hasNext && (
              <Menu.Item
                closeMenuOnClick={false}
                onClick={() => setPage(page + 1)}
              >
                {t("catalog.next")}
              </Menu.Item>
            )}
          </>
        )}
      </Menu.Dropdown>
    </Menu>
  );
}
