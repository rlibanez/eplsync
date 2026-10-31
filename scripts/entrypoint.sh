#!/bin/sh
set -eu

umask 077
cd /app

if [ "$(id -u)" = 0 ]; then
    PUID=${PUID:-1000}
    PGID=${PGID:-1000}
    case "$PUID" in ''|*[!0-9]*) echo 'PUID debe ser numérico' >&2; exit 1;; esac
    case "$PGID" in ''|*[!0-9]*) echo 'PGID debe ser numérico' >&2; exit 1;; esac
    mkdir -p /app/data /app/logs
    chown "$PUID:$PGID" /app/data /app/logs
    chmod u+rwx /app/data /app/logs
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups \
        java --enable-native-access=ALL-UNNAMED -jar /app/app.jar "$@"
fi

exec java --enable-native-access=ALL-UNNAMED -jar /app/app.jar "$@"
