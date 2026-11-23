# Análisis preliminar de los sitios solicitados

Fecha: 23 de noviembre de 2026. Este documento es una investigación inicial, **no una lista de todos los complementos implementados o verificados**. Los resultados dependen de la red y de la fecha. El diagnóstico HTTP reproducible es `.github/workflows/catalogue-diagnostics.yml`; la ejecución 45217497897 inspeccionó una portada y, salvo excepciones, una ficha o página de reproducción por sitio. No se enviaron formularios ni se siguieron anuncios.

| Sitio | Observación inicial | Pendiente |
| --- | --- | --- |
| repelis24.shop | La portada respondió HTTP 200; la ficha muestreada devolvió 403 desde Actions. | Ver acceso en Android y reproductores, películas, series y búsqueda. |
| repelis24.cz | Implementado en `Repelis24CZ`: catálogos, búsqueda, películas, temporadas y resolución AJAX. Pruebas Android aprobaron paginación, 73 episodios de Juego de Tronos y enlaces de muestra de película y episodio. | La reproducción final depende de extractores y hosts. El idioma de episodios se obtiene de la lista dinámica del sitio; si falla se usa un fallback sin adivinarlo. |
| pelissmart.com | La ficha muestreada envía «Ver en HD» a un formulario de registro. | Investigar otras fuentes reales; no confundir registro con reproducción. |
| pelisplus.fit | Catálogo y página `/play/` accesibles; el ejemplo utiliza un reproductor que pide registro tras 10 segundos. | No tratar el ejemplo como un enlace a una película completa; comprobar si hay otras fuentes auténticas. |
| repelishd.surf | Catálogo Next.js y reproductor PlayLatinSRC en la ficha examinada. | Investigar el reproductor, búsqueda, paginación y temporadas. |
| 123pelispop.pro | Catálogo accesible; las opciones de la ficha de Iron Man usan `data-url="#"`. | Comprobar si hay fuentes reales mediante navegador o si la página solo ofrece registro. |
| cuevana3me.info | Catálogo accesible; la página `/ver.html` de la muestra pausa a los 10 segundos y ofrece registro. | No publicar una muestra como película completa; investigar si hay otras fuentes. |
| pelisplus-to.com | Plantilla similar a pelisplus.fit; la muestra de `/ver/` pausa a los 10 segundos y ofrece registro. | Comprobar fuentes reales y series antes de integrar. |
| sololatino.net | La página visible incluye series y episodios; las peticiones de Actions recibieron 403. | Validar acceso desde Android y extracción de fuentes, búsqueda y temporadas. |
| poseidonhd2.co | Implementado en `PoseidonHD2`: búsqueda, catálogo, idiomas y recuperación de temporadas vacías. Android comprobó fichas y episodios; una muestra de Vidara devolvió manifiesto y segmento. | No se ha probado en la app del usuario. VOE pidió verificación humana y otros servidores fallaron desde Actions. |
| pelicinehd.com | El diagnóstico HTTP recibió 403; la vista web mostró una ventana de Telegram. | Comprobar catálogo, acceso desde Android y reproductores. |
| cinecalidad.my | Prototipo `CinecalidadMY` deshabilitado: catálogo y fichas funcionan en Android, pero el servidor Filemoon muestreado usa Byse y ya no expone el reproductor esperado; VOE solicitó verificación humana. | No se publica hasta probar un extractor de vídeo funcional. |
| cinecalidad.am | Implementado en `CinecalidadAM`: catálogo, búsqueda, temporadas y Vimeos. Android comprobó película y serie de 26 episodios; una muestra de Vimeos devolvió manifiesto y segmento. | No se ha probado en la app del usuario; la disponibilidad depende del servidor. |
| pelisjuanita.com | La vista web muestra una verificación humana con CAPTCHA; Actions recibió 403. | No automatizar la resolución del CAPTCHA; determinar si existe una forma de acceso autorizada y viable en CloudStream. |

Siguiente paso: para cada integración, comprobar navegación paginada, búsqueda, ficha, temporadas completas, películas, resolución de servidores por idioma y reproducción con Android. No subir a la release proveedores incompletos como si estuvieran listos.
