import { bookViewSnapshot } from "./useBookViewState";
import { Link, useLocation, type LinkProps } from "react-router-dom";

/** Preserve the originating view without relying on the browser's previous entry. */
export function BookLink(props: LinkProps) {
  const location = useLocation();
  return (
    <Link
      {...props}
      state={{
        bookReturn: {
          pathname: location.pathname,
          search: location.search,
          hash: location.hash,
          state: {
            ...location.state,
            bookView: bookViewSnapshot(location.key),
          },
        },
      }}
    />
  );
}
