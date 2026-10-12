#!/bin/sh
# Builds the nginx snippets for the analytics setup from the environment.
#
# Runs as /docker-entrypoint.d/30-analytics.sh, i.e. at container start just
# before nginx. Why a script and not an envsubst template: analytics has to be
# switchable off completely, and "once with these blocks, once without" is
# beyond envsubst - it only substitutes variables.
#
# Two files are produced, both included by default.conf.template:
#
#   analytics/locations.conf  (server level) - the two proxy paths
#   analytics/snippet.conf    (inside location /) - the sub_filter replacing
#                             the <!--analytics--> placeholder in the HTML
#                             pages with the tracker snippet
#
# Without configuration both stay empty: no proxies, no sub_filter, no script
# in the pages - and the HTML responses keep the ETag that sub_filter would
# otherwise discard.
#
# The script relies on /etc/nginx/conf.d being writable (a tmpfs in compose,
# because the filesystem is read-only otherwise).
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
    echo "analytics: off (ANALYTICS_SCRIPT_URL/ANALYTICS_EVENT_URL not set)"
    exit 0
fi

# The values end up unquoted in the nginx configuration and in the HTML. A
# quote character would tear apart both, so abort here instead of writing a
# broken configuration.
for value in "$script_url" "$event_url" "$website_id" "$id_attr" "$event_path"; do
    case $value in
        *\'* | *\"*)
            echo "analytics: quote character in ANALYTICS_* - aborting" >&2
            exit 1
            ;;
    esac
    if [ "$(printf '%s' "$value" | wc -l)" -ne 0 ]; then
        echo "analytics: line break in ANALYTICS_* - aborting" >&2
        exit 1
    fi
done

host_of() { echo "$1" | sed -e 's#^[a-zA-Z]*://##' -e 's#/.*$##' -e 's#:.*$##'; }
script_host=$(host_of "$script_url")
event_host=$(host_of "$event_url")

# Why go through our own domain at all instead of straight to the provider:
# content blockers filter requests to known analytics hosts by default
# (ERR_BLOCKED_BY_CLIENT). The local path /js/pl.js is deliberately neutral.
#
# Umami sends its events to the directory the tracker was loaded from - so
# /js/pl.js means /js/api/send, which keeps it out of the backend's way under
# /api/. Plausible posts to /api/event instead; that is what
# ANALYTICS_EVENT_PATH is for.
cat >"$dir/locations.conf" <<EOF
location = /js/pl.js {
    proxy_pass $script_url;
    proxy_set_header Host $script_host;
    proxy_ssl_server_name on;
    proxy_ssl_name $script_host;
    # A session cookie for our own domain has no business at the provider.
    proxy_set_header Cookie "";
    proxy_set_header Authorization "";
    # The script rarely changes, so it may be cached.
    proxy_hide_header Cache-Control;
    add_header Cache-Control "public, max-age=3600" always;
}

location = $event_path {
    proxy_pass $event_url;
    proxy_set_header Host $event_host;
    proxy_ssl_server_name on;
    proxy_ssl_name $event_host;
    # If another reverse proxy sits in front of the analytics instance and
    # does not trust X-Forwarded-* headers, it only ever sees this container's
    # IP - every visitor would be a single session. The real IP therefore
    # travels in a header of its own (Umami: CLIENT_IP_HEADER).
    proxy_set_header X-Visitor-IP \$http_x_real_ip;
    proxy_set_header Cookie "";
    proxy_set_header Authorization "";
}
EOF

cat >"$dir/snippet.conf" <<EOF
# sub_filter discards the ETag of the HTML pages; Last-Modified stays, so
# revalidation (304) keeps working.
sub_filter '<!--analytics-->' '<script defer $id_attr="$website_id" src="/js/pl.js"></script>';
sub_filter_once on;
sub_filter_last_modified on;
EOF

echo "analytics: on ($script_host, events at $event_path)"
