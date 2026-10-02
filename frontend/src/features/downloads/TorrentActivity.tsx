import { useEffect } from "react";
import { useIsMutating } from "@tanstack/react-query";
export function TorrentActivity() {
  const pending = useIsMutating({ mutationKey: ["send-books"] }) > 0;
  useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pending]);
  return null;
}
