# Task: column-aware re-OCR of the Teguise emigrant list

Work only inside `pipeline/ocr/` (code) and `data/work/` (outputs). Do not modify
`data/raw/`, do not commit to git, no network needed (everything is installed).

## Inputs

- `data/raw/canarios_ocr.pdf` — 852 pages, scanned printouts (2002) of a database table,
  one embedded 300-dpi JPEG per page (2543×3496, stored portrait; the page is landscape).
  Page 2 needs a 90° **clockwise** rotation to read upright — do not assume this holds
  for every page; detect orientation per page (e.g. `tesseract --psm 0` OSD, or header
  position) and record it.
- Pairing: even PDF page = left half of the table, the following odd page = right half.
  Covers at PDF pages 1, 254, 561 are excluded. Printed page 560 is missing (its 36 records
  have empty right-half fields). See sheet `Control de páginas` in
  `data/raw/canarios_todos.xlsx` (pairing, row counts per page).
  - Left columns: `NUMERO, APELLIDOS Y NOMBRE, NATURAL DE, VECINO DE, ESTADO, EDAD, FIRMA, DESTINO, FECHA`
  - Right columns: `BARCO, OTRAS CARACTERISTICAS, OTROS DATOS FAMI, LOCALIZACION, OTROS DATOS`
  - Typically 36 data rows per page; a header row on top, "Página N" footer, date "28/01/2002" header.
- Existing transcription: sheet `Datos originales` (15,298 rows) with columns
  `Número original, Apellidos y nombre, Natural de, Vecino de, Isla de Origen, Estado, Edad,
  Firma, Destino, Fecha (año), Barco, Otras características, Otros datos fami, Localización,
  Otros datos, Página PDF inicial, Página PDF continuación, Página impresa inicial,
  Fila en la página, Campos con lectura dudosa, Campos con texto en el borde`.
  `(Página PDF inicial, Fila en la página)` identifies the physical row — use it as the join key.
  Note: "Isla de Origen" is not a printed column of the left page; check the header to see
  whether the printout has it and adapt.
- Tesseract 5.5 with Spanish best model: `--tessdata-dir data/work/tessdata -l spa`.
- Python env: `pipeline/ocr/.venv` (opencv-python-headless, numpy, pillow, openpyxl).
  Run scripts with `pipeline/ocr/.venv/bin/python -I`.

## Findings from a probe (page 2)

- The PDF's own text layer is poor. `tesseract -l spa --psm 6` on the upright page is
  much better (accents right, most values right). Errors are mainly merges of adjacent
  narrow columns: `EDAD`+`FIRMA` ("22N0", "17SI"→"1781"), stray grid-line glyphs (`|`, `—`, `_`).
- Grid lines are faint/thin and slightly skewed: a long-kernel morphological line
  detection on a global threshold found nothing. Deskew first; derive column boundaries
  from the header row word boxes (TSV) and/or from vertical-line projections after deskew;
  derive row boundaries from horizontal lines or text baselines. The page layout is the same
  report on every page, so column x-ranges should be stable modulo scan offset/skew.

## Pipeline (CLI with subcommands, each restartable, `--pages a-b`, ≤6 worker processes;
machine has ~7 GB free RAM and ~25 GB free disk — keep total outputs < 3 GB, do not keep
full-resolution PNGs per page)

1. `extract` — `pdfimages -j` into `data/work/ocr/jpg/`; orientation + deskew parameters per
   page into `data/work/ocr/pages.json`.
2. `layout` — per page: column x-ranges and row y-ranges (in upright, deskewed coordinates
   AND mapped back to the original JPEG coordinates), header check, row count vs
   `Control de páginas`.
3. `ocr` — page-level TSV pass, words assigned to cells; then per-cell re-OCR (`--psm 7`)
   for cells that are low-confidence, empty-but-inked, or fail field validation. Field rules:
   - `NUMERO`, `EDAD`, `FECHA`, `OTROS DATOS FAMI`, `OTROS DATOS`: digits (FECHA 1640–1910; EDAD 0–110)
   - `ESTADO`: S/C/V
   - `FIRMA`: SI/NO
   - `DESTINO`, `LOCALIZACION`: closed vocabularies from the xlsx values
   - text fields: strip grid-line glyphs
   Keep tesseract confidences.
4. `reconcile` — join with `Datos originales` by (PDF page, row). Per field:
   - `xlsx` value
   - `ocr` value
   - `chosen` value
   - `status`: `agree` | `ocr_fixes` | `xlsx_kept` | `conflict`
   - `confidence`

   Normalise before comparing (case, accents, whitespace, punctuation).
   - Prefer the value that passes field validation.
   - For names and places, prefer the value closer to a lexicon built from the whole xlsx
     (surnames, given names, place names).
   - Never invent letters for truncated print. Truncation is in the printout itself.
5. `webp` — per PDF page an upright, deskewed grayscale WebP ~1600 px wide, quality ~55,
   target ≤150 KB, into `data/work/web/pages/p-NNN.webp`. Write row boxes in WebP pixel
   coordinates to `data/work/web/rows.json`, keyed by `"page:row"`, as `[x0,y0,x1,y1]`.
6. `report` — `data/work/ocr/report.md`:
   - agreement rate per column
   - counts per status
   - pages with layout anomalies

   Also write `data/work/ocr/review.html`: the 300 most uncertain cells, each as an inline
   crop next to the xlsx value and the OCR value.

Outputs:
- `data/work/ocr/records.csv` — one row per physical row, all fields × {xlsx, ocr, chosen,
  status, confidence}, plus PDF pages, printed page, row, bboxes.
- `data/work/ocr/pages/NNN.json`

## Procedure

First run all steps on PDF pages 2–21. Inspect the results yourself: open some crops and
check the column boundaries visually by writing debug overlays to `data/work/ocr/debug/`.
Iterate until, on rows not flagged doubtful in the xlsx, agreement for ESTADO, EDAD, FIRMA,
FECHA and LOCALIZACION is ≥97%. Then run all pages and write the report. If after three layout/OCR iterations the sample
still misses the target, stop and report what fails instead of running all pages.

Finish with `pipeline/ocr/README.md`, covering how to run and the known limitations, and a
short summary message with the final metrics.
