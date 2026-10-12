FROM nginx:1.31-alpine

COPY app/ /usr/share/nginx/html/

# Die Konfiguration liegt als Template unter /etc/nginx/templates: das Image
# kopiert sie beim Start nach /etc/nginx/conf.d (im Compose ein tmpfs, weil das
# Dateisystem sonst read-only ist). Der Filter schaltet das Ersetzen von
# Variablen praktisch ab - in der Datei stehen keine, und nginx-Variablen wie
# $uri sollen unberuehrt bleiben.
# Die Standardkonfiguration des Images fliegt raus: scheitert das Kopieren,
# soll nginx sichtbar ausfallen statt still die Willkommensseite auszuliefern.
ENV NGINX_ENVSUBST_FILTER=^ANALYTICS_
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template

# Baut die Schnipsel fuer die Reichweitenmessung aus den ANALYTICS_*-Variablen,
# bevor nginx startet. Ohne sie bleiben sie leer und es passiert nichts.
COPY nginx/analytics.sh /docker-entrypoint.d/30-analytics.sh
RUN chmod +x /docker-entrypoint.d/30-analytics.sh

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
