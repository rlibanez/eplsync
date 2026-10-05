import { useAuth } from "../auth/Auth";
import type { Book } from "../../api/catalog";
import { RepairCover } from "./RepairCover";
import { useState } from "react";
import { useIsMutating, useQuery } from "@tanstack/react-query";
import { SendSelection } from "../downloads/SendSelection";
import { Alert, Button, Menu } from "@mantine/core";
import { Download, Magnet, ExternalLink } from "lucide-react";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
export function BookActions({ book }: { book: Book }) {
  const { eplId } = book;
  const auth = useAuth();
  const { t } = useTranslation();
  const [hash, setHash] = useState<string | null>(null);
  const magnets = useQuery({
    queryKey: ["magnets", eplId],
    queryFn: ({ signal }) =>
      get<string[]>(`/catalog/books/${eplId}/magnets`, signal),
  });
  const links = Array.isArray(magnets.data)
    ? magnets.data.filter(
        (link) => typeof link === "string" && link.startsWith("magnet:?"),
      )
    : [];
  const hashes = [
    ...new Set(
      links.flatMap((link) =>
        new URLSearchParams(link.split("?")[1])
          .getAll("xt")
          .filter((xt) => xt.startsWith("urn:btih:"))
          .map((xt) => xt.slice(9)),
      ),
    ),
  ];
  const pending = useIsMutating({ mutationKey: ["send-books"] }) > 0;
  return (
    <section className="book-actions" aria-label={t("detail.actions")}>
      {hash && (
        <SendSelection
          single={{ eplId, hash }}
          filters={{ eplId: [eplId] }}
          count={1}
          allResults={false}
          onClose={() => setHash(null)}
        />
      )}
      <div className="action-row">
        {auth.can("TORRENT_SEND") &&
          (hashes.length > 1 ? (
            <Menu>
              <Menu.Target>
                <Button disabled={pending} leftSection={<Download size={17} />}>
                  {t("send.fromBook")}
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                {hashes.map((value, index) => (
                  <Menu.Item key={value} onClick={() => setHash(value)}>
                    {t("detail.magnetNumber", { number: index + 1 })} · {value}
                  </Menu.Item>
                ))}
              </Menu.Dropdown>
            </Menu>
          ) : (
            <Button
              leftSection={<Download size={17} />}
              disabled={pending || magnets.isPending || !hashes.length}
              onClick={() => setHash(hashes[0])}
            >
              {t("send.fromBook")}
            </Button>
          ))}
        {links.length === 1 ? (
          <Button
            component="a"
            href={links[0]}
            variant="light"
            leftSection={<Magnet size={17} />}
          >
            {t("detail.openMagnet")}
          </Button>
        ) : links.length > 1 ? (
          <Menu>
            <Menu.Target>
              <Button variant="light" leftSection={<Magnet size={17} />}>
                {t("detail.openMagnet")}
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              {links.map((link, index) => (
                <Menu.Item component="a" href={link} key={link}>
                  {t("detail.magnetNumber", { number: index + 1 })} ·{" "}
                  {new URLSearchParams(link.split("?")[1])
                    .get("xt")
                    ?.replace("urn:btih:", "")}
                </Menu.Item>
              ))}
            </Menu.Dropdown>
          </Menu>
        ) : (
          <Button variant="light" disabled leftSection={<Magnet size={17} />}>
            {t("detail.openMagnet")}
          </Button>
        )}
        <Button
          component="a"
          href={`https://www.epublibre.org/libro/detalle/${eplId}`}
          target="_blank"
          rel="noopener noreferrer"
          variant="light"
          leftSection={<ExternalLink size={17} />}
        >
          {t("detail.epublibre")}
        </Button>
        {auth.can("COVERS_MANAGE") && <RepairCover book={book} />}
      </div>
      {magnets.isError && (
        <Alert color="yellow">
          {t("detail.magnetError")}{" "}
          <Button
            variant="subtle"
            size="xs"
            onClick={() => void magnets.refetch()}
          >
            {t("feedback.retry")}
          </Button>
        </Alert>
      )}
      {magnets.isSuccess && !hashes.length && (
        <p className="muted">{t("send.noHash")}</p>
      )}
    </section>
  );
}
