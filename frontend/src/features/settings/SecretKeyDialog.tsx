import { useState } from "react";
import { Alert, Button, TextInput } from "@mantine/core";
import { Check, Copy, KeyRound, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { generateSecretKey } from "./secretKey";

export function SecretKeyDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [key, setKey] = useState("");
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  function generate() {
    setError("");
    setCopied(false);
    try {
      setKey(generateSecretKey());
    } catch {
      setKey("");
      setError("generationError");
    }
  }
  async function copy() {
    setError("");
    const text = `EPLSYNC_SECRET_KEY=${key}`;
    try {
      if (navigator.clipboard?.writeText)
        await navigator.clipboard.writeText(text);
      else {
        // Clipboard API may be unavailable on HTTP installations; preserve a manual fallback.
        const input = document.createElement("textarea");
        input.value = text;
        input.style.position = "fixed";
        input.style.opacity = "0";
        document.body.append(input);
        try {
          input.select();
          if (!document.execCommand("copy")) throw new Error();
        } finally {
          input.remove();
        }
      }
      setCopied(true);
    } catch {
      setCopied(false);
      setError("copyError");
    }
  }
  return (
    <AppModal
      icon={KeyRound}
      opened
      onClose={onClose}
      title={t("serverSettings.secretKey.title")}
    >
      <p>{t("serverSettings.secretKey.help")}</p>
      <TextInput
        label={t("serverSettings.secretKey.label")}
        value={key}
        readOnly
        autoComplete="off"
        onFocus={(event) => event.currentTarget.select()}
        styles={{ input: { fontFamily: "monospace" } }}
      />
      <p className="muted">{t("serverSettings.secretKey.instructions")}</p>
      {error && (
        <Alert color="red" role="alert">
          {t(`serverSettings.secretKey.${error}`)}
        </Alert>
      )}
      <ModalActions>
        <Button
          variant="default"
          leftSection={<RefreshCw size={16} />}
          onClick={generate}
        >
          {t("serverSettings.secretKey.generate")}
        </Button>
        <Button
          disabled={!key}
          leftSection={copied ? <Check size={16} /> : <Copy size={16} />}
          onClick={() => void copy()}
        >
          {t(`serverSettings.secretKey.${copied ? "copied" : "copy"}`)}
        </Button>
        <Button variant="default" onClick={onClose}>
          {t("serverSettings.secretKey.close")}
        </Button>
      </ModalActions>
    </AppModal>
  );
}
