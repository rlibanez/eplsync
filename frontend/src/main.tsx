import { initializeI18n } from "./locales/i18n";
import React from "react";
import ReactDOM from "react-dom/client";
import { AppearanceProvider } from "./layout/Appearance";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { router } from "./app/router";
import "@mantine/core/styles.css";
import "./styles/global.css";
const client = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, retry: 1 } },
});
void initializeI18n().then(() => {
  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <AppearanceProvider>
        <QueryClientProvider client={client}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </AppearanceProvider>
    </React.StrictMode>,
  );
});
