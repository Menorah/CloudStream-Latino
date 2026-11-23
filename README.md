# CloudStream Latino

Extensiones no oficiales e independientes para [AnimeOnline](https://ww4.animeonline.ninja/) y [VerAni.me](https://verani.me/), en fase beta. Ofrecen búsqueda, catálogos de series y películas, episodios y enlaces clasificados por idioma cuando el sitio proporciona esa información.

## Instalación

El índice y el archivo `.cs3` se compilan con GitHub Actions. Tras integrar estos cambios en `main`, el flujo **Compilar extensión** publica `repo.json`, `plugins.json`, `AnimeOnlineNinja.cs3` y `VerAni.cs3` en la release `cloudstream`. Para que CloudStream pueda descargarlos sin autenticación, el repositorio y la release deben ser **públicos**, o los archivos deben alojarse en un servidor público (actualizando las URL en `repo.json` y `build.gradle.kts`). 

1. Comprueba que la ejecución de Actions en `main` terminó correctamente y que la release contiene los cuatro archivos.
2. En CloudStream abre **Ajustes → Extensiones → Repositorios → Añadir repositorio**.
3. Pega esta URL:

   `https://github.com/Menorah/CloudStream-Latino/releases/download/cloudstream/repo.json`

4. Abre el repositorio agregado, instala **AnimeOnlineNinja**, **VerAni** o ambos y activa los proveedores en español si es necesario.

Como alternativa para pruebas privadas, descarga el artefacto `AnimeOnlineNinja` desde la ejecución de Actions, extrae el `.cs3` deseado e impórtalo localmente en CloudStream si tu versión admite instalación de archivos locales. La descarga del artefacto requiere iniciar sesión en GitHub.

## Compilación

Necesitas JDK 17, Android SDK 35 y acceso a los repositorios Maven/JitPack y a las releases de CloudStream. Ejecuta `./gradlew make makePluginsJson`. Los paquetes se generan en `AnimeOnlineNinja/build/AnimeOnlineNinja.cs3` y `VerAni/build/VerAni.cs3`; el índice, en `build/plugins.json`. El flujo de ramas y PR solo sube un artefacto; únicamente una ejecución exitosa de `main` actualiza la release. Si las políticas de Actions restringen la escritura, habilita el permiso de escritura de contenidos para el flujo de publicación.

## Limitaciones

El sitio puede presentar un desafío de Cloudflare que CloudStream no siempre puede resolver, redirigir a otro dominio o cambiar su HTML/API. Los hosts externos pueden desaparecer o requerir extractores incompatibles; la extensión no garantiza que todos los enlaces se reproduzcan. Los enlaces derivados del multiserver se etiquetan según el idioma informado por el sitio. Las portadas del dominio protegido se descargan mediante WebView y se guardan en la caché local de CloudStream; si no se pueden obtener, se conserva la URL original. No incluye contenido multimedia ni elude sistemas DRM. Úsala solo con contenido al que tengas derecho a acceder.

Basado en la [documentación de desarrollo](https://recloudstream.github.io/csdocs/devs/gettingstarted/) y la [plantilla oficial](https://github.com/recloudstream/TestPlugins). La implementación se ha contrastado con los patrones públicos de DooPlay usados por [MiPlugin](https://github.com/Anonimo-00/MiPlugin).

### Diagnóstico de acceso

La raíz del dominio puede redirigir a `ver2.animeonline.ninja`, que muestra el botón de entrada; el catálogo real se consulta en `ww4.animeonline.ninja/inicio/`. El proveedor entra directamente en las rutas de catálogo y detecta la portada intermedia si aparece como redirección. Las peticiones tienen límites de espera y, ante un rechazo, el proveedor intenta obtener la respuesta mediante el WebView integrado del dispositivo, no solo reutilizar sus cookies en el cliente HTTP. No comparte cookies con otras aplicaciones. Un desafío interactivo, el bloqueo de la red o la caída de un servidor externo todavía pueden impedir el acceso. El flujo opcional **Diagnóstico de acceso al sitio** comprueba algunas rutas públicas desde GitHub Actions; no demuestra que funcionen desde la red o el dispositivo del usuario.


### Pruebas de Android

El flujo **Pruebas WebView en Android** ejecuta pruebas con WebView real en un emulador usando respuestas simuladas, incluyendo la preservación de errores HTTP. Desde **Actions → Pruebas WebView en Android → Run workflow**, activa `live` para probar también el catálogo y la API reales; dependen de la red y pueden fallar por causas externas. La prueba real de VerAni comprueba catálogos, búsqueda, episodios, idiomas y metadatos. No verifica la reproducción en el dispositivo del usuario.

### VerAni: estado de los reproductores

VerAni incluye páginas de anime y películas con paginación, búsqueda, fichas y servidores con la etiqueta de audio y subtítulos indicada por la página. Los enlaces HLS directos se presentan solo si el servidor devuelve un manifiesto válido; los demás dependen de los extractores disponibles en CloudStream. **Un manifiesto válido no garantiza reproducción:** en las pruebas desde GitHub, los segmentos de Zilla devolvieron HTTP 403 aunque el manifiesto respondió correctamente. Los servidores y su disponibilidad pueden variar según el título, la red y el dispositivo. Si uno falla, prueba otra fuente; no se garantiza que todas funcionen.
