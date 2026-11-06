#!/bin/sh
set -eu

umask 077
cd /app
data_root=${EPLSYNC_DATA_DIR:-/data}

if [ "$(id -u)" = 0 ]; then
    PUID=${PUID:-1000}
    PGID=${PGID:-1000}
    case "$PUID" in ''|*[!0-9]*) echo 'PUID debe ser numérico' >&2; exit 1;; esac
    case "$PGID" in ''|*[!0-9]*) echo 'PGID debe ser numérico' >&2; exit 1;; esac
    mkdir -p "$data_root" "$data_root/db" "$data_root/logs"
    chown "$PUID:$PGID" "$data_root" "$data_root/db" "$data_root/logs"
    chmod u+rwx "$data_root" "$data_root/db" "$data_root/logs"
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups \
        java --enable-native-access=ALL-UNNAMED -jar /app/app.jar "$@"
fi

exec java --enable-native-access=ALL-UNNAMED -jar /app/app.jar "$@"
