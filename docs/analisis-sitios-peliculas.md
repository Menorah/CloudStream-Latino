# Análisis de los sitios solicitados

Actualizado: 24 de noviembre de 2026. Este documento combina el diagnóstico reproducible
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
| AnimeOnlineNinja / VerAni | Publicados (tareas previas) | Pendientes conocidos: error 3003 y servidores de AnimeOnline. |
| SoloLatino | **Descartado** (decisión del usuario, 24-nov) | En la red del usuario siempre mostró «Enlaces no encontrados»: los botones `data-player-token` los inserta JavaScript y el sitio va tras Cloudflare; el usuario decidió retirarlo tras varias rondas de arreglos. |
| PeliCineHD | **Descartado** (decisión del usuario, 24-nov) | En la red del usuario devolvió HTTP 403 (Cloudflare) y, con el respaldo WebView, no cargaba contenido; el usuario decidió retirarlo. |
| PoseidonHD2 | **Publicado** | Reproducción representativa verificada el 24-nov: película + 3 episodios reproducidos con Media3; se corrigieron episodios sin enlaces (variante `thisEpisode` y fuentes directas). Ciudad de Dios 2x3 y Yellowjackets 1x1 se informan como no disponibles por sus hosts (DNS/403/JS) desde la red de CI. |
| pelissmart.com | **Sin complemento viable** | Muro de teasers (abajo): todas las fuentes públicas son clips de 4-20 s con duración falseada. |
| pelisplus.fit | **Sin complemento viable** | Ídem. |
| pelisplus-to.com | **Sin complemento viable** | Ídem. |
| cuevana3me.info | **Sin complemento viable** | Ídem; la ruta `/ver.html` de episodios devuelve HTTP 500. |
| 123pelispop.pro | **Sin complemento viable** | La ficha no expone ninguna fuente de vídeo (`data-url="#"`); solo registro y publicidad. |
| pelisjuanita.com | **Sin complemento viable** | Muro de CAPTCHA interactivo de imagen (abajo). |

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

## PelisJuanita.com

El sitio (PHP propio, no WordPress) tiene un muro de verificación interactiva:
`inicio.php?captcha=<n>` muestra un **CAPTCHA de imagen** + casilla de términos + honeypot
(«No llenar este campo»). Toda la web (`/movies/`, `movieInfo.php`, `serieInfo.php`)
redirige a ese muro sin la sesión que emite al resolverlo.

Existe una API para la app de Smart TV en `/tv/app`:
- `home.php` — **pública** (portada con secciones de TV en vivo, películas y series);
- `buscar.php`, `movies.php`, `series.php` — responden `{"error":true,"message":"Acceso no autorizado"}`
  sin la sesión/autorización de la app (probado con y sin cookies, referer y varios UA);
- `hzplayer.php?id=<base64>` — reproductor de canales de TV («Canal no encontrado» con id de película).

La única forma de acceso autorizada y viable sería una verificación **interactiva del
usuario** (p. ej. un WebView donde la persona resuelva el captcha una vez), que exige
puentes de cookies y UI no disponibles de forma estándar para complementos. No se
automatizará el CAPTCHA ni se pedirán cookies. **No se publica complemento.**
Los canales de TV en vivo que expone `home.php` incluyen claves de DRM de TV por
suscripción: quedan fuera de alcance.

## PeliCineHD.com

WordPress con tema Dooplay, detrás de Cloudflare (403 desde la red de CI; accesible
desde navegadores reales). Mecánica documentada de implementaciones públicas
(mangayomi-extensions, alfa-addon) y comprobada parcialmente por HTTP:
- Catálogo `/peliculas/`, `/series/`; búsqueda `/?s=`; fichas `/movies/<slug>/`;
  episodios `/episodios/<slug>-temporada-T-capitulo-E/`;
- Temporadas: `POST /wp-admin/admin-ajax.php` con `action=action_select_season&season=&post=`;
- Servidores: `span.server` por página; cada reproductor es `/?trembed=<n>&trid=<postid>&trtype=<1|2>`,
  una página con `<iframe src>` del hoster (VOE, StreamWish, VidHide, Dood…);
- Idiomas en el texto del servidor («UqLoad - LATÍNO»).

Implementación: módulo `PeliCineHD` (análisis + proveedor), pruebas de análisis en CI.
Pendiente: reproducción real desde una red sin bloqueo (la del usuario).

## SoloLatino.net

Cadena completa documentada (implementaciones MiPlugin y CS_latino + investigación):
1. `GET /sanctum/csrf-cookie` → cookies + `XSRF-TOKEN`;
2. `button.server-btn[data-player-token]` por idioma (`div[data-lang-group]`);
3. `POST /api/player-url` `{"t":token}` con `X-XSRF-TOKEN` y `X-SL-Render`
   (sha256 de `token:minuto:sololatino_render_shield_2026`, 16 hex) → `{url,type}`:
   - `mp4` → directo;
   - `player.pelisserieshoy.com` → handshake `_t/_qc/_qk` → `s.php` (`a=1`, `a=click`,
     `a=2&v=`) → `{u,sig}` → HLS firmado en `p.php?url=&sig=` (el m3u8 directo da 403);
   - Embed69 → `dataLink` descifrado localmente (AES-256-CBC, clave
     `sha256(LINK_CHALLENGE+LINK_SALT)` o PoW local) o vía `POST https://embed69.org/api/decrypt`.
- Búsqueda `/buscar?q=`; catálogos `/peliculas`, `/series`, `/animes`.

Implementación: módulo `SoloLatino` (parsers con fixtures reales Apache-2.0 +
proveedor con la cadena completa + pruebas JVM/Android). El sitio bloquea la red de
CI (403), igual que PeliCineHD; el usuario confirma que reproduce desde su navegador
(Black Clover T1E1) y CloudStream está en esa misma red. Pendiente: verificación de
reproducción desde esa red (la release es el único canal de instalación del usuario).

## PoseidonHD2.co

Sitio Next.js: los datos de reproducción están en `__NEXT_DATA__`
(`thisMovie.videos` / `thisSerie.seasons[].episodes[]` / `episode`|`thisEpisode.videos`)
con claves `latino|spanish|english`, campo `result` (envoltorio
`player.poseidonhd2.co/player.php` con `var url=…` o URL directa del hoster).
Correcciones del 24-nov: aceptar fuentes directas y la variante `thisEpisode`
(algunos episodios quedaban sin enlaces). Estado de hosts: Netu con hCaptcha,
Streamtape 404, Doodstream con verificación; VOE/Streamwish/Vidara reproducen.
Pendiente: repetir la sonda de reproducción con más episodios.

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

## Prácticas fijadas

- Reproducción real (frames decodificados) antes de publicar; las muestras se informan
  una a una y los títulos retirados por el host no invalidan el complemento.
- No confundir registro, modales ni duraciones del reproductor con reproducción.
- No automatizar CAPTCHAs ni desafíos; no pedir cookies, contraseñas ni tokens.
- No extrapolar bloqueos de la red de Actions a la red del usuario.
- Las pruebas son de muestras, no del catálogo completo.
