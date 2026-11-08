# Redes Isleñas — implementation plan

**Redes Isleñas** — public, open, Spanish-first (ES/EN) website on 19th-century Canary Island emigration,
built on the Teguise emigrant list (Hernández Delgado compilation) plus Cuban
genealogical/parish records. Sibling of `../ciid` (Cuba-Ireland Immigration Database).

## Decisions (2026-11-07)

- **Fully static, GitHub Pages.** Like CIID: one database payload loaded on page load
  into an in-browser Datahike; no server.
- **Stack:** Datahike (JVM pipeline + browser) and Spindel UI from Clojars
  (`org.replikativ/datahike 0.8.x`, `org.replikativ/spindel 0.1.x`), shadow-cljs.
  Built interactively in Chromium via the shadow-cljs REPL and chrome-devtools MCP (`.mcp.json`).
- **Bilingual:** all UI strings + curated texts (intro, sources, glossary, chart captions,
  stories) in ES and EN; record data stays Spanish.
- **Destination port:** Cuba ⇒ Havana. Other destinations get one canonical port
  (La Guaira, San Juan, Montevideo, …).
- **Open data:** CSV download (all records), free use with citation of the team.
- **Source scans are never published** (citation only: volume, page, row). The working scan
  stays out of git; it is used only for re-OCR checks of the transcription.

## Data facts that shape the design

- `data/raw/canarios_todos.xlsx` (fixed version): 16,023 people; 15,298 from Teguise
  (surnames **A–S only**, T–Z volumes missing), 725 from Cuban genealogies and Havana/
  Santiago parish registers (94 with literal transcriptions).
- Destinations: Cuba 92%, Venezuela 514, Puerto Rico 176, Uruguay 99, …
- Years 1840s–1900; peaks 1850s and 1890s. Ages 10–19 are the largest group.
- 3,936 records carry a ship name → **382 voyages** (ship + year + island archive);
  e.g. *Julia* (La Palma, 1891–94, up to 354 people/yr). The rest: year + island only.
- 702 "reclamo" (sent for by a relative), ~6,000 "Cuño de X" (municipal stamp),
  signature yes/no (literacy), group size ("Otros datos fami").
- Scans: printouts of a 2002 database table. Even PDF page = left half (Nº, name,
  natural de, vecino de, estado, edad, firma, destino, fecha), following odd page =
  right half (barco, otras características, otros datos fami, localización, otros datos),
  36 rows per page. Re-OCR with Tesseract `spa` (tessdata_best) is clearly better
  than the PDF's existing text layer; column-aware OCR should fix the remaining merges.

## Architecture

```
data/raw/*.xlsx, *.pdf
   │  pipeline/ocr (Python: OpenCV + Tesseract spa)  → per-cell OCR, row boxes, page WebPs
   │  src/cinning/pipeline (Clojure, JVM)            → clean, gazetteer, voyages, linkage, QA
   ▼
Datahike (file store, data/work/db)  — canonical, provenance per transaction
   ▼  export
resources/public/data/payload.*   (compact; measured & compressed)   ← loaded on page load
resources/public/img/pages/*.webp (≈850 pages, ~100–150 MB total)
resources/public/geo/*.json       (Natural Earth land, Canarian municipalities, routes)
   ▼
Browser: Datahike (memory) + Spindel views + MapLibre/deck.gl + Observable Plot
```

### Data model (client schema, extends CIID's)

- `:person/*` names, sex, civil status, age, est. birth year, signature, occupation,
  kin fields, notes, `:person/record` (ref)
- `:record/*` source ref, PDF pages (left/right), printed page, row, row bbox,
  raw fields, doubtful fields, OCR confidence per field
- `:place/*` name, kind (locality/municipality/island/port/country), parent, lat/lon,
  wikidata id, 19th-c. census population (where available)
- `:voyage/*` ship, year, departure port, destination port, archive, passengers
- `:ship/*` name, rig/type when known, voyages
- `:event/*` departure / baptism / marriage / death (CIID event model) for
  genealogical records
- `:source/*` title, kind, description (ES/EN), citation, external links
- `:context/*` historical events (year, title ES/EN, text, links to public sources)

## Site (routes, Spanish slugs)

| Route | Content |
|---|---|
| `/` | Hero: animated Atlantic crossing + scroll story (islands → ports → ocean), key numbers, entry search |
| `/explorar` | Search & table (fuzzy: accents, b/v, s/z/c, h, ll/y, OCR noise), facets |
| `/persona/:id` | Record view, source row image (highlighted), kin/reclamo links, same-person candidates |
| `/pueblo/:id` | Who left, when, where to, ships, surname mix, rate per 1,000 |
| `/barco/:id` | Voyages, passenger lists, years active |
| `/travesia` | Map lab: La travesía (ships), Flujos (particles), De la aldea al puerto, Reclamos, Intensidad |
| `/datos` | Data-science plots with interpretive texts |
| `/fuentes` | Sources, compilation history, coverage gap A–S, glossary, how to read a record |
| `/historias` | Curated micro-histories |
| `/acerca`, `/descargas` | Team, partners, license, citation, downloads |

### Map animations (MapLibre GL + deck.gl, self-contained GeoJSON basemap)

1. **La travesía** — ships (IconLayer) sail stylized trade-wind routes Canaries → Havana
   (TripsLayer trails), sized by passengers; year timeline with play; click → manifest.
   Unnamed departures as faint particle stream. Dates within a year are illustrative
   (stated in legend). Optional later: route shapes from CLIWOC logbooks.
2. **Flujos** — one particle per emigrant island → destination by decade; filters.
3. **De la aldea al puerto** — archipelago zoom; dots from municipalities to ports.
4. **Reclamos** — arcs from the Americas back to home villages.
5. **Intensidad** — municipal emigrants per 1,000 inhabitants (INE historical censuses), time slider.

`prefers-reduced-motion` and mobile fallbacks for all.

### Plots (Observable Plot)

Departures per year by island with historical annotations · age–sex pyramids by decade ·
signature literacy by island/sex/decade · destinations over time · family vs. solo travel ·
ship activity timeline · surname geography · repeat emigrants · data-quality/coverage.

### Context texts (linked, not copied)

Gaceta de Madrid (BOE colección histórica) for emigration law, Jable (ULPGC) press for
ship notices, Memoria Digital de Canarias for scholarship, Wikidata for places/events,
INE censuses, ULL Dracaena, PARES, Memoria Digital de Lanzarote.

## Phases

| # | Work | Who |
|---|---|---|
| 0a | OCR pipeline: page extraction/orientation/deskew, column+row layout, per-cell OCR with field vocabularies, reconciliation with the xlsx transcription, row bboxes, WebP derivatives, QA report | Claude subagent (Haiku, background), reviewed here |
| 0b | Clojure import: xlsx → Datahike; gazetteer (GRAFCAN/IGN/ISTAC open data + ports); voyages; repeat-emigrant linkage; QA flags; payload export | interactive (REPL) |
| 1 | App skeleton from CIID patterns: payload load, router, i18n, explorar, persona/pueblo/barco/fuente pages, source row image | interactive (Chromium + shadow-cljs REPL) |
| 2 | Maps & animations, scroll story on `/` | interactive |
| 3 | Plots + texts | interactive; texts with historians |
| 4 | Sources, glossary, stories, context timeline | content-driven |
| 5 | Downloads, Zenodo, GitHub Pages deploy, performance pass (payload size, mobile) | interactive |

## Open items

- License agreement (team).
- Historical census populations per municipality (1857/1860/1877/1887/1897) — source & effort.
- Texts from historians (Giselle, José Antonio González Marrero).
