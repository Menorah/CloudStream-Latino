# CloudStream Latino

Repositorio de extensiones no oficiales en **español latino (`es-419`)** para CloudStream.

## Complementos incluidos

- **AnimeOnlineNinja** (anime y películas)
- **Latanime** (anime, películas y OVAs)
- **VerAni** (anime y películas)
- **CinecalidadAM** (películas y series)
- **CinecalidadMY** (películas)
- **CinecalidadOrg** (películas, series y anime)
- **Cuevana3v** (películas, series y anime)
- **GnulaHD** (películas, series y anime)
- **PeliCineHD** (películas y series)
- **PelisJuanita** (películas y series; requiere verificación manual desde Ajustes del complemento)
- **PoseidonHD2** (películas y series)
- **Repelis24CZ** (películas y series)
- **RePelis24Shop** (películas y series)
- **RePelisHD** (películas y series)

## Instalación

1. En CloudStream abre **Ajustes → Extensiones → Repositorios → Añadir repositorio**.
2. Pega la URL del repositorio:

   ```text
   https://github.com/Menorah/CloudStream-Latino/releases/download/cloudstream/repo.json
   ```

3. Instala los complementos que quieras y verifica en los ajustes de idioma que **Español (Latinoamérica)** (`es-419`) esté activo.

## Compilación

Para compilar localmente (JDK 17 y Android SDK 35):

```bash
./gradlew make makePluginsJson
```

Al integrar cambios en `main`, GitHub Actions compila y publica `repo.json`, `repo-icon.png`, `plugins.json` y los paquetes `.cs3` en la release `cloudstream`.

## Aviso

La disponibilidad de catálogos y servidores depende de terceros y de la red del dispositivo. Este proyecto no aloja contenido multimedia ni elude DRM; úsalo solo con contenido al que tengas derecho de acceso.
