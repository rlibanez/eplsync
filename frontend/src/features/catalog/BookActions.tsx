import { useState } from "react";
import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { Alert, Button, Menu, Select } from "@mantine/core";
import { Download, Magnet, ExternalLink } from "lucide-react";
import { useTranslation } from "react-i18next";
import { get } from "../../api/catalog";
import { ActionFailure, post } from "../downloads/shared";
interface Sent {
  eplId: number;
  hash: string;
  client: string;
  status: "ACCEPTED" | "ALREADY_EXISTS";
}
export function BookActions({ eplId }: { eplId: number }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
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
  const send = useMutation({
    mutationKey: ["send-books"],
    meta: { feedbackPath: `/catalog/${eplId}` },
    retry: false,
    mutationFn: () =>
      post<Sent>(
        `/torrent/books/${eplId}`,
        hashes.length > 1 ? { hash } : undefined,
      ),
    onSuccess: () => {
      for (const key of ["book", "catalog", "downloads", "download-summary"])
        void cache.invalidateQueries({ queryKey: [key] });
    },
  });
  return (
    <section className="book-actions" aria-label={t("detail.actions")}>
      {hashes.length > 1 && (
        <Select
          className="book-hash-picker"
          label={t("detail.chooseTorrent")}
          description={t("detail.multipleTorrents")}
          data={hashes}
          value={hash}
          onChange={setHash}
          allowDeselect={false}
        />
      )}
      <div className="action-row">
        <Button
          leftSection={<Download size={17} />}
          loading={send.isPending}
          disabled={
            pending ||
            magnets.isPending ||
            (magnets.isSuccess && !hashes.length) ||
            (hashes.length > 1 && (!hash || !hashes.includes(hash)))
          }
          onClick={() => send.mutate()}
        >
          {t("send.fromBook")}
        </Button>
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
      <div aria-live="polite">
        <ActionFailure error={send.error} />
        {send.data && (
          <Alert color="green">
            {t(
              send.data.status === "ALREADY_EXISTS"
                ? "detail.alreadySent"
                : "detail.sent",
            )}
          </Alert>
        )}
      </div>
    </section>
  );
}
