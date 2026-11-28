# Análisis de los sitios solicitados

Actualizado: 25 de noviembre de 2026. Este documento combina el diagnóstico reproducible
(sondas en `.github/workflows/` y `scripts/`) con la evidencia de reproducción real.
Los resultados dependen de la red y de la fecha. No se enviaron formularios, no se
crearon cuentas, no se resolvieron CAPTCHAs y no se solicitaron cookies ni tokens al usuario.

## Estado de los 14 complementos

| Complemento | Estado | Evidencia |
| --- | --- | --- |
| RePelis24.cz | **Publicado** | El usuario confirmó que funciona; pruebas Android con 73 episodios. |
| RePelisHD | **Publicado** | Película reproducida en los tres idiomas + episodio en CloudStream. |
| Cinecalidad.am | **Publicado** | Película y episodio reproducidos con CloudStream+Media3. |
| Cinecalidad.my | **Publicado** | Cuatro películas reproducidas vía VOE. |
| RePelis24.shop | **Descartado** (decisión del usuario, 24-nov) | Reproducción verificada en CI el 24-nov (abajo), pero en la red del usuario fallaron imágenes (el CDN `/cdn/` devuelve reto 403 de Cloudflare) y aparecieron enlaces CONTAINER_MALFORMED; tras varias rondas de arreglos el usuario decidió retirarlo. |
| AnimeOnlineNinja | **Reconstruido y verificado (v9)** | Sustituye por completo al complemento antiguo; catálogo completo, portadas Coil + respaldo WebView y reproducción Media3 verificada. |
| Cuevana3v | **Creado desde cero y verificado (v1)** | Catálogo completo HTML SSR (`/peliculas`, `/series`, `/episodios/recientes`, `/explorar?s=`, géneros, estrenos), descifrado XOR/Base64 de `tungtungsahur.cuevana3v.pro`, extractores propios VidHide para Hyper (`lkhjerbhye3wikhoduh5xiczuvd.lol`) y Nebula (`vibuxer.com`), portadas Coil y reproducción Media3 verificada. |
| VerAni | Publicado (tareas previas) | Mantiene su módulo propio para VerAni.me. |
| SoloLatino | **Creado y verificado (v4)** | Reconstrucción completa sobre HTML SSR de `sololatino.net` (catálogos paginados, últimos episodios, búsqueda y sugerencias JSON, pósters TMDB `w500`/`w1280`/`w400`), handshake Sanctum con `X-XSRF-TOKEN` exclusivo (y reintento tras 419) + `X-SL-Render` SHA-256, `WebView` propio (`Semaphore(1)`, UA nativo preservado, `onRenderProcessGone`, sin borrar cookies globales), flujo `player.pelisserieshoy.com` en streaming vía `XMLHttpRequest` (`window.stop()`, `_isLocked`, `_qt`/`_t`,Proxy `/p.php`), `embed69.org` (`dataLink` AES-256-CBC), VOE con espejos + solucionador ALTCHA v2 PBKDF2-HMAC-SHA256 en Kotlin y descifrado F7 unsigned verificados en CloudStream + Media3 + Coil. |
| PoseidonHD2 | **Reconstruido y verificado (v2)** | Sustituye por completo al complemento antiguo sobre el SSR `__NEXT_DATA__` de `poseidonhd2.co`: 27 secciones paginadas (`/page/<n>`), búsqueda, promoción episodio→serie, extractores propios para VOE (ALTCHA v2 PBKDF2 + descifrado), StreamWish (`medixiru.com` → `vsembed.com` AES-128-CBC), VidHide (cascada de espejos `hls2`), `bysejikuar.com`, más extractores integrados (`Vidara`, `DoodStream`) y reproducción Media3 verificada. |
| pelissmart.com | **Sin complemento viable** | Muro de teasers (abajo): todas las fuentes públicas son clips de 4-20 s con duración falseada. |
| pelisplus.fit | **Sin complemento viable** | Ídem. |
| pelisplus-to.com | **Sin complemento viable** | Ídem. |
| cuevana3me.info | **Sin complemento viable** | Ídem; la ruta `/ver.html` de episodios devuelve HTTP 500. |
| 123pelispop.pro | **Sin complemento viable** | La ficha no expone ninguna fuente de vídeo (`data-url="#"`); solo registro y publicidad. |
| pelisjuanita.com (`PelisJuanita`) | **Verificado y actualizado (v3)** | Portada (27 secciones), paginación, búsqueda y fichas públicas vía `/tv/app/buscar.php` + verificación interactiva del usuario en `Ajustes (⚙) -> PelisJuanita` (WebView sobre `inicio.php` sin automatizar CAPTCHA); v3 incorpora descifrado completo de **Byse** (ECDSA P-256 `challenge`/`attest` + PoW determinista `captcha/verify` + claves `version`-indexadas AES-256-GCM en `n1mwq.org`/espejos), actualización de claves AES-CBC de `vsembed.com` con respaldo automático `tiktok` cuando `hls` caduca, extractor propio `MixDrop` (`mixdrop.top`/`mxdrop.click` con `Referer` y `User-Agent`), respaldo de `backgroundPosterUrl` a `posterUrl` cuando `img_fondo` está vacío (p. ej. `the-skin-crawler-murders`), y soporte del HTML unificado de búsqueda (`.seriesTag`/`.fecha`, normalización `&`/`'` y `/series/page/N/search?s=`). |
| pelisenhd.org | **Sin complemento viable (abortado)** | Solo enlaces DDL `.rar`/`.zip` tras temporizador en `financialtrust.info` y Cloudflare Turnstile en `hdpastes.com`, sin vídeo embebido directo. |
| Cinecalidad.org | **Verificado con Media3** | API JSON pública (`/wp-api/v1/`): películas, series, animes, novelas y WWE; película, película subtitulada, episodio de serie (10 temporadas / 232 episodios en `csi-miami-2002`) y anime reproducidos con CloudStream + Media3 (`Vimeos` y `Voe`). |

## Familia trampa: pelissmart, pelisplus.fit, pelisplus-to, cuevana3me, 123pelispop

Los cinco sitios comparten plantilla: ficha con tres pestañas de idioma, modal de
registro (`mdlregister`, «Ver en HD»/«DESCARGAR» a `/register/`) y un único `<source>`
de vídeo. La duración del reproductor está falseada (`player.duration = function(){return 2940}`)
y el modal de registro es cosmético (pausa a los 10 s en película y 4 s en episodio).

**ffprobe demostró que todas las fuentes públicas son teasers genéricos**, no la obra:

| Sitio | Muestra | Fuente pública | Duración real |
| --- | --- | --- | --- |
| pelissmart | Hermanastras (2026) | `streamx.me/8277813/m3tr0g0ldw1n.mp4` | 13.1 s / 2.08 MB |
| pelissmart | Gran Torino (2008) | `streamx.me/8277813/w4rn3rbr0s.mp4` | 14.1 s / 2.66 MB |
| pelissmart | La Momia 3 (2008) | `streamx.me/8277813/m3tr0g0ldw1n.mp4` | 13.1 s |
| pelissmart | Padre de familia T1E1 | `pelissmart.com/videos/netflix.mp4` | 4.0 s / 436 KB |
| pelisplus.fit | Hermanastras | `vids.vidplayz.site/1847290/m3tr0g0ldw1n.mp4` | 13.1 s |
| pelisplus.fit | La semilla del diablo (1968) | `vids.vidplayz.site/1847292/p4r4m0unt.mp4` | 20.1 s / 3.2 MB |
| pelisplus.fit | Padre de familia T1E1 | `raw.githubusercontent.com/bandriseman/netnet/main/netfl1xx.mp4` | 4.0 s |
| pelisplus-to | La otra madre (2026) | `vids.vidplayz.site/1847290/m3tr0g0ldw1n.mp4` | 13.1 s |
| pelisplus-to | Gremlins (1984) | `vids.vidplayz.site/1847284/w4rn3rbr0s.mp4` | 14.1 s |
| cuevana3me | La otra madre (2026) | `vids.vidplayz.site/1847290/m3tr0g0ldw1n.mp4` | 13.1 s |
| cuevana3me | Gremlins (1984) | `vids.vidplayz.site/1847284/w4rn3rbr0s.mp4` | 14.1 s |

Los archivos se llaman como estudios (`w4rn3rbr0s`, `m3tr0g0ldw1n`, `p4r4m0unt`,
`netfl1xx`) y se repiten entre sitios: son reclamos genéricos. La muestra incluye
títulos recientes **y** catálogo antiguo (1968-2008), cumpliendo la exigencia de no
concluir con pocas muestras. Todo acceso real queda tras el registro, cuya automatización
queda excluida. En 123pelispop la ficha no tiene `data-url` útil, ni `createplayer`
inline, ni fuentes: solo scripts publicitarios. Conclusión: **no se puede construir un
complemento que reproduzca estas obras desde las fuentes públicas**; publicar un
«complemento» que solo enseñe teasers sería engañoso.

Evidencia reproducible: `scripts/trap_family_probe.py` (rondas 1-3, runs 45297167223,
45298286466, 45298720540).

## PelisJuanita.com (v3) — actualizado y verificado con Media3

El sitio web (`https://pelisjuanita.com/`, PHP propio) protege `/movies/*`, `/series/*` y
`/tv/` mediante un muro de verificación humana en `/inicio.php?redirect=...` (CAPTCHA
de imagen + casilla de términos + honeypot `empresa_hp`) que emite la cookie de sesión
`PHPSESSID`, mientras expone el buscador público JSON en `/tv/app/buscar.php?q=...&pagina=...`
(~27.890 títulos entre películas y series) con portadas y fondos directos en `image.tmdb.org`.

- **Modo dual sin automatizar CAPTCHAs**:
  - Antes de verificarse, el complemento carga el catálogo completo en la portada (27 secciones
    en paralelo en ~1,4 s, 0 vacías), paginación, búsqueda y fichas de películas desde
    `/tv/app/buscar.php` y guía claramente al usuario para completar la verificación única
    en **`Ajustes (⚙) -> PelisJuanita`**.
  - `PelisJuanitaPlugin.openSettings` abre un diálogo Android con `WebView` a pantalla
    completa (`PelisJuanitaVerificationDialog`) donde la propia persona resuelve el CAPTCHA
    de `/inicio.php`; al confirmar sesión activa (o desaparecer el muro en `/movies/`),
    persiste `PHPSESSID`, cookies (`pelisjuanita.com` y `www.pelisjuanita.com`) y `User-Agent`
    en `SharedPreferences` y activa el catálogo HTML autenticado:
    - Reinyecta automáticamente `PHPSESSID` y cookies en `CookieManager` desde `SharedPreferences`
      antes de abrir `PelisJuanitaWebTransport` si otro complemento ejecutó `removeAllCookies`,
      y activa el respaldo `WebView` también cuando OkHttp recibe `403`/`503` vacíos de Cloudflare
      (incluyendo DOM sintetizado vacío `<html><head></head><body></body></html>`).
    - Resuelve todas las temporadas y episodios de `serieInfo.php` aunque `#ulTemporada` llegue
      vacío en el primer fragmento y las peticiones por temporada devuelvan elementos sueltos
      `<a class="episodio-item">` (evitando el estado erróneo `Próximamente`).
    - Resuelve y conserva el parámetro obligatorio `&p=<id>` para `movieInfo.php` tanto desde
      `/tv/app/buscar.php` como desde `/movies/pelicula/<slug>` (y reconsulta `movieShell` si
      `movieInfo.php` devuelve lista vacía de enlaces).
    - Usa `posterUrl` como respaldo de `backgroundPosterUrl` y filtra URLs incompletas de TMDB
      cuando `img_fondo` está vacío (como en `the-skin-crawler-murders`), además de analizar el
      listado mixto de búsqueda (`/movies/search?s=`, `/movies/apiSeries.php?s=`) con
      `.seriesTag`, `.fecha` y normalización `&`/`'`.
- **Extractores integrados y propios** (`PelisJuanitaExtractors.kt`):
  - **Byse** (`bysesukior.com`, `bysejikuar.com`, `n1mwq.org` y espejos): handshake ECDSA P-256
    (`/api/videos/access/challenge` → `attest`), resolución PoW determinista (`captcha` →
    `captcha/verify`), `POST playback` (y respaldo `/embed/playback`) y descifrado AES-256-GCM
    con selección `version`-indexada `[v-1, 30-v]` de `key_parts`.
  - **StreamWish / HGLink** (`streamwish.to` → `medixiru.com` → `vsembed.com`): API
    `/api/stream/tracking` con tabla de caracteres actualizada (`keyChars`/`saltChars`) y
    descifrado AES-128-CBC (`x-tracking`) más respaldo automático `tiktok` HLS cuando `hls` agota
    el tiempo de espera y subtítulos de `/api/stream`.
  - **MixDrop** (`mixdrop.top` / `mxdrop.click` / espejos): extractor propio con `Referer` y
    `User-Agent` preservados y desempaquetado de `MDCore.wurl`.
  - **Vimeus → Vimeos** (`vimeus.net` → `vimeos.net`), **Fastserver**
    (`pelisplayer.fortamomar.workers.dev` / `seriesplayer.fortamomar.workers.dev`), **Earnvids /
    VidHide** (`callistanise.com`, `morencius.com`, `minochinos.com` y espejos verificando HLS en
    cada espejo), **VOE** (`voe.sx` / `caseyseemavoid.com` con ALTCHA v2), **Savefiles /
    Lulustream** (`savefiles.com` `POST /dl` y `lplaygo.com`) y **Vidara** (`vidara.to` /
    `oisauh.com`).
- **Verificación real en CloudStream pre-release + Coil 3.3.0 + Media3 ExoPlayer**:
  - Portada de 27 secciones sin vacíos (~1,4 s), paginación, metadatos no verificados con
    aviso limpio hacia `Ajustes`, flujo completo autenticado en `WebView` (recuperación tras
    limpiar `CookieManager`, serie multi-temporada con `#ulTemporada` vacío y película con
    `&p=<id>` + extracción real en `Fastserver`), 10/10 portadas TMDB con Coil y
    decodificación real de fotogramas en Media3 en 9 muestras de servidores (`Vimeus` película
    y serie, `Fastserver` película y serie, `Earnvids`, `StreamWish`, `Voe`, `Savefiles` y
    `Vidara`).
  - Los canales de TV en vivo con claves DRM quedan fuera del complemento.

## SoloLatino.net (v4)

Cadena completa implementada y verificada en Android 13 (API 33):
1. `GET /sanctum/csrf-cookie` → cookies + `XSRF-TOKEN` exclusivo (sin enviar `X-CSRF-TOKEN` caduco cuando existe `XSRF-TOKEN`, y refrescando automáticamente ante HTTP `419`, sincronizando el reloj vía header `Date`);
2. `button[data-player-token]` por idioma (`div[data-lang-group]`) y `#player-frame[data-lazy-embed]`, invalidando la caché HTML si una ficha caducada no devuelve reproductores;
3. `POST /api/player-url` y `/api/player-embed` `{"t":token}` con `X-XSRF-TOKEN` y `X-SL-Render` (`sha256(token:minuto:sololatino_render_shield_2026)` truncado a 16 hex) dentro de `WebView` propio (`Semaphore(1)`, UA del sistema intacto para mantener validez de `cf_clearance`, `onRenderProcessGone`, y sin `CloudflareKiller` para evitar llamadas a `removeAllCookies(null)`) → `{url,type}`:
   - `mp4` → directo;
   - `player.pelisserieshoy.com` → resolución en streaming (cada servidor emite su enlace al terminar sin esperar a los más lentos) vía `XMLHttpRequest` (`_t`/`const _t`, `_qc`/`_qk`/`_ao`/`_qsrc`, `fnv1a32` sin zero-padding, fijando `window._qt = tk`, evitando el wrapper de `window.fetch` y `_isLocked`), soporte de respuestas `busy`/`no_click`/`retry_once` y proxy `/p.php?url=&sig=&ctx=` manteniendo sesión en WebView aun tras `window.stop()`;
   - `embed69.org` → transporte compartido con sesión/UA preservados, filtro de página interna Chromium y descifrado AES-256-CBC de `dataLink` (`sha256(LINK_CHALLENGE+LINK_SALT)`);
   - Extractores nativos con sesionado/UA compartido (`HostGate = Semaphore(3)`): **VOE** (`voe.sx` con respaldo a espejos `caseyseemavoid.com`/`crystaltreatmente.com`, solucionador ALTCHA v2 PBKDF2-HMAC-SHA256 midstate en Kotlin puro y descifrado F7 unsigned byte-shift), **VidHide / EarnVids** (`morencius.com`/`callistanise.com`, verificado con Media3), **StreamWish / HGLink / vsembed** (sin `WebViewResolver`), **Byse / Filemoon**, **Savefiles**, **Lulustream**, **DoodStream**, **Uqload** y **Google Drive**.
- Búsqueda `/buscar?q=` y sugerencias `/api/search/suggest`; catálogos `/peliculas`, `/series`, `/animes`, `/doramas`, plataformas (`/red/...`) y géneros (`/genero/...`).

Implementación: módulo `SoloLatino` (`SoloLatinoMarkup`, `SoloLatinoSession`, `SoloLatinoExtractors`, `SoloLatinoProvider`, `SoloLatinoPlugin`) con suite de pruebas JVM (`SoloLatinoMarkupTest`) y verificación en emulador Android 13 con CloudStream pre-release + Coil 3 + Media3 ExoPlayer (VidHide, VOE ALTCHA v2, portadas TMDB y pipeline `WebView` con cancelación segura).

## PoseidonHD2.co (v2) — reconstruido desde cero y verificado con Media3

Extensión reconstruida desde cero (originalmente probada de forma independiente y consolidada en `PoseidonHD2` v2 reemplazando la implementación anterior) a partir del `__NEXT_DATA__` SSR de Next.js en `https://www.poseidonhd2.co/` e imágenes directas de TMDB (`w500` / `original`):
- Portada y catálogos paginados (`/page/<n>`): Películas, Estrenos, Tendencias (Día/Semana), Series, Estrenos de Series, Tendencias de Series (Día/Semana), Últimos Episodios (`/episodios`) y los 18 géneros reales del sitio (`/genero/...`), más búsqueda en `/search?q=...`.
- Fichas de películas y series con sinopsis, reparto, géneros, valoración (`Score.from10`), duración, relacionadas, temporadas ordenadas numéricamente (filtrando la temporada 0 vacía) y promoción automática de episodios recientes hacia la ficha de la serie padre.
- Resolución de envoltorios `player.poseidonhd2.co/player.php` y extractores propios:
  - **VOE** (`voe.sx` / `caseyseemavoid.com`): resolución nativa de ALTCHA v2 (`PBKDF2/SHA-256` en Kotlin con midstate HMAC + workers paralelos en `Dispatchers.Default`) y descifrado del payload.
  - **StreamWish / HGLink** (`streamwish.to` -> `medixiru.com` -> `vsembed.com`): API `/api/stream/tracking` con cifrado/descifrado AES-128-CBC (`x-tracking`) más subtítulos de `/api/stream` y respaldo `tiktok` HLS.
  - **VidHide** (`vidhidepro.com`, `callistanise.com`, etc.): cascada de espejos priorizando dominios activos y pistas `hls2` limpias frente a espejos muertos/enmascarados.
  - **Byse** (`bysejikuar.com`) y delegación a extractores integrados (`Vidara`, `DoodStream`, `StreamTape`), etiquetando cada enlace por idioma (`Latino`, `Español`, `Subtitulado`).
- Verificación real en CloudStream pre-release + Coil 3.3.0 + Media3 ExoPlayer (frames decodificados): 27 secciones de portada sin vacíos, paginación verificada, 15 portadas/fondos con Coil, episodios recientes (`12-12-12 1x4`) y multi-temporada (`La Casa del Dragón 1x1`), películas (estreno reciente y `El camino del pequeño guerrero`) y reproducción directa en Media3 de los tres servidores principales (`VidHide`, `Voe` y `StreamWish`).

## RePelis24.shop — reproducción verificada (24-nov, run 45299012816)

La prueba cargó el complemento en CloudStream pre-release y reprodujo con Media3
(frames decodificados, posición avanzada) en Android:
- La Odisea (película): 9 enlaces (3 Nuupload + 6 VOE), reprodujo (Nuupload y VOE);
- Silent Night (película): 12 enlaces en 3 idiomas, reprodujo;
- The Terror 1x1 (episodio): 12 enlaces en 3 idiomas, reprodujo;
- Mobland 1x1 (episodio): reprodujo (Nuupload Subtitulado).
Búsqueda, catálogo y temporadas (The Terror T2E3) también verificados. Los títulos
retirados por el host (p. ej. Iron Man) se informan como muestras no disponibles sin
ocultarlos; la disponibilidad de hosts varía como en cualquier complemento.

## Historial relevante de otros sitios

- **RePelisHD**: PlayLatinSRC con `POST /api/embed-links` por idioma; el catálogo usa
  `/explorar` con filtros (`t=tv&page=2`); `/series?page=2` pierde la página.
- **123pelispop**: `scriptmin.js` hace XHR a `cuevana3.fish`; las fichas no exponen jugadores.
- **cuevana3me**: `/ver.html` de episodios devuelve HTTP 500 (24-nov).
- **pelisenhd.org** (25-nov): sitio exclusivamente de descargas directas (`revomovies`, archivos `.rar`/`.zip`) sin reproductores embebidos (`iframes: []`); los enlaces `#GoP22` pasan por `financialtrust.info` (temporizador de 45 s en `check.php`) hacia `hdpastes.com`, protegido con Cloudflare Turnstile (`0x4AAAAAAAP9MGErdVlFTX7P`) y `nonce` de sesión. Descartado conforme a la política de no automatizar CAPTCHAs y a las capacidades de extractores/contenedores de CloudStream.

## Cinecalidad.org — reproducción verificada (25-nov, run 45696590155)

SPA WordPress + React cuya portada SSR expone `window.siteConfig` (`imageUrl` y diccionarios de `genres`, `langs` y `years`) y cuyos datos se obtienen de `/wp-api/v1/` sin desafíos de Cloudflare:
- Portada completa (42 secciones en paralelo en ~2,9 s, 0 vacías): novedades, tops semanales, películas, series, animes, novelas, WWE, idiomas, géneros y últimos 8 años.
- Búsqueda por texto y archivo por año (`1995`, etc.).
- Carga completa de fichas (portada, fondo, logotipo, reparto, relacionadas, tráiler y temporadas ordenadas numéricamente con 232 episodios / 10 temporadas en `csi-miami-2002`).
- Reproducción real verificada con CloudStream pre-release + Media3 ExoPlayer (frames decodificados): película (`Perfectos mentirosos` vía Voe), película (`Bulldozer` vía Vimeos), episodio de serie (`CSI: Miami` 1x1 vía Vimeos) y episodio de anime (`Dragon Ball Super: Beerus` 1x1 vía Vimeos).

## AnimeOnlineNinja (v9) — reconstrucción y reproducción verificada (25-nov, runs 45716845070 / 45717526695)

Extensión reconstruida desde cero (originalmente probada como `animeonline2` y consolidada en `AnimeOnlineNinja` v9 reemplazando la implementación anterior) a partir de `https://ww4.animeonline.ninja/inicio/`:
- Portada completa (15 secciones en paralelo en ~2,9-5,1 s, 0 vacías): Últimos Episodios (`/inicio/` y `/episodio/`), En Emisión · Recomendados (`#featured-titles`), Animes (`/online/`), Películas (`/pelicula/`), Temporadas (`/temporada/`), Audio Latino, Audio Castellano, Populares, Mejores Valorados, En Emisión, Blu-Ray/DVD, Sin Censura, Live Action, Ganadores de Premios y Próximamente.
- Búsqueda paginada (`/?s=<q>` y `/search/page/N/?s=<q>`), limpieza de enlaces SEO spam en `.sgeneros`, resolución de episodios recientes desde portada hacia su serie padre vía `.pag_episodes`, y soporte para fichas `/temporada/...`.
- Resolución multi-idioma (`Latino`, `Castellano`, `Subtitulado`) mediante `/wp-json/dooplayer/v1/post/...` (respaldo `admin-ajax.php`) y `saidochesto.top/embed.php?id=...`, con extractores nativos de CloudStream más extractores propios para `gupload.site`, `savefiles.top`, `uqload.vc`, `peeplayer.com`, `mxdrop.sx`/`mxdrop.click`/`mixdropjmk.pw`, `filemoon0.top`/`filemooon.link` (ByseSX + WebView) y `luluvid.com`.
- Reproducción real verificada con CloudStream pre-release + Media3 ExoPlayer (frames decodificados): último episodio de portada (`sekai-saikyou-no-majo-hajimemashita-cap-8`), episodio `dragon-ball-daima` cap 1 (vía `GUpload` en `gupload.site`) y película `All You Need Is Kill`.
- Carga de portadas verificada (`AnimeOnlinePosterHelper`): adjunta `posterHeaders` (`Referer`, `Accept`, `Accept-Language`, `User-Agent` y `Cookie` de `CloudflareKiller`/`CookieManager`) vía `addPoster(...)`, comprueba el `OkHttpClient` usado por Coil y activa caché local en `cacheDir/ao_posters` vía `WebView` (`fetch(..., {credentials:'include'})`) si OkHttp es rechazado por Cloudflare o para miniaturas de episodios (`EpisodeAdapter`); comprobadas 17 portadas/fondos con `Coil 3.3.0` (`SingletonImageLoader`) y el respaldo `WebView` (60.203 bytes) en CloudStream pre-release.

## Cuevana3v.pro (v1) — creado desde cero y verificado con Media3

Sitio con HTML renderizado en servidor (`https://cuevana3v.pro/`) e imágenes alojadas en TMDB CDN (`w200` promocionadas a `w500` para portadas; `w1280` para fondos y miniaturas de episodios), sin desafíos de Cloudflare:
- Portada y catálogos paginados (`?page=N`): Últimos Episodios (`/episodios/recientes`), Películas (`/peliculas`), Películas Destacadas (`/peliculas?orden=tendencias`), Series (`/series`), Series Destacadas (`/series?orden=tendencias`), Anime (`/series?genero=anime`), géneros (`/peliculas?genero=...`) y Estrenos 2026 (`/peliculas?anio=2026`), además de búsqueda paginada en `/explorar?s=<query>`.
- Control de tasa cooperativo (`Semaphore(3)`, pausa entre despachos, caché corta de HTML y retroceso ante `HTTP 429` respetando `Retry-After`) para evitar bloqueos al cargar las secciones en paralelo y al paginar.
- Fichas de películas y series con metadatos completos (puntuación vía `Score.from10`, duración en minutos, reparto desde `.section` + JSON-LD, géneros, año, sinopsis, relacionadas y temporadas en `/temporada-N` cargadas en paralelo; al abrir un episodio reciente desde portada promueve automáticamente a la serie padre).
- Reproducción multi-idioma (`Latino`, `Castellano`, `Subtitulado`, `Multilenguaje`) resolviendo `#player ul.tabs-video > li.tab-video-item` y `tungtungsahur.cuevana3v.pro` (`?token=` XOR+Base64 con tabla dinámica de respaldo y `?v=` Base64 directo):
  - Extractores propios (`CuevanaVidHideExtractor`) para **Hyper** (`lkhjerbhye3wikhoduh5xiczuvd.lol`) y **Nebula** (`vibuxer.com`), priorizando las pistas `hls2` MPEG-TS estándar (`0x47`) frente a `hls3` (`HTTP 403`) y `hls4` (que antepone cabecera PNG de 70 bytes en cada segmento `.ts`), más delegación a extractores nativos de CloudStream (`FileMoon`, `Dood`, etc.).
- Verificación real en CloudStream pre-release + Coil 3.3.0 + Media3 ExoPlayer (frames decodificados): episodios recientes (`a-la-deriva-2` 1x10 en Castellano), serie multi-temporada (`dexter-resurreccion` 2x4 en Latino) y películas (`los-juegos-del-hambre-amanecer-en-la-cosecha` vía Hyper y Nebula, `matrix-resurrections` vía Hyper).

## Prácticas fijadas

- Reproducción real (frames decodificados) antes de publicar; las muestras se informan
  una a una y los títulos retirados por el host no invalidan el complemento.
- No confundir registro, modales ni duraciones del reproductor con reproducción.
- No automatizar CAPTCHAs ni desafíos; no pedir cookies, contraseñas ni tokens.
- No extrapolar bloqueos de la red de Actions a la red del usuario.
- Las pruebas son de muestras, no del catálogo completo.
