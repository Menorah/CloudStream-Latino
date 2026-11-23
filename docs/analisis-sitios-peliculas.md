# Análisis preliminar de los sitios solicitados

Fecha: 23 de noviembre de 2026. Este documento es una investigación inicial, **no una lista de complementos implementados o verificados**. Los resultados dependen de la red y de la fecha. El diagnóstico HTTP reproducible es `.github/workflows/catalogue-diagnostics.yml`; la ejecución 45217497897 inspeccionó una portada y, salvo excepciones, una ficha o página de reproducción por sitio. No se enviaron formularios ni se siguieron anuncios.

| Sitio | Observación inicial | Pendiente |
| --- | --- | --- |
| repelis24.shop | La portada respondió HTTP 200; la ficha muestreada devolvió 403 desde Actions. | Ver acceso en Android y reproductores, películas, series y búsqueda. |
| repelis24.cz | WordPress/Dooplay; ficha muestra varios servidores por idioma. | Investigar resolución AJAX y probar películas, episodios y paginación. |
| pelissmart.com | Catálogo y ficha accesibles en el diagnóstico. | Determinar si los reproductores ofrecen fuentes completas, búsqueda y series. |
| pelisplus.fit | Catálogo y página `/play/` accesibles; el ejemplo utiliza un reproductor que pide registro tras 10 segundos. | No tratar el ejemplo como un enlace a una película completa; comprobar si hay otras fuentes auténticas. |
| repelishd.surf | Catálogo Next.js y reproductor PlayLatinSRC en la ficha examinada. | Investigar el reproductor, búsqueda, paginación y temporadas. |
| 123pelispop.pro | Catálogo accesible; las opciones de la ficha de Iron Man usan `data-url="#"`. | Comprobar si hay fuentes reales mediante navegador o si la página solo ofrece registro. |
| cuevana3me.info | Catálogo accesible; la página `/ver.html` de la muestra pausa a los 10 segundos y ofrece registro. | No publicar una muestra como película completa; investigar si hay otras fuentes. |
| pelisplus-to.com | Plantilla similar a pelisplus.fit; la muestra de `/ver/` pausa a los 10 segundos y ofrece registro. | Comprobar fuentes reales y series antes de integrar. |
| sololatino.net | La página visible incluye series y episodios; las peticiones de Actions recibieron 403. | Validar acceso desde Android y extracción de fuentes, búsqueda y temporadas. |
| poseidonhd2.co | Next.js; incluye metadatos, pósteres, fondos y servidores clasificados por idioma en `__NEXT_DATA__`. El intermediario del reproductor de la muestra de película y episodio contiene una URL de Voe. | Las temporadas posteriores pueden cargarse dinámicamente: la página de Ciudad de Dios enumeró episodios de T1 pero dejó T2 vacía, aunque T2E3 existe. Localizar todas las temporadas, búsqueda y comprobar extracción/reproducción real. |
| pelicinehd.com | El diagnóstico HTTP recibió 403; la vista web mostró una ventana de Telegram. | Comprobar catálogo, acceso desde Android y reproductores. |
| cinecalidad.my | Catálogo y ficha accesibles; muestra opciones de servidores externos. | Investigar cómo se obtienen las URL reales y verificar reproducción. |
| cinecalidad.am | Portada accesible; la vista de ficha muestra varios servidores. | Investigar reproductores, películas y series. |
| pelisjuanita.com | La vista web muestra una verificación humana con CAPTCHA; Actions recibió 403. | No automatizar la resolución del CAPTCHA; determinar si existe una forma de acceso autorizada y viable en CloudStream. |

Siguiente paso: para cada integración, comprobar navegación paginada, búsqueda, ficha, temporadas completas, películas, resolución de servidores por idioma y reproducción con Android. No subir a la release proveedores incompletos como si estuvieran listos.
