FROM nginx:1.27-alpine

COPY app/ /usr/share/nginx/html/
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/40-ensure-upload-dir.sh /docker-entrypoint.d/40-ensure-upload-dir.sh
RUN chmod +x /docker-entrypoint.d/40-ensure-upload-dir.sh

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O /dev/null http://localhost/ || exit 1
