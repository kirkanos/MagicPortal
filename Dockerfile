FROM nginx:1.31-alpine

COPY app/ /usr/share/nginx/html/

# The configuration lives as a template under /etc/nginx/templates: at startup
# the image copies it to /etc/nginx/conf.d (a tmpfs in compose, because the
# filesystem is read-only otherwise). The filter effectively disables variable
# substitution - there are none in the file, and nginx variables such as $uri
# must stay untouched.
# The image's default configuration is removed: if copying fails, nginx should
# fail visibly instead of quietly serving the welcome page.
ENV NGINX_ENVSUBST_FILTER=^ANALYTICS_
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template

# Builds the analytics snippets from the ANALYTICS_* variables before nginx
# starts. Without them they stay empty and nothing happens.
COPY nginx/analytics.sh /docker-entrypoint.d/30-analytics.sh
RUN chmod +x /docker-entrypoint.d/30-analytics.sh

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
