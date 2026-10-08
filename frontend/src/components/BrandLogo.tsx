import type { SVGProps } from "react";
import { logoArtwork } from "./logoArtwork";

type Props = Omit<SVGProps<SVGSVGElement>, "children"> & {
  size?: number;
  variant?: "empty" | "lined";
};

/** Decorative alongside the accessible EPL Sync name; inherits the theme accent. */
export function BrandLogo({ size = 32, variant = "empty", ...props }: Props) {
  const artwork =
    logoArtwork[
      size <= 32 ? (variant === "lined" ? "small-lined" : "small") : variant
    ];
  return (
    <svg
      width={size}
      height={size}
      viewBox="16 56 216 188"
      fill="currentColor"
      fillRule="evenodd"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      {artwork.map((path, index) => (
        <path key={index} {...path} />
      ))}
    </svg>
  );
}
