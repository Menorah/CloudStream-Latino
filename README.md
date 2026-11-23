# CloudStream Latino

Extensiones no oficiales e independientes para [AnimeOnline](https://ww4.animeonline.ninja/), [VerAni.me](https://verani.me/), [RePelis24.cz](https://repelis24.cz/), [RePelisHD](https://repelishd.surf/) y [Cinecalidad.am](https://www.cinecalidad.am/) y [Cinecalidad.my](https://www.cinecalidad.my/), en fase beta. Ofrecen búsqueda, catálogos de series y películas, episodios y enlaces clasificados por idioma cuando el sitio proporciona esa información.

## Instalación

El índice y el archivo `.cs3` se compilan con GitHub Actions. Tras integrar estos cambios en `main`, el flujo **Compilar extensión** publica `repo.json`, `plugins.json`, `AnimeOnlineNinja.cs3`, `VerAni.cs3`, `Repelis24CZ.cs3`, `RePelisHD.cs3`, `CinecalidadAM.cs3` y `CinecalidadMY.cs3` en la release `cloudstream`. Para que CloudStream pueda descargarlos sin autenticación, el repositorio y la release deben ser **públicos**, o los archivos deben alojarse en un servidor público (actualizando las URL en `repo.json` y `build.gradle.kts`). 

1. Comprueba que la ejecución de Actions en `main` terminó correctamente y que la release contiene el índice, el repositorio y los seis complementos.
2. En CloudStream abre **Ajustes → Extensiones → Repositorios → Añadir repositorio**.
3. Pega esta URL:

   `https://github.com/Menorah/CloudStream-Latino/releases/download/cloudstream/repo.json`

4. Abre el repositorio agregado, instala **AnimeOnlineNinja**, **VerAni**, **Repelis24CZ**, **RePelisHD**, **CinecalidadAM**, **CinecalidadMY** o los que desees y activa los proveedores en español si es necesario.

Como alternativa para pruebas privadas, descarga el artefacto `AnimeOnlineNinja` desde la ejecución de Actions, extrae el `.cs3` deseado e impórtalo localmente en CloudStream si tu versión admite instalación de archivos locales. La descarga del artefacto requiere iniciar sesión en GitHub.

## Compilación

Necesitas JDK 17, Android SDK 35 y acceso a los repositorios Maven/JitPack y a las releases de CloudStream. Ejecuta `./gradlew make makePluginsJson`. Los paquetes se generan en `AnimeOnlineNinja/build/AnimeOnlineNinja.cs3`, `VerAni/build/VerAni.cs3`, `Repelis24CZ/build/Repelis24CZ.cs3`, `RePelisHD/build/RePelisHD.cs3` y `CinecalidadAM/build/CinecalidadAM.cs3` y `CinecalidadMY/build/CinecalidadMY.cs3`; el índice, en `build/plugins.json`. El flujo de ramas y PR solo sube un artefacto; únicamente una ejecución exitosa de `main` actualiza la release. Si las políticas de Actions restringen la escritura, habilita el permiso de escritura de contenidos para el flujo de publicación.

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

### Cinecalidad.am y proveedores en investigación

Cinecalidad.am incluye películas, series, búsqueda y catálogo paginado. La versión 3 admite tanto enlaces de episodios `1x1` como los enlaces `s1-e1`. En CloudStream pre-release se comprobaron las cuatro series reportadas y la reproducción de sus primeros episodios con Media3. Esto no garantiza que todos los títulos o servidores funcionen en todas las redes o dispositivos.

PoseidonHD 2 se mantiene como prototipo fuera del índice y de la release: la prueba en CloudStream reprodujo una película, pero no pudo reproducir un episodio de muestra. Se puede compilar solo para diagnóstico con `./gradlew -PenablePoseidon=true :PoseidonHD2:make`. Cinecalidad.my ofrece películas con búsqueda, paginación y géneros; en CloudStream pre-release se reprodujeron cuatro películas mediante VOE. Los enlaces están etiquetados con el idioma indicado por el sitio y la disponibilidad de cada servidor puede variar.

### RePelisHD

Incluye películas, series, búsqueda, catálogos por idioma y género, portadas y fondos. Las temporadas y sus episodios se recuperan de los datos del sitio. La comprobación en CloudStream pre-release reprodujo una película de muestra en latino, castellano y subtitulada, y un episodio en latino. Los enlaces se etiquetan según el idioma informado por PlayLatinSRC; la disponibilidad puede variar por título y por servidor.

### Categorías de Cinecalidad.am

La página principal incluye Destacados, Series, los géneros del sitio y secciones por los ocho años más recientes. Para consultar cualquier otro año del archivo, escribe sus cuatro cifras en la búsqueda (por ejemplo, `1995`); sus resultados se pueden seguir paginando. Esto evita cargar decenas de categorías de años a la vez.
