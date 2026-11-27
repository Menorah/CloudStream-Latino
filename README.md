# CloudStream Latino

Extensiones no oficiales e independientes para [AnimeOnline](https://ww4.animeonline.ninja/inicio/), [VerAni.me](https://verani.me/), [RePelis24.cz](https://repelis24.cz/), [RePelis24.shop](https://repelis24.shop/), [RePelisHD](https://repelishd.surf/), [Cinecalidad.am](https://www.cinecalidad.am/), [Cinecalidad.my](https://www.cinecalidad.my/), [Cinecalidad.org](https://cinecalidad.org/), [PoseidonHD 2](https://www.poseidonhd2.co/), [PeliCineHD](https://pelicinehd.com/), [Cuevana3v](https://cuevana3v.pro/) y [PelisJuanita](https://pelisjuanita.com/). Ofrecen búsqueda, catálogos de series y películas, episodios y enlaces clasificados por idioma cuando el sitio proporciona esa información.

## Instalación

El índice y el archivo `.cs3` se compilan con GitHub Actions. Tras integrar estos cambios en `main`, el flujo **Compilar extensión** publica `repo.json`, `plugins.json` y los doce complementos (`AnimeOnlineNinja.cs3`, `VerAni.cs3`, `Repelis24CZ.cs3`, `RePelis24Shop.cs3`, `RePelisHD.cs3`, `CinecalidadAM.cs3`, `CinecalidadMY.cs3`, `CinecalidadOrg.cs3`, `PoseidonHD2.cs3`, `PeliCineHD.cs3`, `Cuevana3v.cs3`, `PelisJuanita.cs3`) en la release `cloudstream`. Para que CloudStream pueda descargarlos sin autenticación, el repositorio y la release deben ser **públicos**, o los archivos deben alojarse en un servidor público (actualizando las URL en `repo.json` y `build.gradle.kts`). 

1. Comprueba que la ejecución de Actions en `main` terminó correctamente y que la release contiene el índice, el repositorio y los doce complementos.
2. En CloudStream abre **Ajustes → Extensiones → Repositorios → Añadir repositorio**.
3. Pega esta URL:

   `https://github.com/Menorah/CloudStream-Latino/releases/download/cloudstream/repo.json`

4. Abre el repositorio agregado, instala **AnimeOnlineNinja**, **VerAni**, **Repelis24CZ**, **RePelis24.shop**, **RePelisHD**, **CinecalidadAM**, **CinecalidadMY**, **Cinecalidad.org**, **PoseidonHD2**, **PeliCineHD**, **Cuevana3v**, **PelisJuanita** o los que desees y activa los proveedores en español si es necesario.

Como alternativa para pruebas privadas, descarga el artefacto `AnimeOnlineNinja` desde la ejecución de Actions, extrae el `.cs3` deseado e impórtalo localmente en CloudStream si tu versión admite instalación de archivos locales. La descarga del artefacto requiere iniciar sesión en GitHub.

## Compilación

Necesitas JDK 17, Android SDK 35 y acceso a los repositorios Maven/JitPack y a las releases de CloudStream. Ejecuta `./gradlew make makePluginsJson`. Los paquetes se generan en `AnimeOnlineNinja/build/AnimeOnlineNinja.cs3`, `VerAni/build/VerAni.cs3`, `Repelis24CZ/build/Repelis24CZ.cs3`, `RePelis24Shop/build/RePelis24Shop.cs3`, `RePelisHD/build/RePelisHD.cs3`, `CinecalidadAM/build/CinecalidadAM.cs3`, `CinecalidadMY/build/CinecalidadMY.cs3`, `CinecalidadOrg/build/CinecalidadOrg.cs3`, `PoseidonHD2/build/PoseidonHD2.cs3`, `PeliCineHD/build/PeliCineHD.cs3`, `Cuevana3v/build/Cuevana3v.cs3` y `PelisJuanita/build/PelisJuanita.cs3`; el índice, en `build/plugins.json`. El flujo de ramas y PR solo sube un artefacto; únicamente una ejecución exitosa de `main` actualiza la release. Si las políticas de Actions restringen la escritura, habilita el permiso de escritura de contenidos para el flujo de publicación.

## Limitaciones

El sitio puede presentar un desafío de Cloudflare que CloudStream no siempre puede resolver, redirigir a otro dominio o cambiar su HTML/API. Los hosts externos pueden desaparecer o requerir extractores incompatibles; la extensión no garantiza que todos los enlaces se reproduzcan. Los enlaces derivados del multiserver se etiquetan según el idioma informado por el sitio. Las portadas del dominio protegido se descargan mediante WebView y se guardan en la caché local de CloudStream; si no se pueden obtener, se conserva la URL original. No incluye contenido multimedia ni elude sistemas DRM. Úsala solo con contenido al que tengas derecho a acceder.

Basado en la [documentación de desarrollo](https://recloudstream.github.io/csdocs/devs/gettingstarted/) y la [plantilla oficial](https://github.com/recloudstream/TestPlugins). La implementación se ha contrastado con los patrones públicos de DooPlay usados por [MiPlugin](https://github.com/Anonimo-00/MiPlugin).

### Imágenes de AnimeOnline

En las fichas de AnimeOnline se usa la imagen de fondo que indica el propio sitio. Cuando una portada es una miniatura de WordPress, se intenta obtener su archivo original; si no está disponible, se conserva la miniatura. Los fondos alojados por terceros dependen de la disponibilidad de ese servidor en la red del dispositivo.

### Diagnóstico de acceso

La raíz del dominio puede redirigir a `ver2.animeonline.ninja`, que muestra el botón de entrada; el catálogo real se consulta en `ww4.animeonline.ninja/inicio/`. El proveedor entra directamente en las rutas de catálogo y detecta la portada intermedia si aparece como redirección. Las peticiones tienen límites de espera y, ante un rechazo, el proveedor intenta obtener la respuesta mediante el WebView integrado del dispositivo, no solo reutilizar sus cookies en el cliente HTTP. No comparte cookies con otras aplicaciones. Un desafío interactivo, el bloqueo de la red o la caída de un servidor externo todavía pueden impedir el acceso. El flujo opcional **Diagnóstico de acceso al sitio** comprueba algunas rutas públicas desde GitHub Actions; no demuestra que funcionen desde la red o el dispositivo del usuario.


### Pruebas de Android

El flujo **Pruebas WebView en Android** ejecuta pruebas con WebView real en un emulador usando respuestas simuladas, incluyendo la preservación de errores HTTP. Desde **Actions → Pruebas WebView en Android → Run workflow**, activa `live` para probar también el catálogo y la API reales; dependen de la red y pueden fallar por causas externas.

### VerAni.me

Extensión reconstruida para `https://verani.me/` con soporte paginado de **Estrenos Semanales**, **Últimos Animes**, **En Emisión**, **Audio Latino**, **Castellano**, **Más Populares**, **Películas** y géneros principales, además de búsqueda paginada y resolución automática desde capítulos hacia la ficha padre del anime.

Las temporadas y capítulos se ordenan cronológicamente evitando duplicados en los nombres de temporada, asignan cualquier sección de películas relacionadas (como *Boruto*) a su propia pestaña de temporada no-cero y descartan automáticamente el logotipo genérico del sitio como portada en favor de la imagen del anime. Incluye extractores nativos para **AnimeAV1 / TurboEmbed** (conservando la pista de audio en listas maestras HLS separadas), **Vidara**, **Zilla**, **VOE** (espejos y decodificación F7), **Mp4Upload**, **MxDrop** y **Byse**, etiquetando cada enlace con su idioma (`[Latino]`, `[Castellano]`, `[Sub Español]`). Algunos servidores externos como `player.zilla-networks.com` o espejos concretos de Byse pueden aplicar bloqueos por IP de centro de datos o captcha interactivo dependiendo de la red del dispositivo.

### RePelis24.cz

La extensión incluye catálogos paginados de películas, series, tendencias y los géneros del sitio, además de búsqueda y fichas. Los enlaces de películas y episodios se etiquetan según el idioma de cada opción en la web. Si no se puede recuperar la lista de un episodio, se intenta la búsqueda alternativa y se conservan como «Idioma no indicado», sin adivinarlo. La disponibilidad de los reproductores externos depende de la red y de los extractores de CloudStream.

### RePelis24.shop

Dominio nuevo del mismo catálogo que RePelis24.cz, con HTML propio: catálogos paginados (películas, series, estrenos y los géneros del sitio), búsqueda paginada y fichas de películas y series. Las series se leen desde `p.item-season-title` y cada temporada se consulta en `/temporada/<slug>-<n>/` para recuperar sus episodios, de modo que se muestran todas las temporadas sin pedir páginas extra al usuario.

Los reproductores no se enlazan directamente: cada opción es un `li[data-server]` con un Base64 que apunta a un envoltorio propio (`nupload.my`). El complemento lo resuelve: los enlaces `/iframe/?url=` se envían al extractor correspondiente del host embebido y las páginas `/watch/<uid>` se decodifican para obtener el manifiesto HLS firmado, que se etiqueta con el idioma del panel (`Latino`, `Español Castellano`, `Subtitulado`). El manifiesto exige `Referer: https://nupload.my/`, así que ese encabezado se fija en el enlace resultante. La disponibilidad de cada servidor varía por título y por red; si una opción falla, prueba otra.

Las imágenes (portadas, fondos de las fichas y miniaturas de episodios) se sirven desde `/cdn/` detrás de Cloudflare, que responde 403 con una página de verificación si la petición no lleva `Referer` del sitio. Como el cargador de imágenes de CloudStream no envía `Referer` por su cuenta, cada respuesta del complemento adjunta los encabezados necesarios (`Referer`, `Accept` y `Accept-Language`) con `addPoster(...)`, de modo que las portadas y los fondos se muestran. Las miniaturas de la lista de episodios se omiten a propósito: la API de CloudStream no permite adjuntar encabezados a un episodio y el CDN rechaza toda petición sin `Referer`, así que se muestran filas sin miniatura en lugar de imágenes rotas. Si alguna imagen queda en blanco tras actualizar, borra la caché de imágenes de CloudStream, ya que una respuesta 403 anterior puede estar guardada.

El sitio responde con lentitud (unos 10-15 s por página incluso con la caché de Cloudflare caliente) y rechaza peticiones esporádicas. El complemento pide un máximo de ocho catálogos a la vez, reintenta cada página hasta tres veces con esperas cortas y devuelve un catálogo vacío en lugar de tumbar la pantalla de inicio si una sección falla; además amplía los tiempos de espera (`getMainPageTimeoutMs`, `searchTimeoutMs`, `loadTimeoutMs`) y usa 45 s por petición. Si aun así aparece «Connection Timeout», suele ser la red del dispositivo o un bloqueo temporal de Cloudflare: reintenta desde el botón de la pantalla o desde «Reintentar conexión», y comprueba que el sitio abra en el navegador del dispositivo.

Los cambios del sitio pueden requerir ajustes: los flujos **Verificación real RePelis24.shop** y **Diagnóstico imágenes RePelis24.shop** (este último solo manual, desde la pestaña Actions) recorren catálogo, búsqueda, ficha, reproductores, temporadas, episodios, manifiesto y entrega de imágenes contra el sitio real y publican el resumen en las anotaciones del chequeo.

### PoseidonHD 2

Extensión reconstruida desde cero en su versión 2 para `https://www.poseidonhd2.co/` usando el SSR `__NEXT_DATA__` de Next.js. Incluye catálogos paginados mediante `/page/<n>` de películas, estrenos, tendencias diarias y semanales, series, estrenos de series, tendencias de series, últimos episodios (`/episodios`) y los 18 géneros en vivo del sitio, además de búsqueda (`/search?q=...`), fichas completas con reparto, géneros, valoración, recomendaciones, temporadas completas (omitiendo la temporada 0 vacía) y promoción automática de episodios recientes hacia la serie padre. Resuelve los envoltorios `player.poseidonhd2.co/player.php` e incluye extractores propios para **VOE** (`voe.sx` / `caseyseemavoid.com` con resolución nativa de desafío ALTCHA v2 PBKDF2-HMAC-SHA256 + descifrado), **StreamWish / HGLink** (`medixiru.com` -> `vsembed.com` mediante cifrado/descifrado AES-128-CBC en `/api/stream/tracking`), **VidHide** (cascada de espejos `callistanise.com` / `vidhidevip.com` / `peytonepre.com` / `smoothpre.com` priorizando `hls2`) y **Byse** (`bysejikuar.com`), además de delegar en los extractores integrados (`Vidara`, `DoodStream`, `StreamTape`, etc.) etiquetando cada enlace como `Latino`, `Español` o `Subtitulado`. Su reproducción se verificó en CloudStream con Media3 y portadas en Coil; la disponibilidad de cada servidor puede variar según la red y el dispositivo.

### Cinecalidad.am y proveedores en investigación

Cinecalidad.am incluye películas, series, búsqueda y catálogo paginado. La versión 3 admite tanto enlaces de episodios `1x1` como los enlaces `s1-e1`. En CloudStream pre-release se comprobaron las cuatro series reportadas y la reproducción de sus primeros episodios con Media3. Esto no garantiza que todos los títulos o servidores funcionen en todas las redes o dispositivos.

Cinecalidad.my ofrece películas con búsqueda, paginación y géneros; en CloudStream pre-release se reprodujeron cuatro películas mediante VOE. Los enlaces están etiquetados con el idioma indicado por el sitio y la disponibilidad de cada servidor puede variar.

### RePelisHD

Incluye películas, series, búsqueda, catálogos por idioma y género, portadas y fondos. Las temporadas y sus episodios se recuperan de los datos del sitio. La comprobación en CloudStream pre-release reprodujo una película de muestra en latino, castellano y subtitulada, y un episodio en latino. Los enlaces se etiquetan según el idioma informado por PlayLatinSRC; la disponibilidad puede variar por título y por servidor.

### Categorías de Cinecalidad.am

La página principal incluye Destacados, Series, los géneros del sitio y secciones por los ocho años más recientes. Para consultar cualquier otro año del archivo, escribe sus cuatro cifras en la búsqueda (por ejemplo, `1995`); sus resultados se pueden seguir paginando. Esto evita cargar decenas de categorías de años a la vez.

### PeliCineHD

Incluye películas (`/peliculas/`), series (`/series/`), estrenos (`/release/2026/`), búsqueda paginada, las categorías del sitio, fichas completas con reparto, recomendaciones, valoración y tráiler, carga de temporadas vía AJAX (`admin-ajax.php`) ordenada numéricamente y resolución de los envoltorios `/?trembed=` y de los reproductores propios (`Morencius` y `Minochinos` / `callistanise.com`, además de los extractores integrados como `Filemoon` y `Voe`), etiquetando cada fuente según el idioma indicado por la página (`Latino`, `Castellano`, `Subtitulado`) y priorizando las pistas `hls2` limpias frente a espejos rotos o enmascarados.

Cuando Cloudflare exige verificación, el complemento resuelve mediante `WebView` integrado (conservando el `User-Agent` nativo para mantener coherentes los encabezados `Sec-CH-UA` y probando como respaldo el perfil Chrome móvil limpio sin `; wv`) y reutiliza esas cookies en peticiones `GET` y `POST`, manteniendo el `WebView` como transporte de respaldo si la red o el sitio rechazan el cliente HTTP. La disponibilidad de cada servidor y la resolución de Cloudflare pueden variar según la red y el dispositivo.

### Cinecalidad.org

Incluye novedades, tops semanales de películas y series, catálogos paginados de películas, series, animes, novelas y WWE, filtros por idioma (`Latino`, `Castellano`, `Subtitulado`), géneros del sitio y archivo por año (los ocho años más recientes en portada y búsqueda por cualquier año de cuatro cifras como `1995`). Consulta directamente la API JSON pública (`/wp-api/v1/`) del sitio junto con las tablas de taxonomía de la portada para ofrecer fichas con portada, fondo, logotipo, reparto, relacionadas, valoración y tráiler, temporadas ordenadas numéricamente y reproducción mediante los reproductores directos del sitio (`Vimeos`, `Voe` y envoltorio de respaldo `videoapp.zip`) etiquetados por idioma y calidad. La disponibilidad de cada servidor puede variar según la red y el dispositivo.

### AnimeOnlineNinja

Extensión reconstruida desde cero en su versión 9 a partir de la estructura actual de `https://ww4.animeonline.ninja/inicio/`. Incluye los módulos de portada (`Últimos Episodios`, `En Emisión · Recomendados`), catálogos paginados de animes (`/online/`), películas (`/pelicula/`), temporadas (`/temporada/`), populares (`/tendencias/`), mejor valorados (`/ratings/`) y filtros (`Audio Latino`, `Audio Castellano`, `En Emisión`, `Blu-Ray / DVD`, `Sin Censura`, `Live Action`, `Ganadores de Premios`, `Próximamente`), además de búsqueda paginada (`/?s=...` y `/search/page/N/?s=...`). En las fichas usa `.sheader .data h1`, limpia los tags SEO no deseados de `.sgeneros`, extrae temporadas completas, reparto y relacionadas, y al abrir un episodio reciente desde portada resuelve hacia la serie padre si dispone de enlace `lista de episodios`. Adjunta encabezados coherentes (`posterHeaders`) y cookies en todas las portadas con respaldo automático mediante caché local vía `WebView` (`cacheDir/ao_posters`) si `OkHttp` es bloqueado por Cloudflare. Para reproducir, consulta primero `/wp-json/dooplayer/v1/post/...` (con respaldo a `admin-ajax.php`), abre el selector multi-idioma `saidochesto.top` etiquetando cada fuente (`Latino`, `Castellano`, `Subtitulado`), y decodifica tanto los extractores integrados de CloudStream (`Vidara`, `Firestream`, `Vixeo`, `Playmate`, `Dood`, `StreamTape`, `Mp4Upload`, `Hexload`) como extractores propios para `gupload.site`, `savefiles.top`, `uqload.vc`, `peeplayer.com`, `mxdrop.sx`/`mixdropjmk.pw`, `filemoon0.top`/`filemooon.link` (ByseSX + WebView) y `luluvid.com`. La disponibilidad de cada servidor puede variar según la red y el dispositivo.

### Cuevana3v

Extensión creada desde cero para `https://cuevana3v.pro/`. Incluye catálogos paginados de últimos episodios (`/episodios/recientes`), películas (`/peliculas`), películas destacadas (`/peliculas?orden=tendencias`), series (`/series`), series destacadas (`/series?orden=tendencias`), anime (`/series?genero=anime`), los géneros principales y estrenos 2026, además de búsqueda paginada (`/explorar?s=...`). Al abrir un episodio reciente desde portada resuelve automáticamente hacia la ficha completa de la serie padre para cargar todas sus temporadas (`/temporada-N`) y episodios, junto con sinopsis, reparto, géneros, duración, puntuación (`Score`), portadas TMDB (`w500`) y fondos (`w1280`). Para reproducir, descifra los tokens XOR+Base64 (`?token=`) y enlaces Base64 (`?v=`) de `tungtungsahur.cuevana3v.pro` (con actualización dinámica del mapa de servidores ante cambios) y extrae los flujos HLS limpios (`hls2` con segmentos MPEG-TS estándar) de **Hyper** (`lkhjerbhye3wikhoduh5xiczuvd.lol`) y **Nebula** (`vibuxer.com`), además de delegar en los extractores integrados (`FileMoon`, `Doodstream`, etc.) etiquetando cada enlace con el idioma indicado por la página (`Latino`, `Castellano`, `Subtitulado`, `Multilenguaje`). Incluye control de concurrencia, caché corta de HTML y retroceso cooperativo frente a respuestas `HTTP 429` del servidor. La disponibilidad de cada servidor puede variar según la red y el dispositivo.

### PelisJuanita

Extensión creada desde cero para `https://pelisjuanita.com/` respetando íntegramente el muro de verificación humana (`/inicio.php`). Incluye:
- **Búsqueda y catálogo base sin CAPTCHA** mediante la API pública `/tv/app/buscar.php` (más de 27 800 títulos con sinopsis, puntuación, géneros y portadas/fondos TMDB) para mostrar la portada, el buscador y las fichas de películas incluso antes de verificar.
- **Verificación humana interactiva en Ajustes (`⚙`)**: desde **Ajustes → Extensiones → PelisJuanita (⚙)** se abre un navegador integrado (`WebView`) con `inicio.php` para que el propio usuario introduzca el código CAPTCHA de 4 dígitos y acepte los términos una vez; el complemento guarda únicamente la cookie de sesión (`PHPSESSID`) y el `User-Agent` coherente en `SharedPreferences` + `CookieManager`, limpiando la sesión y mostrando un aviso claro si caduca.
- **Catálogos completos, series multi-temporada y reproducción multihost** tras la verificación: `movies.php` y `apiSeries.php` (estrenos, populares, últimos episodios, géneros, idiomas y plataformas), carga de todas las temporadas y episodios de `serieInfo.php` mediante `CURRENT_SERIE_ID`, fichas completas (`movieInfo.php`) y extractores propios/integrados para **Vimeus / Vimeos** (HLS + subtítulos VTT reales filtrando pistas vacías), **Fastserver** (`fortamomar.workers.dev`), **Earnvids / VidHide** (`morencius.com`, `callistanise.com`, `minochinos.com`), **StreamWish / HGLink** (`streamwish.to`, `hglink.to`, `medixiru.com`, `vsembed.com`), **VOE** (`voe.sx`), **Savefiles / Lulustream** (`savefiles.com`, `lplaygo.com`), **Byse** (`bysesukior.com`), **MixDrop**, **Dood / Playmogo** y **Vidara**, etiquetando cada fuente como `Latino`, `Español` o `Subtitulado`. La disponibilidad de cada servidor puede variar según la red y el dispositivo.

