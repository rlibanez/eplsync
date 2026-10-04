import { NumberInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { CoverOptions } from "../catalog/coverApi";
export const coverKeys = [
  "connectTimeoutMs",
  "requestTimeoutMs",
  "batchTimeoutMs",
  "concurrency",
] as const;
export function CoverParameter({
  name,
  value,
  onChange,
  disabled,
  modified,
}: {
  name: keyof CoverOptions;
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  modified?: boolean;
}) {
  const { t } = useTranslation();
  const concurrency = name === "concurrency";
  return (
    <NumberInput
      label={t(`covers.${name}`)}
      aria-label={t(`covers.${name}`)}
      description={t(`covers.${name}Help`)}
      value={concurrency ? value : value / 1000}
      min={concurrency ? 1 : 0.001}
      max={concurrency ? 32 : 300}
      step={1}
      allowDecimal={!concurrency}
      decimalScale={concurrency ? 0 : 3}
      required
      disabled={disabled}
      classNames={{ input: modified ? "send-option-modified" : undefined }}
      onChange={(value) =>
        onChange(
          typeof value === "number"
            ? Math.round(value * (concurrency ? 1 : 1000))
            : 0,
        )
      }
    />
  );
}
// Spring duration strings supplied by the settings API, including ISO-8601.
export function durationMs(value: string): number {
  const simple = value
    .trim()
    .match(/^([+-]?\d+(?:\.\d+)?)(ns|us|ms|s|m|h|d)?$/i);
  if (simple)
    return (
      Number(simple[1]) *
      ({
        ns: 0.000001,
        us: 0.001,
        ms: 1,
        s: 1000,
        m: 60000,
        h: 3600000,
        d: 86400000,
      }[simple[2]?.toLowerCase() ?? "ms"] ?? 1)
    );
  const iso = value.match(
    /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i,
  );
  return iso
    ? Number(iso[1] ?? 0) * 86400000 +
        Number(iso[2] ?? 0) * 3600000 +
        Number(iso[3] ?? 0) * 60000 +
        Number(iso[4] ?? 0) * 1000
    : 0;
}

export function validCoverOptions(options: CoverOptions): boolean {
  return Number.isInteger(options.concurrency) &&
    options.concurrency >= 1 && options.concurrency <= 32 &&
    Number.isFinite(options.connectTimeoutMs) && Number.isFinite(options.requestTimeoutMs) && Number.isFinite(options.batchTimeoutMs) &&
    options.connectTimeoutMs >= 1 &&
    options.connectTimeoutMs <= options.requestTimeoutMs &&
    options.requestTimeoutMs <= options.batchTimeoutMs &&
    options.batchTimeoutMs <= 300000;
}
