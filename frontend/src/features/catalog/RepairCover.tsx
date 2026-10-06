import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { useState } from "react";
import { Button, Tooltip } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Wrench } from "lucide-react";
import type { Book } from "../../api/catalog";
import { post } from "../downloads/shared";
import type { CoverReport, CoverResult } from "./coverApi";

export function RepairCover({ book }: { book: Book }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [decision, setDecision] = useState<CoverResult | null>(null);
  const update = (available: boolean) => {
    cache.setQueryData<Book>(["book", String(book.eplId)], (old) =>
      old ? { ...old, coverAvailable: available } : old,
    );
    void cache.invalidateQueries({ queryKey: ["book", String(book.eplId)] });
    void cache.invalidateQueries({ queryKey: ["catalog"] });
  };
  const check = useMutation({
    mutationKey: ["cover-check"],
    meta: {
      notice: {
        title: "covers.repair",
        error: "covers.repairError",
        href: `/catalog/${book.eplId}`,
      },
    },
    retry: false,
    mutationFn: () =>
      post<CoverReport>("/catalog/covers/check", {
        dryRun: false,
        eplId: book.eplId,
        onlyUnchecked: false,
      }),
    onError: () =>
      setDecision({
        eplId: book.eplId,
        coverUrl: book.coverUrl!,
        previousAvailable: book.coverAvailable,
        available: null,
        httpStatus: null,
        reason: "CHECK_ERROR",
        wouldChange: false,
        updated: false,
      }),
    onSuccess: (report) => {
      const result = report.items[0];
      if (!result) {
        void cache.invalidateQueries({
          queryKey: ["book", String(book.eplId)],
        });
        setDecision({
          eplId: book.eplId,
          coverUrl: book.coverUrl!,
          previousAvailable: book.coverAvailable,
          available: null,
          httpStatus: null,
          reason: "NO_COVER",
          wouldChange: false,
          updated: false,
        });
        return;
      }
      if (result.reason === "CONCURRENT_CHANGE") {
        setDecision(result);
        return;
      }
      if (result.available !== null) update(result.available);
      if (result.available !== false) setDecision(result);
    },
  });
  const force = useMutation({
    meta: {
      notice: {
        title: "covers.repair",
        success: "covers.repaired",
        error: "covers.forceError",
        href: `/catalog/${book.eplId}`,
      },
    },
    retry: false,
    mutationFn: () =>
      post(`/catalog/covers/${book.eplId}/alternative`, {
        expectedCoverUrl: decision?.coverUrl,
      }),
    onSuccess: () => {
      update(false);
      setDecision(null);
    },
  });
  const pending = check.isPending || force.isPending;
  const reason = decision?.reason || "HTTP_ERROR";
  const explanation = t(`covers.reasons.${reason}`, {
    defaultValue: t("covers.reasons.UNKNOWN"),
    status: decision?.httpStatus ?? "—",
  });
  return (
    <>
      <Tooltip
        label={t("covers.alreadyAlternative")}
        disabled={!!book.coverUrl}
        events={{ hover: true, focus: true, touch: true }}
      >
        <span
          style={{ display: "inline-flex" }}
          tabIndex={!book.coverUrl ? 0 : undefined}
        >
          <Button
            variant="light"
            leftSection={<Wrench size={17} />}
            loading={check.isPending}
            disabled={pending || !book.coverUrl}
            onClick={() => {
              check.reset();
              force.reset();
              check.mutate();
            }}
          >
            {t("covers.repair")}
          </Button>
        </span>
      </Tooltip>
      <Modal
        icon={Wrench}
        opened={decision !== null}
        onClose={() => {
          if (!force.isPending) setDecision(null);
        }}
        title={t("covers.repair")}
        closeOnClickOutside={!force.isPending}
        closeOnEscape={!force.isPending}
        withCloseButton={!force.isPending}
      >
        <p>{explanation}</p>
        {!["CONCURRENT_CHANGE", "NO_COVER"].includes(
          decision?.reason || "",
        ) && <p>{t("covers.forceDescription")}</p>}
        <ModalActions>
          <Button
            variant="default"
            disabled={force.isPending}
            onClick={() => setDecision(null)}
          >
            {t("covers.close")}
          </Button>
          {!["CONCURRENT_CHANGE", "NO_COVER"].includes(
            decision?.reason || "",
          ) && (
            <Button
              color="orange"
              loading={force.isPending}
              onClick={() => force.mutate()}
            >
              {t("covers.force")}
            </Button>
          )}
        </ModalActions>
      </Modal>
    </>
  );
}
