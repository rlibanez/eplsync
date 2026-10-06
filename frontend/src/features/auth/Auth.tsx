import {
  createContext,
  useContext,
  useEffect,
  useState,
  useRef,
  type ReactNode,
  type FormEvent,
} from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Alert, Button, PasswordInput, Stack, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { AppModal, ModalActions } from "../../components/AppModal";
import { OptionLabel } from "../downloads/SendOptions";
import { ValidationTip } from "./ValidationTip";
import { validEmail, validUsername } from "./validation";
import { Check, Copy, KeyRound, WifiOff } from "lucide-react";
import { authRequest, resetCsrf, secureFetch } from "./transport";
export type Permission =
  | "CATALOG_READ"
  | "BOOK_HISTORY_READ"
  | "DOWNLOADS_READ"
  | "TORRENT_SEND"
  | "TORRENT_SYNC"
  | "TORRENT_JOBS_MANAGE"
  | "TORRENT_CLEANUP"
  | "TORRENT_FILES_DELETE"
  | "CATALOG_IMPORT"
  | "CATALOG_DELETE"
  | "COVERS_MANAGE"
  | "EVENTS_MANAGE"
  | "SETTINGS_MANAGE";
export interface Account {
  id: string;
  username: string;
  email: string;
  role: "ADMIN" | "USER";
  mustChangePassword: boolean;
  temporaryExpiresAt?: string | null;
  permissions: Permission[];
}
export interface TemporaryCredential {
  user: { username: string };
  password: string;
  expiresAt: string;
}
interface AuthState {
  user: Account | null;
  can: (permission: Permission) => boolean;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
  showTemporary: (credential: TemporaryCredential) => void;
}
const Context = createContext<AuthState | null>(null);
export function useAuth() {
  const value = useContext(Context);
  if (!value) throw new Error("Authentication provider missing");
  return value;
}
export function AuthProvider({ children }: { children: ReactNode }) {
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState("");
  const [temporary, setTemporary] = useState<TemporaryCredential>();
  const [user, setUser] = useState<Account | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const { t } = useTranslation();
  const generation = useRef(0);
  async function refresh() {
    const requestGeneration = ++generation.current;
    setRefreshing(true);
    try {
      const response = await secureFetch("/api/auth/me", { cache: "no-store" });
      if (requestGeneration !== generation.current) return;
      if (response.status === 401) {
        setUser(null);

        resetCsrf();
        setFailure("");
        return;
      }
      if (!response.ok) throw new Error(t("auth.unavailable"));
      const account: Account = await response.json();
      if (requestGeneration !== generation.current) return;
      setUser((previous) =>
        JSON.stringify(previous) === JSON.stringify(account)
          ? previous
          : account,
      );
      setFailure("");
    } catch (error) {
      if (requestGeneration !== generation.current) return;
      setFailure(error instanceof TypeError ? "connection" : "server");
    } finally {
      if (requestGeneration === generation.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }
  async function logout() {
    ++generation.current;
    const response = await secureFetch("/api/auth/logout", { method: "POST" });
    if (!response.ok && response.status !== 401)
      throw new Error(t("auth.unavailable"));
    ++generation.current;
    resetCsrf();
    setUser(null);
  }
  useEffect(() => {
    void refresh();
    const expired = () => {
      ++generation.current;
      setUser(null);

      resetCsrf();
    };
    window.addEventListener("eplsync-auth-expired", expired);
    const focused = () => void refresh();
    window.addEventListener("focus", focused);
    return () => {
      window.removeEventListener("eplsync-auth-expired", expired);
      window.removeEventListener("focus", focused);
    };
  }, []);
  const value = {
    user,
    can: (permission: Permission) =>
      !!user?.permissions.includes(permission) && !user.mustChangePassword,
    refresh,
    logout,
    showTemporary: (credential: TemporaryCredential) => {
      setCopied(false);
      setCopyError("");
      setTemporary(credential);
    },
  };
  return (
    <Context.Provider value={value}>
      {loading ? (
        <p role="status">{t("auth.loading")}</p>
      ) : failure ? (
        <main className="connection-error-page">
          <section
            className="connection-error-card"
            aria-labelledby="connection-error-title"
          >
            <div className="connection-error-brand">EPL Sync</div>
            <div className="connection-error-icon" aria-hidden="true">
              <WifiOff size={28} />
            </div>
            <h1 id="connection-error-title">{t("auth.connectionTitle")}</h1>
            <p role="alert">
              {t(
                failure === "connection"
                  ? "auth.connectionMessage"
                  : "auth.serverMessage",
              )}
            </p>
            <Button loading={refreshing} onClick={() => void refresh()}>
              {t("feedback.retry")}
            </Button>
          </section>
        </main>
      ) : !user ? (
        <Login
          onLogin={async () => {
            resetCsrf();
            await refresh();
          }}
        />
      ) : user.mustChangePassword ? (
        <main className="auth-panel">
          <h1>{t("auth.changeRequired")}</h1>
          <PasswordChange required />
        </main>
      ) : (
        <SessionWorkspace key={user.id}>{children}</SessionWorkspace>
      )}
      <AppModal
        icon={KeyRound}
        title={t("auth.temporaryPassword")}
        opened={!!temporary}
        onClose={() => setTemporary(undefined)}
        centered
        closeOnClickOutside={false}
      >
        {temporary && (
          <>
            <p>
              <strong>{temporary.user.username}</strong>
            </p>
            <PasswordInput
              label={t("auth.temporaryPassword")}
              readOnly
              value={temporary.password}
              defaultVisible
              autoComplete="off"
            />
            <p className="temporary-password-expiry">
              {t("auth.expires")}:{" "}
              {new Date(temporary.expiresAt).toLocaleString()}
            </p>
            {copyError && (
              <Alert color="red" role="alert">
                {copyError}
              </Alert>
            )}
          </>
        )}
        <ModalActions>
          <Button
            variant="default"
            leftSection={copied ? <Check size={16} /> : <Copy size={16} />}
            onClick={async () => {
              if (!temporary) return;
              try {
                await navigator.clipboard.writeText(temporary.password);
                setCopied(true);
                setCopyError("");
              } catch {
                setCopyError(t("auth.copyFailed"));
              }
            }}
          >
            {t(copied ? "auth.passwordCopied" : "auth.copyPassword")}
          </Button>
          <Button onClick={() => setTemporary(undefined)}>
            {t("auth.dismiss")}
          </Button>
        </ModalActions>
      </AppModal>
    </Context.Provider>
  );
}
function SessionWorkspace({ children }: { children: ReactNode }) {
  // Old in-flight mutations retain their own cache; results cannot cross accounts.
  const [cache] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { staleTime: 60_000, retry: 1 } },
      }),
  );
  useEffect(() => () => cache.clear(), [cache]);
  return <QueryClientProvider client={cache}>{children}</QueryClientProvider>;
}

function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<{
    initialized: boolean;
    initialAdminKeyRequired?: boolean;
    registrationEnabled: boolean;
    approvalRequired: boolean;
    passwordMinimumLength?: number;
  }>();
  const [initialAdminKey, setInitialAdminKey] = useState("");
  const [register, setRegister] = useState(false);
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirmation, setConfirmation] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [pending, setPending] = useState(false);
  const setup = status?.initialized === false;
  const minimum = status?.passwordMinimumLength ?? 8;
  const usernameInvalid =
    (setup || register) && username !== "" && !validUsername(username);
  const emailInvalid =
    (setup || register) && email !== "" && !validEmail(email);
  const passwordInvalid =
    (setup || register) && password !== "" && password.length < minimum;
  const mismatch =
    (setup || register) &&
    passwordConfirmation !== "" &&
    password !== passwordConfirmation;
  async function loadStatus() {
    try {
      setStatus(await authRequest<typeof status>("/auth/status"));
    } catch {
      setError(t("auth.unavailable"));
    }
  }
  useEffect(() => {
    void loadStatus();
  }, []);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || !status) return;
    if ((setup || register) && password !== passwordConfirmation) {
      setError(t("auth.passwordMismatch"));
      return;
    }
    setPending(true);
    setError("");
    setMessage("");
    try {
      if (setup) {
        await authRequest("/auth/setup", "POST", {
          username,
          email,
          password,
          passwordConfirmation,
          initialAdminKey: status.initialAdminKeyRequired
            ? initialAdminKey
            : undefined,
        });
        await onLogin();
      } else if (register) {
        const result = await authRequest<{ status: string }>(
          "/auth/register",
          "POST",
          { username, email, password },
        );
        setMessage(
          t(
            result.status === "PENDING"
              ? "auth.pendingApproval"
              : "auth.registered",
          ),
        );
        setRegister(false);
        setPassword("");
      } else {
        await authRequest("/auth/login", "POST", { username, password });
        await onLogin();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : t("auth.unavailable"));
      if (setup) await loadStatus();
    } finally {
      setPending(false);
    }
  }
  return (
    <main className="auth-panel">
      <h1 className="auth-setup-title">EPL Sync</h1>
      {error && (
        <Alert color="red" role="alert">
          {error}
        </Alert>
      )}
      {!status ? (
        <>
          <p role="status">{t("auth.loading")}</p>
          <Button onClick={() => void loadStatus()}>
            {t("feedback.retry")}
          </Button>
        </>
      ) : (
        <>
          {!setup && <h2>{t(register ? "auth.register" : "auth.login")}</h2>}
          {setup && <p>{t("auth.setupRequired")}</p>}
          {message && <Alert color="green">{message}</Alert>}
          <form onSubmit={submit}>
            <Stack>
              <ValidationTip
                message={usernameInvalid ? t("auth.usernameHelp") : undefined}
              >
                <TextInput
                  label={
                    setup || register ? (
                      <OptionLabel
                        text={t("auth.username")}
                        help={t("auth.usernameHelp")}
                      />
                    ) : (
                      t("auth.username")
                    )
                  }
                  error={usernameInvalid}
                  required
                  minLength={3}
                  maxLength={64}
                  value={username}
                  autoComplete="username"
                  disabled={pending}
                  onChange={(e) => setUsername(e.currentTarget.value)}
                />
              </ValidationTip>
              {(setup || register) && (
                <ValidationTip
                  message={emailInvalid ? t("auth.emailInvalid") : undefined}
                >
                  <TextInput
                    type="email"
                    label={t("auth.email")}
                    error={emailInvalid}
                    required
                    maxLength={254}
                    value={email}
                    autoComplete="email"
                    disabled={pending}
                    onChange={(e) => setEmail(e.currentTarget.value)}
                  />
                </ValidationTip>
              )}
              <ValidationTip
                message={
                  passwordInvalid
                    ? t("auth.passwordRules", { minimum })
                    : undefined
                }
              >
                <PasswordInput
                  label={
                    setup || register ? (
                      <OptionLabel
                        text={t("auth.password")}
                        help={t("auth.passwordRules", { minimum })}
                      />
                    ) : (
                      t("auth.password")
                    )
                  }
                  required
                  minLength={setup || register ? minimum : undefined}

                  maxLength={256}
                  value={password}
                  autoComplete={
                    setup || register ? "new-password" : "current-password"
                  }
                  disabled={pending}
                  onChange={(e) => setPassword(e.currentTarget.value)}
                  error={passwordInvalid}
                />
              </ValidationTip>
              {(setup || register) && (
                <ValidationTip
                  message={
                    mismatch
                      ? t("auth.passwordMismatch")
                      : passwordConfirmation
                        ? t("auth.passwordMatch")
                        : undefined
                  }
                  success={!mismatch}
                >
                  <PasswordInput
                    label={t("auth.repeatPassword")}
                    required
                    minLength={minimum}
                    maxLength={256}
                    error={mismatch}
                    value={passwordConfirmation}
                    autoComplete="new-password"
                    disabled={pending}
                    onChange={(e) => setConfirmation(e.currentTarget.value)}
                  />
                </ValidationTip>
              )}
              {setup && status.initialAdminKeyRequired && (
                <PasswordInput
                  label={
                    <OptionLabel
                      text={t("auth.initialAdminKey")}
                      help={t("auth.initialAdminKeyHelp")}
                    />
                  }
                  required
                  autoComplete="off"
                  value={initialAdminKey}
                  disabled={pending}
                  onChange={(e) => setInitialAdminKey(e.currentTarget.value)}
                />
              )}
              <Button
                className="auth-submit"
                type="submit"
                loading={pending}
                disabled={
                  usernameInvalid ||
                  emailInvalid ||
                  passwordInvalid ||
                  ((setup || register) && password !== passwordConfirmation)
                }
              >
                {t(
                  setup
                    ? "auth.setupAction"
                    : register
                      ? "auth.register"
                      : "auth.login",
                )}
              </Button>
            </Stack>
          </form>
          {!setup && status.registrationEnabled && (
            <Button
              fullWidth
              mt="md"
              variant="default"
              disabled={pending}
              onClick={() => {
                setRegister(!register);
                setConfirmation("");
                setError("");
              }}
            >
              {t(register ? "auth.backLogin" : "auth.register")}
            </Button>
          )}
        </>
      )}
    </main>
  );
}
export function PasswordChange({ required = false }: { required?: boolean }) {
  const { t } = useTranslation();
  const auth = useAuth();
  const [minimum, setMinimum] = useState(8);
  useEffect(() => {
    void authRequest<{ passwordMinimumLength: number }>("/auth/status")
      .then((status) => setMinimum(status.passwordMinimumLength ?? 8))
      .catch((e) => setError(String(e)));
  }, []);
  const [currentPassword, setCurrent] = useState("");
  const [newPassword, setNew] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const passwordInvalid = newPassword !== "" && newPassword.length < minimum;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    if (newPassword !== repeat) {
      setError(t("auth.passwordMismatch"));
      return;
    }
    setPending(true);
    setError("");
    try {
      await authRequest("/auth/password", "POST", {
        currentPassword,
        newPassword,
      });
      resetCsrf();
      await auth.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : t("auth.unavailable"));
    } finally {
      setPending(false);
    }
  }
  return (
    <>
      {error && <Alert color="red">{error}</Alert>}
      <form onSubmit={submit}>
        <div
          className={
            required
              ? "password-change-stack"
              : "server-settings-grid account-password-grid"
          }
        >
          <PasswordInput
            label={t("auth.currentPassword")}
            required
            value={currentPassword}
            maxLength={256}
            autoComplete="current-password"
            onChange={(e) => setCurrent(e.currentTarget.value)}
          />
          <ValidationTip
            message={
              passwordInvalid ? t("auth.passwordRules", { minimum }) : undefined
            }
          >
            <PasswordInput
              label={
                <OptionLabel
                  text={t("auth.newPassword")}
                  help={t("auth.passwordRules", { minimum })}
                />
              }
              required
              value={newPassword}
              minLength={minimum}
              maxLength={256}
              autoComplete="new-password"
              onChange={(e) => setNew(e.currentTarget.value)}
              error={passwordInvalid}
            />
          </ValidationTip>
          <ValidationTip
            message={
              repeat
                ? t(
                    newPassword !== repeat
                      ? "auth.passwordMismatch"
                      : "auth.passwordMatch",
                  )
                : undefined
            }
            success={newPassword === repeat}
          >
            <PasswordInput
              label={t("auth.repeatPassword")}
              required
              error={repeat !== "" && newPassword !== repeat}
              value={repeat}
              minLength={minimum}
              maxLength={256}
              autoComplete="new-password"
              onChange={(e) => setRepeat(e.currentTarget.value)}
            />
          </ValidationTip>
        </div>
        <div className={required ? "password-change-actions" : "action-row"}>
          <Button
            fullWidth={required}
            type="submit"
            loading={pending}
            disabled={passwordInvalid || newPassword !== repeat}
          >
            {t("auth.changePassword")}
          </Button>
        </div>
      </form>
    </>
  );
}
export function AccountSettings() {
  const { t } = useTranslation();
  const auth = useAuth();
  const [email, setEmail] = useState(auth.user?.email ?? "");
  const [message, setMessage] = useState("");
  return (
    <>
      <section className="panel settings-section security-settings">
        <h2>{t("auth.email")}</h2>
        <div className="server-settings-grid">
          <TextInput
            label={t("auth.email")}
            type="email"
            value={email}
            maxLength={254}
            onChange={(e) => setEmail(e.currentTarget.value)}
          />
        </div>
        <p>{t("auth.emailUnverified")}</p>
        <Button
          onClick={() =>
            void authRequest("/auth/email", "PUT", { email })
              .then(async () => {
                await auth.refresh();
                setMessage(t("auth.saved"));
              })
              .catch((e) => setMessage(String(e)))
          }
        >
          {t("auth.save")}
        </Button>
        {message && <p role="status">{message}</p>}
      </section>
      <section className="panel settings-section security-settings">
        <h2>{t("auth.changePassword")}</h2>
        <PasswordChange />
      </section>
    </>
  );
}
export function Require({
  permission,
  admin,
  children,
}: {
  permission?: Permission;
  admin?: boolean;
  children: ReactNode;
}) {
  const auth = useAuth();
  const { t } = useTranslation();
  return (
    admin ? auth.user?.role === "ADMIN" : permission && auth.can(permission)
  ) ? (
    <>{children}</>
  ) : (
    <Alert color="red">{t("auth.forbidden")}</Alert>
  );
}
