FROM nginx:1.31-alpine

COPY app/ /usr/share/nginx/html/
# Die Konfiguration ist ein Template: das Image setzt beim Start die Werte aus
# der Umgebung ein und schreibt das Ergebnis nach /etc/nginx/conf.d (im
# Compose ein tmpfs, weil das Dateisystem sonst read-only ist). Der Filter
# beschraenkt das auf UMAMI_*, nginx-Variablen wie $uri bleiben unberuehrt.
# Die Standardkonfiguration des Images fliegt raus: scheitert das Einsetzen,
# soll nginx sichtbar ausfallen statt still die Willkommensseite auszuliefern.
ENV NGINX_ENVSUBST_FILTER=^UMAMI_
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
