#!/usr/bin/env bash
# Sonda de HTML crudo: vuelca los fragmentos reales de los sitios para fijar selectores.
set -u
exec > >(tee /tmp/probe-full.log) 2>&1
UA='Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
mkdir -p out

echo '==================== 1) SOLOLATINO episodio: markup de botones ===================='
curl -sL -A "$UA" 'https://sololatino.net/serie/black-clover/temporada-1/episodio-1' -o out/sl-ep.html
echo "bytes: $(wc -c < out/sl-ep.html)"
grep -o 'data-player-[a-z-]*' out/sl-ep.html | sort | uniq -c | head
grep -o 'data-[a-z-]*token[a-z-]*' out/sl-ep.html | sort | uniq -c | head
echo '---- fragmento alrededor de "Principal" ----'
grep -o '.\{600\}Principal.\{300\}' out/sl-ep.html | head -c 2200
echo
echo '---- fragmento alrededor de "player-url" o "render_shield" ----'
grep -o '.\{300\}player-url.\{400\}' out/sl-ep.html | head -c 1600
grep -o '.\{100\}render_shield.\{200\}' out/sl-ep.html | head -c 800
echo
echo '---- clases de botones/contenedores de servidor ----'
grep -o '<button[^>]*>' out/sl-ep.html | head -12
grep -o 'class="[^"]*server[^"]*"' out/sl-ep.html | sort | uniq -c | head
grep -o 'data-lang[a-z-]*' out/sl-ep.html | sort | uniq -c | head

echo
echo '==================== 2) SOLOLATINO API completa desde cero ===================='
JAR=out/cookies.txt; rm -f $JAR
curl -sL -A "$UA" -c $JAR 'https://sololatino.net/sanctum/csrf-cookie' -o /dev/null -w 'csrf-cookie: %{http_code}\n'
XSRF=$(grep XSRF-TOKEN $JAR | awk '{print $NF}')
echo "xsrf(len ${#XSRF}): ${XSRF:0:40}..."
TOKEN=$(grep -o 'data-player-token="[^"]*"' out/sl-ep.html | head -1 | sed 's/data-player-token="//;s/"$//')
echo "token: ${TOKEN:0:60} (len ${#TOKEN})"
if [ -n "$TOKEN" ]; then
  REN=$(printf '%s' "$TOKEN:1:sololatino_render_shield_2026" | sha256sum | cut -c1-16)
  for R in 1 0; do
    for X in "$XSRF" "$(python3 -c "import urllib.parse,sys;print(urllib.parse.unquote(sys.argv[1]))" "$XSRF" 2>/dev/null)"; do
      echo "-- render=$R xsrf=$( [ "$X" = "$XSRF" ] && echo raw || echo decoded ) --"
      curl -s -A "$UA" -b $JAR -X POST 'https://sololatino.net/api/player-url' \
        -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $X" -H "X-SL-Render: $REN" \
        -H "X-Requested-With: XMLHttpRequest" -H "Referer: https://sololatino.net/serie/black-clover/temporada-1/episodio-1" \
        --data "{\"t\":\"$TOKEN\"}" -w '\nhttp: %{http_code}\n' | head -c 600
      echo
    done
  done
fi

echo
echo '==================== 3) PELICINEHD /peliculas: markup de tarjetas ===================='
curl -sL -A "$UA" 'https://pelicinehd.com/peliculas/' -o out/pc-list.html
echo "bytes: $(wc -c < out/pc-list.html)"
echo '---- tags article y sus clases ----'
grep -o '<article[^>]*>' out/pc-list.html | head -5
echo '---- contenedores con clase tipo item/movie/poster ----'
grep -o 'class="[^"]*\(movie\|item\|poster\|film\|card\)[^"]*"' out/pc-list.html | sort | uniq -c | sort -rn | head -12
echo '---- primer bloque de tarjeta (alrededor de "Ver pelicula") ----'
grep -o '.\{800\}Ver pelicula.\{100\}' out/pc-list.html | head -c 2200
echo
echo '---- año/idioma: badges ----'
grep -o '<div class="[^"]*"[^>]*>20[0-9][0-9]<' out/pc-list.html | head -4

echo
echo '==================== 4) REPELIS24.SHOP /peliculas: imágenes ===================='
curl -sL -A "$UA" 'https://repelis24.shop/peliculas' -o out/rs-list.html
echo "bytes: $(wc -c < out/rs-list.html)"
grep -o 'class="[^"]*\(movie-item\|item\|poster\|card\)[^"]*"' out/rs-list.html | sort | uniq -c | sort -rn | head -10
echo '---- etiquetas img de la primera tarjeta ----'
grep -o '<img[^>]*>' out/rs-list.html | head -6
echo '---- contenedor de tarjeta ----'
grep -o '.\{600\}movie-item.\{500\}' out/rs-list.html | head -c 1800

echo
echo '==================== FIN ===================='

echo
echo '==================== 5) EXTRA ===================='
echo '---- SoloLatino: scripts con datos embebidos ----'
grep -o 'window\.[A-Za-z_]*\s*=' out/sl-ep.html | sort | uniq -c | head
grep -c '<template' out/sl-ep.html
echo '---- SoloLatino: botones completos ----'
grep -o '<button[^>]*data-[a-z-]*="[^"]*"[^>]*>[^<]*' out/sl-ep.html | head -8
echo '---- Repelis24Shop: style backgrounds ----'
grep -o 'background-image[^;")]*' out/rs-list.html | head -4
grep -o 'class="[^"]*"' out/rs-list.html | sort | uniq -c | sort -rn | head -8
echo '---- PeliCineHD: enlaces de tarjeta ----'
grep -o 'href="https://pelicinehd.com/pelicula/[^"]*"' out/pc-list.html | head -4
grep -o 'href="https://pelicinehd.com/[^"]*"' out/pc-list.html | sort -u | head -8


echo '==================== 6) CDN de imágenes: ¿también tras Cloudflare? ===================='
curl -s -o /dev/null -w 'webp repelis24: http %{http_code} tipo %{content_type} bytes %{size_download}\n' \
  -A "$UA" 'https://repelis24.shop/cdn/p/v2/w1/ty7zfdlgY2HU3eopy3SLV8JJIODoGd5ZB3oH.webp'
curl -s -o /dev/null -w 'tmdb poster: http %{http_code} tipo %{content_type}\n' \
  -A "$UA" 'https://image.tmdb.org/t/p/w500/gr2v2nt0isUhTqnvNKeNQYpX0sO.jpg'

# Publicar el resumen: los logs de Actions son inalcanzables desde el sandbox; git y
# los issues sí. Push con GITHUB_TOKEN no dispara más workflows (anti-recursión).
{
  echo "## Sonda HTML $(date -u +%FT%TZ) commit ${GITHUB_SHA:-local}"
  echo
  echo '```text'
  cat /tmp/probe-full.log 2>/dev/null || true
  echo '```'
} > docs/sonda-html.md
git config user.name "sonda-html[bot]"
git config user.email "sonda-html@users.noreply.github.com"
git add docs/sonda-html.md
git commit -m "sonda-html: $(date -u +%FT%TZ)" || true
git push origin "HEAD:${GITHUB_REF_NAME}" || true
if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  gh issue create --repo "${GITHUB_REPOSITORY:-Menorah/CloudStream-Latino}" \
    --title "sonda-html $(date -u +%FT%TZ)" --body-file docs/sonda-html.md || true
fi

echo '==================== 7) Proxy de imágenes weserv ===================='
curl -s -o /dev/null -w 'weserv→webp repelis24: http %{http_code} tipo %{content_type} bytes %{size_download}\n' \
  -A "$UA" 'https://images.weserv.nl/?url=repelis24.shop%2Fcdn%2Fp%2Fv2%2Fw1%2Fty7zfdlgY2HU3eopy3SLV8JJIODoGd5ZB3oH.webp'
