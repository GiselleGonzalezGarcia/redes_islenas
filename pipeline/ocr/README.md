# OCR pipeline: column-aware re-OCR of the Teguise emigrant list

Re-reads the 2002 printout `data/raw/canarios_ocr.pdf` (852 scanned pages, PDF pages 2-852
without the covers 1, 254, 561) cell by cell, and reconciles the result with the existing
transcription in `data/raw/canarios_todos.xlsx` (sheet `Datos originales`). Nothing in
`data/raw/` is modified. Code lives here; outputs go to `data/work/`.

## Run

```sh
cd /home/christian-weilbach/Development/cinning/pipeline/ocr
sh run_all.sh              # everything, all pages (about 30 min on 6 workers)
sh run_all.sh 2-21         # sample only
```

Steps (each is restartable; `--pages a-b` or `2,4,6`, `--workers` up to 6, `--force` redoes):

| step | command | output |
|---|---|---|
| extract | `ocrpipe.py extract` | `data/work/ocr/jpg/pNNN.jpg` (pdfimages -j), `data/work/ocr/pages.json` (rotation k, deskew angle, orientation scores) |
| layout | `ocrpipe.py layout` | `data/work/ocr/pages/NNN.json` (row/column lines, cell and row boxes in JPEG coordinates) |
| ocr | `ocrpipe.py ocr` | adds per-cell OCR results to `pages/NNN.json` |
| reconcile | `ocrpipe.py reconcile` | `data/work/ocr/records.csv` |
| webp | `ocrpipe.py webp` | `data/work/web/pages/p-NNN.webp`, `data/work/web/rows.json` |
| report | `ocrpipe.py report` | `data/work/ocr/report.md`, `data/work/ocr/review.html` |
| debug | `ocrpipe.py debug --pages 2,3` | grid overlays in `data/work/ocr/debug/` |

Python: `pipeline/ocr/.venv/bin/python -I`. Tesseract 5.5 with `data/work/tessdata` (`-l spa`);
the TSV config is read from the system tesseract install.

## Method

- **Pairing.** The `Control de páginas` sheet gives each record's left PDF page (`NUMERO` to
  `FECHA`) and right page (`BARCO` to `OTROS DATOS`). Page 562 has no right half (its right
  fields are empty in the source). Each physical row is `(left page, row 1-36)`; the xlsx join
  key is `(Página PDF inicial, Fila en la página)`.
- **Orientation.** Each page is tried in four rotations. The score is the number of confident
  dictionary-like words in the top band plus a bonus for table header keywords. The winner is
  recorded per page. All 849 pages turn out to need the same clockwise 90° turn in the sample;
  the code does not assume it.
- **Deskew.** The angle maximises the variance of the row projection of the thresholded table
  area (long grid lines dominate). Lines are not deskewed by warping the JPEG on disk: the
  upright, deskewed image is recomputed from the raw JPEG whenever it is needed.
- **Grid.** Horizontal lines are candidates where a continuous run of at least 150 px of ink
  exists. Rows are fitted as an arithmetic progression (start and pitch searched jointly, about
  49.9 to 50.0 px) giving the header line and 36 data rows. Columns are fitted to a template of
  relative line positions measured on pages 16 (left, 10 lines) and 3 (right, 6 lines), by
  searching an offset and scale that best explains the detected lines; unmatched template lines
  are filled and recorded as anomalies.
- **OCR.** One tesseract call per column (psm 6) over the data rows, with grid lines erased,
  so words cannot merge across columns. Words are assigned to rows by their vertical centre.
  Cells are flagged for re-OCR when empty but inked, low confidence (< 70), or failing field
  validation. Re-OCR crops the cell, upscales it x2 and runs psm 7 (psm 10 as fallback) with a
  digit or S/C/V/SI/NO whitelist where applicable.
- **Validation.** NUMERO, EDAD (0-110), FECHA (1640-1910), OTROS DATOS FAMI and OTROS DATOS
  are digits (with usual misreads O, l, I to 0 and 1); ESTADO is S/C/V (a lower-case o or
  O/Q/G read as C); FIRMA is SI/NO; DESTINO and LOCALIZACION are matched to their closed
  vocabularies; text fields need at least two letters or digits after stripping grid glyphs.
- **Reconcile.** Values are normalised (case, accents, punctuation) before comparing. Status:
  `agree` (equal, or both empty), `ocr_fixes` (xlsx empty or invalid, OCR valid), `xlsx_kept`
  (OCR empty or invalid), `conflict` (both valid and different; for names and places a lexicon
  score built from the xlsx decides when the gap is at least 0.15, otherwise the xlsx value is
  kept with low confidence). ESTADO is compared in the xlsx vocabulary (S = Soltero(a), etc.).
  A printed EDAD of 0 compares as empty, because no xlsx row contains age 0.

## Known limitations

- Printed truncation stays as printed. Names and places are not completed.
- `Isla de Origen` is not on the printout and is not compared.
- Page 14 of the xlsx is misaligned with the printout from about row 2 onward (the printed
  number matches the next xlsx row). The OCR follows the printout. Such pages appear as
  conflicts in the report; the xlsx is not changed.
- Tesseract confidence is a weak signal for short fields; conflict and confidence values are
  heuristics, not calibrated probabilities.
- Faint cells, stamps, and handwritten annotations can still be misread; `review.html` shows
  the 300 most uncertain cells with crops for manual checking.
- Layout fitting assumes the same table geometry on every page (verified on the sample and
  reported as anomalies per page where it fails).
