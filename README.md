# CloudStream Latino

Extensiones no oficiales e independientes para [AnimeOnline](https://ww4.animeonline.ninja/), [VerAni.me](https://verani.me/), [RePelis24.cz](https://repelis24.cz/), [RePelis24.shop](https://repelis24.shop/), [RePelisHD](https://repelishd.surf/), [Cinecalidad.am](https://www.cinecalidad.am/), [Cinecalidad.my](https://www.cinecalidad.my/) [PoseidonHD 2](https://www.poseidonhd2.co/) y [PeliCineHD](https://pelicinehd.com/). Ofrecen búsqueda, catálogos de series y películas, episodios y enlaces clasificados por idioma cuando el sitio proporciona esa información.

## Instalación

El índice y el archivo `.cs3` se compilan con GitHub Actions. Tras integrar estos cambios en `main`, el flujo **Compilar extensión** publica `repo.json`, `plugins.json` y los nueve complementos (`AnimeOnlineNinja.cs3`, `VerAni.cs3`, `Repelis24CZ.cs3`, `RePelis24Shop.cs3`, `RePelisHD.cs3`, `CinecalidadAM.cs3`, `CinecalidadMY.cs3`, `PoseidonHD2.cs3`, `PeliCineHD.cs3`) en la release `cloudstream`. Para que CloudStream pueda descargarlos sin autenticación, el repositorio y la release deben ser **públicos**, o los archivos deben alojarse en un servidor público (actualizando las URL en `repo.json` y `build.gradle.kts`). 

1. Comprueba que la ejecución de Actions en `main` terminó correctamente y que la release contiene el índice, el repositorio y los nueve complementos.
2. En CloudStream abre **Ajustes → Extensiones → Repositorios → Añadir repositorio**.
3. Pega esta URL:

   `https://github.com/Menorah/CloudStream-Latino/releases/download/cloudstream/repo.json`

4. Abre el repositorio agregado, instala **AnimeOnlineNinja**, **VerAni**, **Repelis24CZ**, **RePelis24.shop**, **RePelisHD**, **CinecalidadAM**, **CinecalidadMY**, **PoseidonHD2**, **PeliCineHD** o los que desees y activa los proveedores en español si es necesario.

Como alternativa para pruebas privadas, descarga el artefacto `AnimeOnlineNinja` desde la ejecución de Actions, extrae el `.cs3` deseado e impórtalo localmente en CloudStream si tu versión admite instalación de archivos locales. La descarga del artefacto requiere iniciar sesión en GitHub.

## Compilación

Necesitas JDK 17, Android SDK 35 y acceso a los repositorios Maven/JitPack y a las releases de CloudStream. Ejecuta `./gradlew make makePluginsJson`. Los paquetes se generan en `AnimeOnlineNinja/build/AnimeOnlineNinja.cs3`, `VerAni/build/VerAni.cs3`, `Repelis24CZ/build/Repelis24CZ.cs3`, `RePelis24Shop/build/RePelis24Shop.cs3`, `RePelisHD/build/RePelisHD.cs3`, `CinecalidadAM/build/CinecalidadAM.cs3`, `CinecalidadMY/build/CinecalidadMY.cs3`, `PoseidonHD2/build/PoseidonHD2.cs3` y `PeliCineHD/build/PeliCineHD.cs3`; el índice, en `build/plugins.json`. El flujo de ramas y PR solo sube un artefacto; únicamente una ejecución exitosa de `main` actualiza la release. Si las políticas de Actions restringen la escritura, habilita el permiso de escritura de contenidos para el flujo de publicación.

## Limitaciones

El sitio puede presentar un desafío de Cloudflare que CloudStream no siempre puede resolver, redirigir a otro dominio o cambiar su HTML/API. Los hosts externos pueden desaparecer o requerir extractores incompatibles; la extensión no garantiza que todos los enlaces se reproduzcan. Los enlaces derivados del multiserver se etiquetan según el idioma informado por el sitio. Las portadas del dominio protegido se descargan mediante WebView y se guardan en la caché local de CloudStream; si no se pueden obtener, se conserva la URL original. No incluye contenido multimedia ni elude sistemas DRM. Úsala solo con contenido al que tengas derecho a acceder.

Basado en la [documentación de desarrollo](https://recloudstream.github.io/csdocs/devs/gettingstarted/) y la [plantilla oficial](https://github.com/recloudstream/TestPlugins). La implementación se ha contrastado con los patrones públicos de DooPlay usados por [MiPlugin](https://github.com/Anonimo-00/MiPlugin).

### Imágenes de AnimeOnline

En las fichas de AnimeOnline se usa la imagen de fondo que indica el propio sitio. Cuando una portada es una miniatura de WordPress, se intenta obtener su archivo original; si no está disponible, se conserva la miniatura. Los fondos alojados por terceros dependen de la disponibilidad de ese servidor en la red del dispositivo.

### Diagnóstico de acceso

La raíz del dominio puede redirigir a `ver2.animeonline.ninja`, que muestra el botón de entrada; el catálogo real se consulta en `ww4.animeonline.ninja/inicio/`. El proveedor entra directamente en las rutas de catálogo y detecta la portada intermedia si aparece como redirección. Las peticiones tienen límites de espera y, ante un rechazo, el proveedor intenta obtener la respuesta mediante el WebView integrado del dispositivo, no solo reutilizar sus cookies en el cliente HTTP. No comparte cookies con otras aplicaciones. Un desafío interactivo, el bloqueo de la red o la caída de un servidor externo todavía pueden impedir el acceso. El flujo opcional **Diagnóstico de acceso al sitio** comprueba algunas rutas públicas desde GitHub Actions; no demuestra que funcionen desde la red o el dispositivo del usuario.


### Pruebas de Android

El flujo **Pruebas WebView en Android** ejecuta pruebas con WebView real en un emulador usando respuestas simuladas, incluyendo la preservación de errores HTTP. Desde **Actions → Pruebas WebView en Android → Run workflow**, activa `live` para probar también el catálogo y la API reales; dependen de la red y pueden fallar por causas externas. La prueba real de VerAni comprueba catálogos, búsqueda, episodios, idiomas y metadatos. No verifica la reproducción en el dispositivo del usuario.

### VerAni: temporadas y OVAS

Las temporadas y la numeración se toman de las fichas de VerAni, independientemente de si la dirección de un episodio contiene `capitulo-`, comienza por un número o solo tiene un título. Las OVAS sin número se ordenan según la lista del sitio y se enumeran para poder seleccionarlas. Se asignan a la temporada 0; algunas versiones de CloudStream la muestran como «Ninguna temporada», por lo que cada uno de estos episodios conserva también el prefijo OVAS.

### VerAni: estado de los reproductores

VerAni incluye páginas de anime y películas con paginación, búsqueda, fichas y servidores con la etiqueta de audio y subtítulos indicada por la página. Los tops se muestran por clasificación (puntuación, votos, vistas, comentarios, reacciones y favoritos, según corresponda) y se pueden recorrer en varias páginas. Los títulos que el sitio enlaza a otros dominios se omiten porque no son fichas de VerAni compatibles. Las fichas usan la portada y el fondo del título cuando están disponibles, evitando el logotipo genérico del sitio. Los enlaces HLS directos se presentan solo si el servidor devuelve un manifiesto válido; los demás dependen de los extractores disponibles en CloudStream. **Un manifiesto válido no garantiza reproducción:** en las pruebas desde GitHub, los segmentos de Zilla devolvieron HTTP 403 aunque el manifiesto respondió correctamente. Los servidores y su disponibilidad pueden variar según el título, la red y el dispositivo. Si uno falla, prueba otra fuente; no se garantiza que todas funcionen.

### RePelis24.cz

La extensión incluye catálogos paginados de películas, series, tendencias y los géneros del sitio, además de búsqueda y fichas. Los enlaces de películas y episodios se etiquetan según el idioma de cada opción en la web. Si no se puede recuperar la lista de un episodio, se intenta la búsqueda alternativa y se conservan como «Idioma no indicado», sin adivinarlo. La disponibilidad de los reproductores externos depende de la red y de los extractores de CloudStream.

### RePelis24.shop

Dominio nuevo del mismo catálogo que RePelis24.cz, con HTML propio: catálogos paginados (películas, series, estrenos y los géneros del sitio), búsqueda paginada y fichas de películas y series. Las series se leen desde `p.item-season-title` y cada temporada se consulta en `/temporada/<slug>-<n>/` para recuperar sus episodios, de modo que se muestran todas las temporadas sin pedir páginas extra al usuario.

Los reproductores no se enlazan directamente: cada opción es un `li[data-server]` con un Base64 que apunta a un envoltorio propio (`nupload.my`). El complemento lo resuelve: los enlaces `/iframe/?url=` se envían al extractor correspondiente del host embebido y las páginas `/watch/<uid>` se decodifican para obtener el manifiesto HLS firmado, que se etiqueta con el idioma del panel (`Latino`, `Español Castellano`, `Subtitulado`). El manifiesto exige `Referer: https://nupload.my/`, así que ese encabezado se fija en el enlace resultante. La disponibilidad de cada servidor varía por título y por red; si una opción falla, prueba otra.

Las imágenes (portadas, fondos de las fichas y miniaturas de episodios) se sirven desde `/cdn/` detrás de Cloudflare, que responde 403 con una página de verificación si la petición no lleva `Referer` del sitio. Como el cargador de imágenes de CloudStream no envía `Referer` por su cuenta, cada respuesta del complemento adjunta los encabezados necesarios (`Referer`, `Accept` y `Accept-Language`) con `addPoster(...)`, de modo que las portadas y los fondos se muestran. Las miniaturas de la lista de episodios se omiten a propósito: la API de CloudStream no permite adjuntar encabezados a un episodio y el CDN rechaza toda petición sin `Referer`, así que se muestran filas sin miniatura en lugar de imágenes rotas. Si alguna imagen queda en blanco tras actualizar, borra la caché de imágenes de CloudStream, ya que una respuesta 403 anterior puede estar guardada.

El sitio responde con lentitud (unos 10-15 s por página incluso con la caché de Cloudflare caliente) y rechaza peticiones esporádicas. El complemento pide un máximo de ocho catálogos a la vez, reintenta cada página hasta tres veces con esperas cortas y devuelve un catálogo vacío en lugar de tumbar la pantalla de inicio si una sección falla; además amplía los tiempos de espera (`getMainPageTimeoutMs`, `searchTimeoutMs`, `loadTimeoutMs`) y usa 45 s por petición. Si aun así aparece «Connection Timeout», suele ser la red del dispositivo o un bloqueo temporal de Cloudflare: reintenta desde el botón de la pantalla o desde «Reintentar conexión», y comprueba que el sitio abra en el navegador del dispositivo.

Los cambios del sitio pueden requerir ajustes: los flujos **Verificación real RePelis24.shop** y **Diagnóstico imágenes RePelis24.shop** (este último solo manual, desde la pestaña Actions) recorren catálogo, búsqueda, ficha, reproductores, temporadas, episodios, manifiesto y entrega de imágenes contra el sitio real y publican el resumen en las anotaciones del chequeo.

### PeliCineHD

El sitio usa el tema WordPress *torofilm*, con el catálogo dentro de `article.post.movies` / `article.post.series`. La extensión incluye películas, series, búsqueda paginada (`/?s=`) y las dieciséis secciones del inicio (estrenos, tendencias, los géneros del sitio y las listas de series). Cada ficha se lee con sus datos reales: título, portada, sinopsis, año, géneros, reparto, duración y puntuación; las series añaden temporadas y episodios.

Las temporadas se descubren en `div.choose-season a[data-post][data-season]`. El sitio solo dibuja una temporada en la página, así que el resto se pide con el mismo AJAX del tema (`POST /wp-admin/admin-ajax.php`, `action=action_select_season`) y se reutiliza el mismo analizador sobre la respuesta. La numeración se toma de `span.num-epi` (`1x6`) y no se renumera: si el sitio salta un episodio, la lista conserva el hueco.

Los reproductores están en `aside#aa-options`, una opción por `div#options-<n>`, con un `iframe` que apunta a un envoltorio del propio sitio (`/?trembed=<n>&trid=<id>&trtype=1`). El complemento pide cada envoltorio para obtener el hoster final (Voe, Minochinos, etc.) y entrega el enlace al extractor correspondiente de CloudStream, etiquetado con el idioma que indica el panel (`Latino`, `Castellano`, `Subtitulado`). Si un envoltorio falla, se conserva la opción tal cual en lugar de perder el servidor. La reproducción depende de los extractores instalados y de la red del dispositivo, como en el resto de complementos.

**Versión 6: fuera la fila «Destacados».** La portada guardada por el usuario no contiene ninguna diapositiva de carrusel (solo el tema imprime el JS `owl_carousel`, sin diapositivas), así que esa fila salía siempre vacía. Se eliminó junto con el selector de carrusel: las filas son ahora las secciones reales del sitio (Películas, Series y sus géneros).

**Versión 5: películas que se creían series y temporadas desordenadas.** Con las páginas reales guardadas por el usuario se midieron dos errores:

- **Toda película se clasificaba como serie.** La ficha de una película (La Odisea, por ejemplo) también trae `<section class="section episodes">`, vacía, y esa marca bastaba para tratarla como serie: el complemento buscaba episodios, no había, y la pantalla mostraba «PeliCineHD devolvió la ficha pero ningún episodio». Ahora una página es serie solo si `article.post.single` tiene la clase `series`, si hay selector de temporada (`div.choose-season`) o si hay tarjetas de episodio reales (`article.post.episodes`, `#episode_by_temp`). Además, una ficha sin título informa un error propio en lugar de quedar vacía.
- **Faltaba la tercera temporada.** El sitio imprime las temporadas desordenadas (`Season 2`, `Season 1`, `Season 3` en Los Anillos del Poder) y el número se deducía de la posición, quedándose en 2. Ahora se lee siempre de `data-season` y se ordena por número; `dt.n_s` (que en esa página vale 2, la temporal visible) solo se usa como respaldo y nunca para numerar el resto. La temporada que la página no dibuja se pide por AJAX, como antes.

**Versión 4: ficha, temporadas, reproductores y portada.**

- **«Error loading» al abrir una ficha.** Cuando la ficha llegaba pero la lista de episodios salía vacía, el complemento devolvía `null` y CloudStream mostraba el genérico «Error loading, try again later». Ahora la causa se informa como error propio. En paralelo se corrigió la lectura de temporadas.
- **Faltaban temporadas.** El tema no siempre emite `data-season`: algunas series publican la temporada con solo `data-post`, con el número en el texto (`Season 3`, `Temporada 3`) o sin atributos. El analizador exigía `li` y `data-season`, así que se perdían temporadas (Los Anillos del Poder mostraba 2 de 3). Ahora se aceptan las tres formas y el número se deduce del atributo, del texto o del orden. Si la página no indica el `post` de la temporada, la temporada se lista igualmente; solo se omite la petición AJAX que no se puede hacer.
- **«Enlaces no encontrados».** Cada opción del panel es un envoltorio del propio sitio (`?trembed=…`), y **ningún extractor de CloudStream conoce `pelicinehd.com`**, así que extraer esa URL siempre devolvía lista vacía. Ahora el envoltorio se abre primero y se extrae el **embed real** (iframe directo, `data-src` diferido o la URL `.m3u8`/`.mp4` que arma el script del reproductor). Solo si no aparece ningún embed se intenta la URL del envoltorio como último recurso.
- **Carrusel de portada.** Se añadió la fila **Destacados** (retirada en la versión 6, ver arriba).

**Cloudflare: identificación del cliente (corregido en la versión 3).** El WebView de Android añade `; wv` a su User-Agent, y Cloudflare trata cualquier `wv` como bot: responde `403` antes incluso de mostrar el desafío. Por eso la versión 2 seguía dando «HTTP 403» en el dispositivo aunque el sitio abriera bien en el navegador (el navegador real no lleva `wv`). La versión 3 envía un User-Agent de Chrome para Android en las dos vías (cliente HTTP y WebView), y además:

- Abre la portada **una sola vez** en el navegador para que Cloudflare entregue `cf_clearance`; esas cookies se adjuntan a todas las peticiones HTTP posteriores. Solo si con las cookies el HTTP sigue rechazado se pide la página dentro del navegador, de modo que una sesión normal abre el navegador una vez y no una vez por página.
- Envía `User-Agent`, `Accept`, `Accept-Language`, `Upgrade-Insecure-Requests` y `Referer` en todas las peticiones, incluidas las del AJAX de temporadas (que también admite la vía del navegador si el HTTP falla).
- No confunde un `404` normal con un rechazo: solo `403/429/503` y la página del desafío activan la vía del navegador.

**Cloudflare y pantalla vacía (corregido en la versión 2):** el sitio responde a muchas redes con el desafío JavaScript de Cloudflare («Just a moment…»), a veces con un `403` y a veces con un `200` cuyo cuerpo *es* la página del desafío. La versión 1 solo usaba el cliente HTTP y, si ese `200` llegaba, el catálogo se analizaba como si fuera HTML normal: cero títulos. Además, cada sección fallida devolvía una lista vacía en silencio, así que la pantalla mostraba las categorías sin un solo título y sin explicación.

La versión 2 corrige las dos causas:

1. **Transporte con navegador.** Si el cliente HTTP recibe un `403`, un `429`, un `503` o el desafío, la misma página se pide con el WebView fuera de pantalla del dispositivo, que ejecuta el JavaScript del desafío y devuelve el HTML real; el host que funcionó se recuerda para las páginas siguientes. Es el mismo mecanismo que ya usan AnimeOnline y VerAni.
2. **Detección estricta del desafío.** Solo cuentan los marcadores del interstitial (`Just a moment`, `id="challenge-form"`, `id="cf-challenge-running"`, `window._cf_chl_opt`). Cloudflare también inyecta `/cdn-cgi/challenge-platform/...` en páginas normales que usan detección de bots, así que esa cadena por sí sola ya no descarta una página válida.

También se añadieron encabezados de navegador (`Accept`, `Accept-Language`, `Referer`) a todas las peticiones, se ampliaron los tiempos (`getMainPageTimeoutMs` 180 s, `searchTimeoutMs` 60 s, `loadTimeoutMs` 90 s, `loadLinksTimeoutMs` 240 s) y, si ocho secciones seguidas fallan sin ningún éxito, la pantalla muestra el motivo real en lugar de quedarse vacía. Las tarjetas se resuelven también sin el enlace `a.lnk-blk` del tema, para que un cambio menor de HTML no vacíe una fila.

**Estado de la verificación:** el flujo **Verificación real PeliCineHD** se ejecuta en un runner de GitHub, que no tiene WebView y cuya IP recibe el desafío; por eso informa `403` y `challenge: true`. Documenta el bloqueo de los centros de datos, no un fallo del complemento: la ruta con navegador solo puede comprobarse en el dispositivo. El analizador se prueba en su lugar con fragmentos HTML reales del sitio guardados en `PeliCineHD/src/test/resources/` (catálogo con paginación, ficha de película con dos reproductores y ficha de serie con once episodios), de modo que un cambio del tema rompe una prueba unitaria antes de llegar a la aplicación. Las pruebas cubren portadas, título, sinopsis, año, géneros, reparto, duración, puntuación, orden e idioma de los reproductores, temporadas, episodios, paginación, la detección del desafío y el transporte con navegador (incluido que un `200` con la página del desafío nunca se analice como catálogo).

Como el bloqueo depende de la reputación de la IP, el complemento reintenta cada página hasta tres veces con esperas cortas y usa 30 s por petición; si aun así aparece el desafío, suele resolverse abriendo el sitio en el navegador del dispositivo o cambiando de red. La caché de imágenes de CloudStream puede conservar una respuesta de error anterior: bórrala si una portada queda en blanco.

### PoseidonHD 2

La extensión incluye catálogos, búsqueda, películas y series con temporadas. Los enlaces se obtienen de los datos del sitio (`__NEXT_DATA__`) y se etiquetan por idioma (latino, castellano, subtitulado); el envoltorio del reproductor del sitio se resuelve al hoster correspondiente. Su reproducción se verificó en CloudStream con Media3 (una película y tres episodios reproducidos con frames decodificados). Algunos hosts piden verificación (Netu, Doodstream) o fallan según la red; esos casos se informan como muestras no disponibles y la disponibilidad varía como en el resto de complementos.

### Cinecalidad.am y proveedores en investigación

Cinecalidad.am incluye películas, series, búsqueda y catálogo paginado. La versión 3 admite tanto enlaces de episodios `1x1` como los enlaces `s1-e1`. En CloudStream pre-release se comprobaron las cuatro series reportadas y la reproducción de sus primeros episodios con Media3. Esto no garantiza que todos los títulos o servidores funcionen en todas las redes o dispositivos.

Cinecalidad.my ofrece películas con búsqueda, paginación y géneros; en CloudStream pre-release se reprodujeron cuatro películas mediante VOE. Los enlaces están etiquetados con el idioma indicado por el sitio y la disponibilidad de cada servidor puede variar.

### RePelisHD

Incluye películas, series, búsqueda, catálogos por idioma y género, portadas y fondos. Las temporadas y sus episodios se recuperan de los datos del sitio. La comprobación en CloudStream pre-release reprodujo una película de muestra en latino, castellano y subtitulada, y un episodio en latino. Los enlaces se etiquetan según el idioma informado por PlayLatinSRC; la disponibilidad puede variar por título y por servidor.

### Categorías de Cinecalidad.am

La página principal incluye Destacados, Series, los géneros del sitio y secciones por los ocho años más recientes. Para consultar cualquier otro año del archivo, escribe sus cuatro cifras en la búsqueda (por ejemplo, `1995`); sus resultados se pueden seguir paginando. Esto evita cargar decenas de categorías de años a la vez.
