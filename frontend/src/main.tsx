import { initializeI18n } from "./locales/i18n";
import React from "react";
import ReactDOM from "react-dom/client";
import { AppearanceProvider } from "./layout/Appearance";
import { RouterProvider } from "react-router-dom";
import { router } from "./app/router";
import "@mantine/core/styles.css";
import "./styles/global.css";
import { AuthProvider } from "./features/auth/Auth";
void initializeI18n().then(() => {
  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <AppearanceProvider>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </AppearanceProvider>
    </React.StrictMode>,
  );
});
