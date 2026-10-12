#!/bin/sh
# Baut die nginx-Schnipsel fuer die Reichweitenmessung aus der Umgebung.
#
# Laeuft als /docker-entrypoint.d/30-analytics.sh, also beim Start des
# Containers noch vor nginx. Warum ein Skript und kein envsubst-Template: die
# Messung soll sich komplett abschalten lassen, und "einmal mit Bloecken,
# einmal ohne" kann envsubst nicht - es ersetzt nur Variablen.
#
# Erzeugt werden zwei Dateien, die default.conf.template einbindet:
#
#   analytics/locations.conf  (Serverebene) - die beiden Proxy-Pfade
#   analytics/snippet.conf    (in location /) - das sub_filter, das den
#                             Platzhalter <!--analytics--> in den HTML-Seiten
#                             durch das Tracker-Snippet ersetzt
#
# Ohne Konfiguration bleiben beide leer: keine Proxys, kein sub_filter, kein
# Skript in den Seiten - und die HTML-Antworten behalten ihr ETag, das
# sub_filter sonst verwirft.
#
# Das Skript verlaesst sich darauf, dass /etc/nginx/conf.d beschreibbar ist
# (im Compose ein tmpfs, weil das Dateisystem sonst read-only ist).
set -eu

dir=/etc/nginx/conf.d/analytics
mkdir -p "$dir"
: >"$dir/locations.conf"
: >"$dir/snippet.conf"

script_url=${ANALYTICS_SCRIPT_URL:-}
event_url=${ANALYTICS_EVENT_URL:-}
website_id=${ANALYTICS_WEBSITE_ID:-}
id_attr=${ANALYTICS_ID_ATTRIBUTE:-data-website-id}
event_path=${ANALYTICS_EVENT_PATH:-/js/api/send}

if [ -z "$script_url" ] || [ -z "$event_url" ]; then
    echo "analytics: aus (ANALYTICS_SCRIPT_URL/ANALYTICS_EVENT_URL nicht gesetzt)"
    exit 0
fi

# Die Werte landen unquotiert in der nginx-Konfiguration und im HTML. Ein
# Anfuehrungszeichen darin wuerde beides zerreissen, deshalb hier abbrechen
# statt eine kaputte Konfiguration zu schreiben.
for value in "$script_url" "$event_url" "$website_id" "$id_attr" "$event_path"; do
    case $value in
        *\'* | *\"*)
            echo "analytics: Anfuehrungszeichen in ANALYTICS_* - Abbruch" >&2
            exit 1
            ;;
    esac
    if [ "$(printf '%s' "$value" | wc -l)" -ne 0 ]; then
        echo "analytics: Zeilenumbruch in ANALYTICS_* - Abbruch" >&2
        exit 1
    fi
done

host_of() { echo "$1" | sed -e 's#^[a-zA-Z]*://##' -e 's#/.*$##' -e 's#:.*$##'; }
script_host=$(host_of "$script_url")
event_host=$(host_of "$event_url")

# Warum ueberhaupt ueber die eigene Domain und nicht direkt zum Anbieter:
# Inhaltsblocker filtern Anfragen an bekannte Analytics-Hosts standardmaessig
# weg (ERR_BLOCKED_BY_CLIENT). Der lokale Pfad /js/pl.js ist bewusst neutral.
#
# Umami schickt seine Ereignisse an das Verzeichnis, aus dem der Tracker
# geladen wurde - bei /js/pl.js also an /js/api/send. Damit kommt er dem
# Backend unter /api/ nicht in die Quere. Plausible postet stattdessen an
# /api/event; dafuer gibt es ANALYTICS_EVENT_PATH.
cat >"$dir/locations.conf" <<EOF
location = /js/pl.js {
    proxy_pass $script_url;
    proxy_set_header Host $script_host;
    proxy_ssl_server_name on;
    proxy_ssl_name $script_host;
    # Eine Sitzung auf der eigenen Domain hat beim Anbieter nichts zu suchen.
    proxy_set_header Cookie "";
    proxy_set_header Authorization "";
    # Das Skript aendert sich selten, darf also zwischengespeichert werden.
    proxy_hide_header Cache-Control;
    add_header Cache-Control "public, max-age=3600" always;
}

location = $event_path {
    proxy_pass $event_url;
    proxy_set_header Host $event_host;
    proxy_ssl_server_name on;
    proxy_ssl_name $event_host;
    # Steht noch ein Reverse Proxy vor der Analytics-Instanz, der den
    # X-Forwarded-*-Headern nicht traut, sieht sie sonst nur die IP dieses
    # Containers - alle Besucher waeren eine einzige Sitzung. Die echte IP
    # geht deshalb in einem eigenen Header mit (Umami: CLIENT_IP_HEADER).
    proxy_set_header X-Visitor-IP \$http_x_real_ip;
    proxy_set_header Cookie "";
    proxy_set_header Authorization "";
}
EOF

cat >"$dir/snippet.conf" <<EOF
# sub_filter verwirft das ETag der HTML-Seiten; Last-Modified bleibt, damit
# die Revalidierung (304) weiter funktioniert.
sub_filter '<!--analytics-->' '<script defer $id_attr="$website_id" src="/js/pl.js"></script>';
sub_filter_once on;
sub_filter_last_modified on;
EOF

echo "analytics: an ($script_host, Ereignisse unter $event_path)"
