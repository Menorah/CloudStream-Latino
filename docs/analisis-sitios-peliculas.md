# Análisis preliminar de los sitios solicitados

Fecha: 23 de noviembre de 2026. Este documento es una investigación inicial, **no una lista de todos los complementos implementados o verificados**. Los resultados dependen de la red y de la fecha. El diagnóstico HTTP reproducible es `.github/workflows/catalogue-diagnostics.yml`; la ejecución 45217497897 inspeccionó una portada y, salvo excepciones, una ficha o página de reproducción por sitio. No se enviaron formularios ni se siguieron anuncios.

| Sitio | Observación inicial | Pendiente |
| --- | --- | --- |
| repelis24.shop | La portada respondió HTTP 200; la ficha muestreada devolvió 403 desde Actions. | Ver acceso en Android y reproductores, películas, series y búsqueda. |
| repelis24.cz | Implementado en `Repelis24CZ`: catálogos, búsqueda, películas, temporadas y resolución AJAX. Pruebas Android aprobaron paginación, 73 episodios de Juego de Tronos y enlaces de muestra de película y episodio. | La reproducción final depende de extractores y hosts. El idioma de episodios se obtiene de la lista dinámica del sitio; si falla se usa un fallback sin adivinarlo. |
| pelissmart.com | La ficha muestreada envía «Ver en HD» a un formulario de registro. | Investigar otras fuentes reales; no confundir registro con reproducción. |
| pelisplus.fit | Catálogo y página `/play/` accesibles; el ejemplo utiliza un reproductor que pide registro tras 10 segundos. | No tratar el ejemplo como un enlace a una película completa; comprobar si hay otras fuentes auténticas. |
| repelishd.surf | Implementado `RePelisHD`: catálogo paginado, filtros, búsqueda, temporadas y PlayLatinSRC. En CloudStream Android una película reprodujo vídeo en los tres idiomas indicados y un episodio en latino. La serie de muestra arrojó 144 episodios disponibles en 13 temporadas. | La disponibilidad de hosts y enlaces varía. |
| 123pelispop.pro | Catálogo accesible; las opciones de la ficha de Iron Man usan `data-url="#"`. | Comprobar si hay fuentes reales mediante navegador o si la página solo ofrece registro. |
| cuevana3me.info | Catálogo accesible; la página `/ver.html` de la muestra pausa a los 10 segundos y ofrece registro. | No publicar una muestra como película completa; investigar si hay otras fuentes. |
| pelisplus-to.com | Plantilla similar a pelisplus.fit; la muestra de `/ver/` pausa a los 10 segundos y ofrece registro. | Comprobar fuentes reales y series antes de integrar. |
| sololatino.net | La página visible incluye series y episodios; las peticiones de Actions recibieron 403. | Validar acceso desde Android y extracción de fuentes, búsqueda y temporadas. |
| poseidonhd2.co | Prototipo `PoseidonHD2` fuera de la release. Una película reprodujo vídeo en CloudStream; en el episodio de muestra T2E3 no se pudo reproducir: VOE pide verificación, Doodstream devuelve 403, Vidhide falla en DNS y Streamwish muestra pantalla de carga desde Actions. | Investigar servidores de episodios y comprobar reproducción antes de publicar. |
| pelicinehd.com | El diagnóstico HTTP recibió 403; la vista web mostró una ventana de Telegram. | Comprobar catálogo, acceso desde Android y reproductores. |
| cinecalidad.my | Implementado `CinecalidadMY`: películas, búsqueda y géneros paginados. En CloudStream pre-release, cuatro películas reprodujeron mediante VOE en las ejecuciones 45268420130 y 45269931169. | Los demás servidores no funcionaron en esas muestras; VOE puede bloquear o cambiar según la red y el momento. |
| cinecalidad.am | Implementado en `CinecalidadAM`: catálogo, búsqueda, temporadas y Vimeos. Una prueba en CloudStream pre-release en Android decodificó y reprodujo una película y un episodio; la serie de muestra contiene 26 episodios. | La disponibilidad depende del título, servidor, dispositivo y red. |
| pelisjuanita.com | La vista web muestra una verificación humana con CAPTCHA; Actions recibió 403. | No automatizar la resolución del CAPTCHA; determinar si existe una forma de acceso autorizada y viable en CloudStream. |

Siguiente paso: para cada integración, comprobar navegación paginada, búsqueda, ficha, temporadas completas, películas, resolución de servidores por idioma y reproducción con Android. No subir a la release proveedores incompletos como si estuvieran listos.

## Avances del diagnóstico de RePelisHD

La ficha y el episodio de prueba apuntan a PlayLatinSRC. Su página contiene un token temporal y consulta `POST /api/embed-links`: para Iron Man devolvió servidores separados por latino, castellano y subtitulado. Dos de ellos, `dr0pstream.com` y `superplayer.ws`, devolvieron un HLS y un segmento `video/MP2T` desde Actions. Se probaron extractores y reproducción en CloudStream para una película y un episodio. Las fichas muestran inicialmente solo una temporada, por lo que el proveedor consulta las otras temporadas indicadas por los datos del sitio y verifica el número de episodios disponibles.

El catálogo usa `/explorar` con filtros, p. ej. `t=tv` y `page=2`: `/series?page=2` redirige y pierde la página, y `/peliculas` redirige a un listado mixto. No inferir tipo o paginación de esas rutas sin filtros.
