#!/bin/sh
# Runs as root at container start (official nginx entrypoint hook), before the
# worker processes drop to the 'nginx' user. Ensures the bind-mounted upload
# folder exists and is writable for WebDAV PUT uploads.
set -e

UPLOAD_DIR="/usr/share/nginx/html/upload"
mkdir -p "$UPLOAD_DIR"
chmod 0777 "$UPLOAD_DIR"
