import { AppModal as Modal, ModalActions } from "../../components/AppModal";
import { useEffect, useState } from "react";
import { Alert, Button } from "@mantine/core";
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
  const [repaired, setRepaired] = useState(false);
  useEffect(() => {
    if (!repaired) return;
    const timer = window.setTimeout(() => setRepaired(false), 5000);
    return () => window.clearTimeout(timer);
  }, [repaired]);
  const update = (available: boolean) => {
    cache.setQueryData<Book>(["book", String(book.eplId)], (old) =>
      old ? { ...old, coverAvailable: available } : old,
    );
    void cache.invalidateQueries({ queryKey: ["book", String(book.eplId)] });
    void cache.invalidateQueries({ queryKey: ["catalog"] });
  };
  const check = useMutation({
    retry: false,
    mutationFn: () =>
      post<CoverReport>(
        `/catalog/covers/check?eplId=${book.eplId}&onlyUnchecked=false`,
      ),
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
      if (result.available === false) setRepaired(true);
      else setDecision(result);
    },
  });
  const force = useMutation({
    retry: false,
    mutationFn: () =>
      post(`/catalog/covers/${book.eplId}/alternative`, {
        expectedCoverUrl: decision?.coverUrl,
      }),
    onSuccess: () => {
      update(false);
      setDecision(null);
      setRepaired(true);
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
      <Button
        variant="light"
        leftSection={<Wrench size={17} />}
        loading={check.isPending}
        disabled={pending || !book.coverUrl}
        onClick={() => {
          setRepaired(false);
          check.reset();
          force.reset();
          check.mutate();
        }}
      >
        {t("covers.repair")}
      </Button>
      {!book.coverUrl && (
        <p className="muted cover-repair-feedback">
          {t("covers.alreadyAlternative")}
        </p>
      )}
      {repaired && (
        <Alert
          className="cover-repair-feedback"
          color="green"
          role="status"
          withCloseButton
          closeButtonLabel={t("covers.close")}
          onClose={() => setRepaired(false)}
        >
          {t("covers.repaired")}
        </Alert>
      )}
      {check.isError && !repaired && (
        <Alert className="cover-repair-feedback" color="red" role="alert">
          {t("covers.repairError")}
        </Alert>
      )}
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
        {force.isError && (
          <Alert color="red" role="alert">
            {t("covers.forceError")}
          </Alert>
        )}
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
