import { useEffect, useState, type FormEvent } from "react";
import {
  Badge,
  ThemeIcon,
  ActionIcon,
  Alert,
  Button,
  Checkbox,
  NumberInput,
  Select,
  Stack,
  TextInput,
} from "@mantine/core";
import { useTranslation } from "react-i18next";
import { ValidationTip } from "./ValidationTip";
import { validEmail, validUsername } from "./validation";
import { OptionLabel } from "../downloads/SendOptions";
import { authRequest } from "./transport";
import { useAuth, type Permission } from "./Auth";
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  BookOpen,
  Download,
  Shield,
  UserRound,
  Pencil,
  Plus,
  KeyRound,
  Trash2,
  UserCheck,
  RotateCcw,
} from "lucide-react";
import { AppModal, ModalActions } from "../../components/AppModal";
interface User {
  id: string;
  username: string;
  email: string;
  role: "ADMIN" | "USER";
  status: string;
  mustChangePassword: boolean;
  overrides: Record<string, string>;
  permissions: Permission[];
}
interface Policy {
  registrationEnabled: boolean;
  approvalRequired: boolean;
  idleMinutes: number;
  maximumHours: number;
  passwordMinimumLength: number;
}
interface Temporary {
  user: User;
  password: string;
  expiresAt: string;
}
export function UserSettings() {
  const { t, i18n } = useTranslation();
  const [sort, setSort] = useState<{
    key: "username" | "role" | "status";
    descending: boolean;
  }>({ key: "username", descending: false });
  const auth = useAuth();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<User>();
  const [resetting, setResetting] = useState<User>();
  const [deleting, setDeleting] = useState<User>();
  const [modalError, setModalError] = useState("");
  const [users, setUsers] = useState<User[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [policy, setPolicy] = useState<Policy>();
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("USER");
  const usernameInvalid = username !== "" && !validUsername(username);
  const emailInvalid = email !== "" && !validEmail(email);
  function sortValue(user: User) {
    return sort.key === "username"
      ? user.username
      : sort.key === "role"
        ? t(user.role === "ADMIN" ? "auth.roleAdmin" : "auth.roleUser")
        : t(`auth.statuses.${user.status}`);
  }
  const sortedUsers = [...users].sort((a, b) => {
    const comparison =
      sortValue(a).localeCompare(sortValue(b), i18n.resolvedLanguage, {
        numeric: true,
        sensitivity: "base",
      }) ||
      a.username.localeCompare(b.username, i18n.resolvedLanguage, {
        numeric: true,
        sensitivity: "base",
      });
    return sort.descending ? -comparison : comparison;
  });
  async function load() {
    const [nextUsers, nextPolicy, nextPermissions] = await Promise.all([
      authRequest<User[]>("/security/users"),
      authRequest<Policy>("/security/policy"),
      authRequest<Permission[]>("/security/permissions"),
    ]);
    setUsers(nextUsers);
    setPolicy(nextPolicy);
    setPermissions(nextPermissions);
  }
  useEffect(() => {
    void load().catch((e) => setError(String(e)));
  }, []);
  async function action(work: () => Promise<unknown>) {
    if (pending) return;
    setPending(true);
    setError("");
    setModalError("");
    try {
      const reload = await work();
      if (reload === false) return true;
      await load();
      return true;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (creating || editing || resetting || deleting) setModalError(message);
      else setError(message);
      return false;
    } finally {
      setPending(false);
    }
  }
  function create(event: FormEvent) {
    event.preventDefault();
    if (!validUsername(username) || !validEmail(email)) return;
    void action(async () => {
      auth.showTemporary(
        await authRequest<Temporary>("/security/users", "POST", {
          username,
          email,
          role,
        }),
      );
      setUsername("");
      setEmail("");
      setCreating(false);
    });
  }
  return (
    <>
      {error && (
        <Alert color="red" role="alert">
          {error}
        </Alert>
      )}
      <section className="panel settings-section security-settings">
        <h2>{t("auth.userList")}</h2>
        <div className="table-scroll">
          <table className="catalog-table users-table">
            <thead>
              <tr>
                {(["username", "role", "status"] as const).map((key) => {
                  const active = sort.key === key;
                  const Icon = active
                    ? sort.descending
                      ? ArrowDown
                      : ArrowUp
                    : ArrowUpDown;
                  return (
                    <th
                      key={key}
                      aria-sort={
                        active
                          ? sort.descending
                            ? "descending"
                            : "ascending"
                          : "none"
                      }
                    >
                      <button
                        className="catalog-sort-heading"
                        onClick={() =>
                          setSort((previous) => ({
                            key,
                            descending:
                              previous.key === key
                                ? !previous.descending
                                : false,
                          }))
                        }
                      >
                        {t(`auth.${key}`)}
                        <Icon size={15} aria-hidden="true" />
                      </button>
                    </th>
                  );
                })}
                <th>{t("auth.permissions")}</th>
                <th>{t("auth.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {sortedUsers.map((user) => (
                <tr key={user.id}>
                  <td>
                    {user.username}
                    <div className="muted">{user.email}</div>
                  </td>
                  <td>
                    {t(
                      user.role === "ADMIN"
                        ? "auth.roleAdmin"
                        : "auth.roleUser",
                    )}
                  </td>
                  <td>{t(`auth.statuses.${user.status}`)}</td>
                  <td>
                    {user.role === "ADMIN" ? (
                      t("auth.allPermissions")
                    ) : user.permissions.length ? (
                      <ul className="user-permissions">
                        {user.permissions.map((permission) => (
                          <li key={permission}>
                            {t(`auth.permissionLabels.${permission}`)}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      t("auth.noPermissions")
                    )}
                  </td>
                  <td>
                    <div className="user-actions">
                      {user.status === "PENDING" && (
                        <ActionIcon
                          variant="subtle"
                          color="green"
                          aria-label={t("auth.approveUserNamed", {
                            username: user.username,
                          })}
                          title={t("auth.approveUserNamed", {
                            username: user.username,
                          })}
                          disabled={pending}
                          onClick={() =>
                            void action(() =>
                              authRequest(
                                `/security/users/${user.id}/approve`,
                                "POST",
                              ),
                            )
                          }
                        >
                          <UserCheck size={18} />
                        </ActionIcon>
                      )}

                      <ActionIcon
                        variant="subtle"
                        aria-label={t("auth.editUser", {
                          username: user.username,
                        })}
                        disabled={pending}
                        onClick={() => {
                          setModalError("");
                          setEditing(user);
                        }}
                      >
                        <Pencil size={18} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        aria-label={t("auth.resetPasswordNamed", {
                          username: user.username,
                        })}
                        title={t("auth.resetPasswordNamed", {
                          username: user.username,
                        })}
                        disabled={pending || user.status !== "ACTIVE"}
                        onClick={() => {
                          setModalError("");
                          setResetting(user);
                        }}
                      >
                        <KeyRound size={18} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        aria-label={t("auth.deleteUserNamed", {
                          username: user.username,
                        })}
                        disabled={
                          pending ||
                          (user.role === "ADMIN" &&
                            user.status === "ACTIVE" &&
                            users.filter(
                              (u) =>
                                u.role === "ADMIN" && u.status === "ACTIVE",
                            ).length === 1)
                        }
                        onClick={() => {
                          setModalError("");
                          setDeleting(user);
                        }}
                      >
                        <Trash2 size={18} />
                      </ActionIcon>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {users.length === 0 && <p>{t("auth.noUsers")}</p>}
        <div className="action-row">
          <Button
            leftSection={<Plus size={17} />}
            disabled={pending}
            onClick={() => {
              setUsername("");
              setEmail("");
              setRole("USER");
              setModalError("");
              setCreating(true);
            }}
          >
            {t("auth.create")}
          </Button>
        </div>
      </section>
      {policy && (
        <section className="panel settings-section security-settings">
          <h2>{t("auth.securityPolicy")}</h2>
          <Stack>
            <fieldset className="send-options-group">
              <legend>{t("auth.registration")}</legend>
              <Stack>
                <Checkbox
                  label={t("auth.registrationEnabled")}
                  checked={policy.registrationEnabled}
                  onChange={(e) =>
                    setPolicy({
                      ...policy,
                      registrationEnabled: e.currentTarget.checked,
                    })
                  }
                />
                <Checkbox
                  label={
                    <OptionLabel
                      text={t("auth.approvalRequired")}
                      help={t("auth.policyHelp")}
                    />
                  }
                  checked={policy.approvalRequired}
                  onChange={(e) =>
                    setPolicy({
                      ...policy,
                      approvalRequired: e.currentTarget.checked,
                    })
                  }
                />
              </Stack>
            </fieldset>
            <fieldset className="send-options-group">
              <legend>{t("auth.sessions")}</legend>
              <div className="server-settings-grid">
                <NumberInput
                  label={t("auth.idleMinutes")}
                  min={5}
                  max={1440}
                  value={policy.idleMinutes}
                  onChange={(value) =>
                    setPolicy({ ...policy, idleMinutes: Number(value) })
                  }
                />
                <NumberInput
                  label={t("auth.maximumHours")}
                  min={1}
                  max={168}
                  value={policy.maximumHours}
                  onChange={(value) =>
                    setPolicy({ ...policy, maximumHours: Number(value) })
                  }
                />
              </div>
            </fieldset>
            <fieldset className="send-options-group">
              <legend>{t("auth.passwords")}</legend>
              <div className="server-settings-grid">
                <NumberInput
                  label={
                    <OptionLabel
                      text={t("auth.passwordMinimumLength")}
                      help={t("auth.passwordPolicyHelp")}
                    />
                  }
                  min={8}
                  max={128}
                  value={policy.passwordMinimumLength ?? 8}
                  onChange={(value) =>
                    setPolicy({
                      ...policy,
                      passwordMinimumLength: Number(value),
                    })
                  }
                />
              </div>
            </fieldset>
            <Button
              disabled={pending}
              onClick={() =>
                void action(() =>
                  authRequest("/security/policy", "PUT", policy),
                )
              }
            >
              {t("auth.save")}
            </Button>
          </Stack>
        </section>
      )}
      <AppModal
        icon={Plus}
        title={t("auth.createUser")}
        opened={creating}
        onClose={() => {
          if (!pending) setCreating(false);
        }}
        size={392}
        centered
      >
        {modalError && <Alert color="red">{modalError}</Alert>}
        <div className="security-settings">
          <form onSubmit={create}>
            <div className="server-settings-grid">
              <ValidationTip
                message={usernameInvalid ? t("auth.usernameHelp") : undefined}
              >
                <TextInput
                  label={
                    <OptionLabel
                      text={t("auth.username")}
                      help={t("auth.usernameHelp")}
                    />
                  }
                  error={usernameInvalid}
                  required
                  minLength={3}
                  maxLength={64}
                  value={username}
                  onChange={(e) => setUsername(e.currentTarget.value)}
                />
              </ValidationTip>
              <ValidationTip
                message={emailInvalid ? t("auth.emailInvalid") : undefined}
              >
                <TextInput
                  label={t("auth.email")}
                  error={emailInvalid}
                  type="email"
                  required
                  maxLength={254}
                  value={email}
                  onChange={(e) => setEmail(e.currentTarget.value)}
                />
              </ValidationTip>
              <Select
                label={t("auth.role")}
                allowDeselect={false}
                data={[
                  { value: "USER", label: t("auth.roleUser") },
                  { value: "ADMIN", label: t("auth.roleAdmin") },
                ]}
                value={role}
                onChange={(value) => setRole(value ?? "USER")}
              />
            </div>
            <ModalActions>
              <Button
                variant="default"
                disabled={pending}
                onClick={() => setCreating(false)}
              >
                {t("import.cancel")}
              </Button>
              <Button
                type="submit"
                disabled={
                  pending || !validUsername(username) || !validEmail(email)
                }
              >
                {t("auth.createUser")}
              </Button>
            </ModalActions>
          </form>
        </div>
      </AppModal>
      <AppModal
        icon={Pencil}
        title={t("auth.editUser", { username: editing?.username })}
        opened={!!editing}
        onClose={() => {
          if (!pending) setEditing(undefined);
        }}
        size="xl"
        centered
      >
        {modalError && <Alert color="red">{modalError}</Alert>}
        {editing && (
          <UserEditor
            key={editing.id}
            user={editing}
            permissions={permissions}
            pending={pending}
            cancel={() => setEditing(undefined)}
            save={async (next) => {
              if (
                await action(() =>
                  authRequest(`/security/users/${editing.id}`, "PUT", next),
                )
              ) {
                setEditing(undefined);
                await auth.refresh();
              }
            }}
          />
        )}
      </AppModal>
      <AppModal
        icon={KeyRound}
        title={t("auth.resetPassword")}
        opened={!!resetting}
        onClose={() => {
          if (!pending) setResetting(undefined);
        }}
        centered
      >
        <p>
          {t("auth.resetPasswordWarning", { username: resetting?.username })}
        </p>
        {modalError && <Alert color="red">{modalError}</Alert>}
        <ModalActions>
          <Button
            variant="default"
            disabled={pending}
            onClick={() => setResetting(undefined)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            loading={pending}
            onClick={() =>
              void action(async () => {
                const result = await authRequest<Temporary>(
                  `/security/users/${resetting!.id}/password`,
                  "POST",
                );
                auth.showTemporary(result);
                const self = resetting!.id === auth.user?.id;
                setResetting(undefined);
                if (self) {
                  await auth.logout();
                  return false;
                }
              })
            }
          >
            {t("auth.resetPassword")}
          </Button>
        </ModalActions>
      </AppModal>
      <AppModal
        icon={Trash2}
        title={t("auth.deleteUser")}
        opened={!!deleting}
        onClose={() => {
          if (!pending) setDeleting(undefined);
        }}
        centered
      >
        <p>{t("auth.deleteUserWarning", { username: deleting?.username })}</p>
        {modalError && <Alert color="red">{modalError}</Alert>}
        <ModalActions>
          <Button
            variant="default"
            disabled={pending}
            onClick={() => setDeleting(undefined)}
          >
            {t("import.cancel")}
          </Button>
          <Button
            color="red"
            loading={pending}
            onClick={() =>
              void action(async () => {
                await authRequest(`/security/users/${deleting!.id}`, "DELETE", {
                  confirm: true,
                });
                setDeleting(undefined);
                await auth.refresh();
              })
            }
          >
            {t("auth.deleteUser")}
          </Button>
        </ModalActions>
      </AppModal>
    </>
  );
}
function UserEditor({
  user,
  permissions,
  pending,
  save,
  cancel,
}: {
  user: User;
  permissions: Permission[];
  pending: boolean;
  save: (value: {
    role: string;
    status: string;
    overrides: Record<string, string>;
  }) => Promise<void>;
  cancel: () => void;
}) {
  const { t } = useTranslation();
  const [role, setRole] = useState(user.role);
  const [status, setStatus] = useState(user.status);
  const [overrides, setOverrides] = useState(user.overrides);
  const groups = [
    {
      name: "catalog",
      icon: BookOpen,
      values: permissions.filter((p) =>
        [
          "CATALOG_READ",
          "BOOK_HISTORY_READ",
          "CATALOG_IMPORT",
          "CATALOG_DELETE",
          "COVERS_MANAGE",
        ].includes(p),
      ),
    },
    {
      name: "torrent",
      icon: Download,
      values: permissions.filter(
        (p) => p === "DOWNLOADS_READ" || p.startsWith("TORRENT_"),
      ),
    },
    {
      name: "system",
      icon: Shield,
      values: permissions.filter((p) =>
        ["EVENTS_MANAGE", "SETTINGS_MANAGE"].includes(p),
      ),
    },
  ];
  return (
    <div className="security-settings user-editor">
      <div className="user-editor-content">
        <div className="user-editor-identity">
          <ThemeIcon size={44} radius="xl" variant="light">
            <UserRound size={23} />
          </ThemeIcon>
          <div>
            <strong>{user.username}</strong>
            <div className="muted">{user.email}</div>
          </div>
          <Badge variant="light">
            {t(role === "ADMIN" ? "auth.roleAdmin" : "auth.roleUser")}
          </Badge>
        </div>
        <fieldset className="send-options-group" disabled={pending}>
          <legend>{t("auth.accountDetails")}</legend>
          <div className="server-settings-grid">
            <Select
              label={t("auth.role")}
              value={role}
              allowDeselect={false}
              data={[
                { value: "USER", label: t("auth.roleUser") },
                { value: "ADMIN", label: t("auth.roleAdmin") },
              ]}
              onChange={(value) => {
                setRole(value as User["role"]);
                if (value === "ADMIN") setOverrides({});
              }}
            />
            <Select
              label={t("auth.status")}
              value={status}
              allowDeselect={false}
              data={["PENDING", "ACTIVE", "DISABLED", "REJECTED"].map(
                (value) => ({ value, label: t(`auth.statuses.${value}`) }),
              )}
              onChange={(value) => setStatus(value ?? status)}
            />
          </div>
        </fieldset>
        {role === "USER" ? (
          <section
            className="user-editor-permissions"
            aria-label={t("auth.permissions")}
          >
            <h3>{t("auth.permissions")}</h3>
            <p className="muted">{t("auth.permissionHelp")}</p>
            <Button
              variant="default"
              size="sm"
              leftSection={<RotateCcw size={16} />}
              disabled={pending}
              onClick={() => setOverrides({})}
            >
              {t("auth.restoreDefaultPermissions")}
            </Button>
            {groups
              .filter((group) => group.values.length)
              .map((group) => (
                <fieldset
                  className="send-options-group"
                  key={group.name}
                  disabled={pending}
                >
                  <legend>
                    <span className="user-editor-legend">
                      <group.icon size={16} aria-hidden="true" />
                      {t(`auth.permissionGroups.${group.name}`)}
                    </span>
                  </legend>
                  <div className="server-settings-grid">
                    {group.values.map((permission) => (
                      <Select
                        key={permission}
                        label={t(`auth.permissionLabels.${permission}`)}
                        value={overrides[permission] ?? "INHERIT"}
                        allowDeselect={false}
                        data={[
                          {
                            value: "INHERIT",
                            label: t(
                              permission === "CATALOG_READ"
                                ? "auth.inheritAllow"
                                : "auth.inheritDeny",
                            ),
                          },
                          { value: "ALLOW", label: t("auth.allow") },
                          { value: "DENY", label: t("auth.deny") },
                        ]}
                        onChange={(value) =>
                          setOverrides((previous) => {
                            const next = { ...previous };
                            if (value === "INHERIT") delete next[permission];
                            else next[permission] = value ?? "DENY";
                            return next;
                          })
                        }
                      />
                    ))}
                  </div>
                </fieldset>
              ))}
          </section>
        ) : (
          <Alert icon={<Shield size={18} />}>
            {t("auth.adminPermissionHelp")}
          </Alert>
        )}
      </div>
      <div className="user-editor-footer">
        <ModalActions>
          <Button variant="default" disabled={pending} onClick={cancel}>
            {t("import.cancel")}
          </Button>
          <Button
            loading={pending}
            onClick={() => void save({ role, status, overrides })}
          >
            {t("auth.save")}
          </Button>
        </ModalActions>
      </div>
    </div>
  );
}
