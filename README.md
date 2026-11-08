# Redes Isleñas

Base de datos abierta de la emigración canaria a América (siglos XV–XX): buscador de
personas, pueblos y barcos, mapas animados de la travesía y gráficos sobre quiénes
emigraron, desde dónde y hacia dónde.

Open database of Canarian emigration to the Americas (15th–20th century).

**Dirección:** Dra. Giselle González García · Redes Isleñas, 2026

**Cómo citar:** González García, Giselle (dir.). *Redes Isleñas. Base de datos de la
emigración canaria a América*, 2026. https://gisellegonzalezgarcia.github.io/redes_islenas/

Los datos son de libre uso y descarga, con la debida citación y referencia al equipo de
trabajo y sus resultados. Proyecto hermano:
[Cuba-Ireland Immigration Database](https://gisellegonzalezgarcia.github.io/ciid/).

## Fuentes

- Hernández Delgado, Francisco. 2002. *Relación de emigrantes canarios: Clasificados por
  orden alfabético.* Vol. I-III, Archivo Histórico Municipal de Teguise, Lanzarote, Spain.
- *Libro de Barajas: Matrimonios, 1584–1622.* Archivo de la Sagrada Catedral de La Habana.
  Transcribed by Jorge E. Delgadillo Núñez. Slave Societies Digital Archive, Vanderbilt
  University, 2016.
- *Libros de Matrimonios de Remedios.* Transcribed by José Antonio Mahy, Cuban Genealogy
  Club of Miami, 2009.
- Navarrete, William, and Manuel F. Díaz Álvarez. 2023. *Primeras Familias, Poblado y
  Ciudad San Isidoro de Holguín: Estudio de Genealogía Cubana.* [Miami]: UnosOtrosEdiciones.
- Santa Cruz y Mallén, Francisco Xavier de. 1940-1988. *Historia de Familias Cubanas*,
  1-9 Vols. La Habana: Editorial Hércules.

Corrections to the transcription are documented in `data/raw/corrections.edn`.

## Build

Requirements: Clojure CLI, Java 21+, Node.js.

```bash
npm install
clojure -M -m redes.pipeline.core       # workbook → Datahike → resources/public/data/
npx shadow-cljs watch app               # dev server on http://localhost:8090
BASE=/redes_islenas/ scripts/build-site.sh   # static site in site/
scripts/deploy.sh                       # build and publish to the gh-pages branch
```

Stack: Datahike, Spindel, shadow-cljs, MapLibre GL, deck.gl, Observable Plot.
Coastlines: Natural Earth. Village coordinates: Wikidata.
