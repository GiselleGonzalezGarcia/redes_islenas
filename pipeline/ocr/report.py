"""report.md and review.html from records.csv and pages/NNN.json (run: ocrpipe.py report)."""
import base64
import collections
import csv
import html
import json
import os

import cv2
import numpy as np

import ocrpipe as op
from imgutil import upright_gray

REVIEW_N = 300
STATUS_RANK = {'conflict': 0, 'xlsx_kept': 1, 'ocr_fixes': 2, 'agree': 3}


def load_records():
    with open(os.path.join(op.OCR, 'records.csv'), newline='') as fh:
        return list(csv.DictReader(fh))


def shift_check(pages_done):
    """For each PDF page, match OCR printed numbers against xlsx NUMERO at row offsets -1, 0, +1."""
    xr = op.load_xlsx_rows()
    xi = op.load_rows_index(xr)
    out = {}
    for L in pages_done:
        path = op.page_json_path(L)
        if not os.path.exists(path):
            continue
        rec = json.load(open(path))
        if not rec.get('ocr'):
            continue
        res = {}
        for sh in (-1, 0, 1):
            m = n = 0
            for r in range(1, op.ROWS_PER_SIDE + 1):
                ov = rec['ocr']['cells'].get('%d,0' % r, {}).get('t', '')
                xd = xi.get((L, r + sh))
                if xd is None or not ov:
                    continue
                n += 1
                m += str(xd.get('Número original')) == ov
            res[sh] = (m, n)
        # rows where the printed number equals the NEXT xlsx row but not the same row
        nxt = same = 0
        for r in range(1, op.ROWS_PER_SIDE + 1):
            ov = rec['ocr']['cells'].get('%d,0' % r, {}).get('t', '')
            x0 = xi.get((L, r))
            x1 = xi.get((L, r + 1))
            if not ov or x0 is None or x1 is None:
                continue
            a, b = str(x0.get('Número original')), str(x1.get('Número original'))
            if ov == b and ov != a:
                nxt += 1
            elif ov == a:
                same += 1
        res['next_only'] = (nxt, same)
        out[L] = res
    return out


def cell_crop(L, role, r, c, rec_cache):
    if L not in rec_cache:
        rec_cache[L] = json.load(open(op.page_json_path(L)))
    rec = rec_cache[L]
    if rec.get('layout') is None:
        return None
    raw = cv2.imread(op.jpg_path(L), cv2.IMREAD_GRAYSCALE)
    g = upright_gray(raw, rec['k'], rec['angle'])
    lay = rec['layout']
    x0, x1 = lay['col_lines'][c], lay['col_lines'][c + 1]
    y0, y1 = lay['row_lines'][r], lay['row_lines'][r + 1]
    pad = 6
    crop = g[max(0, int(y0) - pad):int(y1) + pad, max(0, int(x0) - pad):int(x1) + pad]
    crop = cv2.resize(crop, None, fx=1.6, fy=1.6, interpolation=cv2.INTER_CUBIC)
    ok, buf = cv2.imencode('.jpg', crop, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return base64.b64encode(buf.tobytes()).decode('ascii') if ok else None


def pick_review(records):
    per_field = collections.defaultdict(list)
    for rec in records:
        for f in op.FIELDS:
            st = rec[f + '_status']
            if st == 'agree':
                continue
            conf = float(rec[f + '_confidence'] or 0)
            occ = rec[f + '_ocr_conf'] or ''
            occ = float(occ) if occ not in ('', 'None') else 0.0
            per_field[f].append((STATUS_RANK[st], conf, occ, rec, f))
    for f in per_field:
        per_field[f].sort(key=lambda t: (t[0], t[1], t[2]))
    # round-robin over fields so the page is not only one column
    picked = []
    idx = {f: 0 for f in per_field}
    while len(picked) < REVIEW_N and any(idx[f] < len(per_field[f]) for f in per_field):
        for f in op.FIELDS:
            if f in per_field and idx[f] < len(per_field[f]) and len(picked) < REVIEW_N:
                picked.append(per_field[f][idx[f]])
                idx[f] += 1
    return picked


def write_review(records):
    picked = pick_review(records)
    cache = {}
    rows_html = []
    for _, conf, occ, rec, f in picked:
        side_left = f in op.LEFT_FIELDS
        L = int(rec['pdf_left']) if side_left else int(rec['pdf_right'] or 0)
        role = 'L' if side_left else 'R'
        fields = op.LEFT_FIELDS if side_left else op.RIGHT_FIELDS
        c = fields.index(f)
        r = int(rec['row'])
        img = cell_crop(L, role, r, c, cache) if L else None
        imgtag = ('<img src="data:image/jpeg;base64,%s">' % img) if img else '<span class="na">no crop</span>'
        rows_html.append(
            '<tr><td>%s</td><td>p%s row %s<br><span class="meta">rec %s</span></td><td>%s</td>'
            '<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>' % (
                imgtag, L, r, html.escape(str(rec['record_index'])), html.escape(f),
                html.escape(rec[f + '_xlsx'] or '(empty)'), html.escape(rec[f + '_ocr'] or '(empty)'),
                html.escape(rec[f + '_chosen'] or '(empty)'), html.escape(rec[f + '_status']),
                '%.2f' % float(rec[f + '_confidence'] or 0)))
    doc = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Revisión OCR</title>
<style>
:root{--bg:#fbfaf7;--fg:#1d1d1b;--mut:#6b6a64;--line:#dcd9d0;--hd:#efece4}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--bg:#17171a;--fg:#ececea;--mut:#9b9a94;--line:#333;--hd:#24242a}}
:root[data-theme="dark"]{--bg:#17171a;--fg:#ececea;--mut:#9b9a94;--line:#333;--hd:#24242a}
body{background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif;margin:16px}
h1{font-size:18px} p{color:var(--mut);max-width:70ch}
table{border-collapse:collapse;width:100%}
th,td{border-bottom:1px solid var(--line);padding:4px 6px;vertical-align:middle;text-align:left}
th{background:var(--hd);position:sticky;top:0}
img{image-rendering:auto;max-height:60px;border:1px solid var(--line);background:#fff}
.meta,.na{color:var(--mut);font-size:12px}
@media (max-width:700px){table,thead,tbody,tr,td,th{display:block}th{display:none}td{border:0;padding:2px 0}tr{border-bottom:1px solid var(--line);padding:6px 0}}
</style></head><body>
<h1>Revisión de celdas inciertas</h1>
<p>@@N@@ celdas, ordenadas por estado (conflicto, xlsx mantenido, OCR corrige, acuerdo) y por confianza. Las imágenes son recortes de la celda en la página ya enderezada. Las columnas: recorte, posición (página PDF y fila), campo, valor xlsx, valor OCR, valor elegido, estado, confianza.</p>
<table><thead><tr><th>Recorte</th><th>Posición</th><th>Campo</th><th>xlsx</th><th>OCR</th><th>Elegido</th><th>Estado</th><th>Conf.</th></tr></thead>
<tbody>@@ROWS@@</tbody></table></body></html>"""
    doc = doc.replace("@@N@@", str(len(picked))).replace("@@ROWS@@", "\n".join(rows_html))
    with open(os.path.join(op.OCR, 'review.html'), 'w') as fh:
        fh.write(doc)
    return len(picked)


def write_report(records, units):
    lines = []
    w = lines.append
    n_rows = len(records)
    n_xlsx = sum(1 for r in records if r['xlsx_found'] == 'True')
    w('# Informe de re-OCR por columnas: Relación de emigrantes canarios')
    w('')
    w('Generado por `pipeline/ocr/ocrpipe.py report`. Un registro por fila física (PDF par izquierda + derecha).')
    w('')
    w('## Resumen')
    w('')
    w('- Filas físicas: %d; filas con correspondencia en `Datos originales`: %d; sin correspondencia: %d.' % (
        n_rows, n_xlsx, n_rows - n_xlsx))
    pages_json = json.load(open(op.PAGES_JSON))
    rot = collections.Counter(v['k'] for v in pages_json.values())
    w('- Orientación detectada por páginas (k = giros horarios de 90°): %s.' % dict(sorted(rot.items())))
    w('- Páginas con orientación dudosa: %d.' % sum(1 for v in pages_json.values() if v['status'] != 'ok'))
    pages_done = [p for p in range(1, 853) if os.path.exists(op.page_json_path(p))]
    w('- Páginas procesadas (con JSON): %d.' % len(pages_done))
    w('')
    w('## Acuerdo por columna')
    w('')
    w('"Acuerdo" = el valor OCR, normalizado, coincide con el xlsx (estado `agree`). "No dudosas" = filas en las que la columna no aparece en `Campos con lectura dudosa` del xlsx. ')
    w('Para EDAD, un 0 impreso se compara como vacío, porque el xlsx no contiene ninguna edad 0.')
    w('')
    w('| Columna | Acuerdo (todas) | Acuerdo (no dudosas) | n no dudosas | agree | ocr_fixes | xlsx_kept | conflict |')
    w('|---|---|---|---|---|---|---|---|')
    for f in op.FIELDS:
        st = collections.Counter(r[f + '_status'] for r in records if r['xlsx_found'] == 'True' or r[f + '_status'])
        ag = sum(1 for r in records if r[f + '_status'] == 'agree')
        nd = [r for r in records if r['xlsx_found'] == 'True' and r[f + '_doubtful'] == '0']
        agn = sum(1 for r in nd if r[f + '_status'] == 'agree')
        w('| %s | %.1f%% | %.1f%% | %d | %d | %d | %d | %d |' % (
            f, 100.0 * ag / max(1, n_rows), 100.0 * agn / max(1, len(nd)), len(nd),
            st['agree'], st['ocr_fixes'], st['xlsx_kept'], st['conflict']))
    w('')
    w('Metas de la muestra (páginas 2-21, no dudosas): ESTADO, EDAD, FIRMA, FECHA, LOCALIZACION >= 97%. Ver `ocr/README.md` para la discusión de las páginas con desplazamiento del xlsx.')
    w('')
    w('## Desplazamientos de fila entre xlsx y el PDF')
    w('')
    sc = shift_check(pages_done)
    flagged = []
    for L, res in sorted(sc.items()):
        nxt, same = res['next_only']
        if nxt >= 5 and nxt >= 0.3 * (nxt + same):
            flagged.append((L, nxt, same))
    if flagged:
        w('Páginas en las que el número impreso coincide con la fila siguiente del xlsx y no con la propia (posible desplazamiento en el xlsx; se conserva el xlsx tal cual):')
        w('')
        w('| PDF | filas = fila siguiente del xlsx | filas = misma fila |')
        w('|---|---|---|')
        for L, nxt, same in flagged:
            w('| %d | %d | %d |' % (L, nxt, same))
    else:
        w('Ninguna página muestra un desplazamiento claro.')
    w('')
    w('## Estados de las celdas')
    w('')
    w('- `agree`: OCR y xlsx coinciden tras normalizar (o ambos vacíos).')
    w('- `ocr_fixes`: el xlsx está vacío o no es válido y el OCR sí da un valor válido; se elige el OCR.')
    w('- `xlsx_kept`: el OCR no produce un valor válido y se mantiene el xlsx.')
    w('- `conflict`: ambos valores son válidos y no difieren de forma decisiva (léxico); se mantiene el xlsx con confianza baja.')
    w('')
    w('## Anomalías de maquetación')
    w('')
    anom = [(int(k), v.get('anomalies', []), v.get('layout') is None) for k, v in
            ((os.path.basename(p)[:-5], json.load(open(p))) for p in
             [op.page_json_path(p) for p in pages_done])]
    bad = [(p, a, nolay) for p, a, nolay in anom if a or nolay]
    w('Páginas con anomalías (%d de %d): ' % (len(bad), len(anom)))
    w('')
    if bad:
        w('| PDF | anomalías |')
        w('|---|---|')
        for p, a, nolay in bad[:200]:
            w('| %d | %s |' % (p, ', '.join(a) if a else 'sin capa'))
        if len(bad) > 200:
            w('| ... | %d más |' % (len(bad) - 200))
    w('')
    w('## Recuentos de OCR')
    w('')
    nre = sum(json.load(open(op.page_json_path(p)))['ocr'].get('n_reocr', 0)
              for p in pages_done[:0]) if False else None
    w('- Celdas con re-OCR por celda: ver `pages/NNN.json` (campo `m` = `reocr` cuando se usó).')
    w('')
    w('## Limitaciones conocidas')
    w('')
    w('- El texto truncado en la impresión se conserva truncado; no se completan nombres.')
    w('- `Isla de Origen` no aparece en el impreso y no se compara.')
    w('- Los campos de texto con caracteres de borde se comparan solo cuando el OCR produce texto válido.')
    w('- La revisión visual se limita a las celdas de `review.html`.')
    w('')
    with open(os.path.join(op.OCR, 'report.md'), 'w') as fh:
        fh.write('\n'.join(lines) + '\n')


def main():
    records = load_records()
    units = op.load_units()
    n = write_review(records)
    write_report(records, units)
    print('report.md and review.html written; review cells:', n)


if __name__ == '__main__':
    main()
