#!/usr/bin/env python3
"""Column-aware re-OCR of the Teguise emigrant list (see TASK.md, README.md).

Subcommands (all restartable; --pages a-b restricts PDF pages; --workers <= 6):
  extract    pdfimages -> jpg/, orientation (header keywords) + deskew -> pages.json
  layout     grid detection per PDF page -> pages/NNN.json (column/row lines, bboxes)
  ocr        row-strip TSV pass + per-cell re-OCR for flagged cells -> pages/NNN.json
  reconcile  join with 'Datos originales' -> records.csv, rows.json-ready data
  webp       upright deskewed WebP pages + web/rows.json
  report     report.md and review.html
  debug      overlays for a page into debug/
"""
import argparse
import base64
import csv
import difflib
import glob
import html
import json
import multiprocessing as mp
import os
import re
import subprocess
import sys
import time
import unicodedata

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from imgutil import (upright_gray, estimate_skew, rotate_raw, upright_pt_to_raw,  # noqa: E402
                     deskew_pt_to_upright, deskew_matrix, apply_affine_pt)

ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
RAW_PDF = os.path.join(ROOT, 'data/raw/canarios_ocr.pdf')
XLSX = os.path.join(ROOT, 'data/raw/canarios_todos.xlsx')
WORK = os.path.join(ROOT, 'data/work')
OCR = os.path.join(WORK, 'ocr')
JPG = os.path.join(OCR, 'jpg')
PAGES = os.path.join(OCR, 'pages')
DEBUG = os.path.join(OCR, 'debug')
TMP = os.path.join(OCR, 'tmp')
PAGES_JSON = os.path.join(OCR, 'pages.json')
TESSDATA = os.path.join(WORK, 'tessdata')
TSV_CFG = '/usr/share/tesseract-ocr/5/tessdata/configs/tsv'
WEB = os.path.join(WORK, 'web')
WEB_PAGES = os.path.join(WEB, 'pages')
ENV = dict(os.environ, OMP_THREAD_LIMIT='1')

EXCLUDED = {1, 254, 561}
ROWS_PER_SIDE = 36
LEFT_FIELDS = ['NUMERO', 'APELLIDOS Y NOMBRE', 'NATURAL DE', 'VECINO DE', 'ESTADO', 'EDAD', 'FIRMA',
               'DESTINO', 'FECHA']
RIGHT_FIELDS = ['BARCO', 'OTRAS CARACTERISTICAS', 'OTROS DATOS FAMI', 'LOCALIZACION', 'OTROS DATOS']
XLSX_COL = {'NUMERO': 'Número original', 'APELLIDOS Y NOMBRE': 'Apellidos y nombre',
            'NATURAL DE': 'Natural de', 'VECINO DE': 'Vecino de', 'ESTADO': 'Estado', 'EDAD': 'Edad',
            'FIRMA': 'Firma', 'DESTINO': 'Destino', 'FECHA': 'Fecha (año)', 'BARCO': 'Barco',
            'OTRAS CARACTERISTICAS': 'Otras características', 'OTROS DATOS FAMI': 'Otros datos fami',
            'LOCALIZACION': 'Localización', 'OTROS DATOS': 'Otros datos'}
FIELDS = LEFT_FIELDS + RIGHT_FIELDS
ESTADO_MAP = {'S': 'Soltero(a)', 'C': 'Casado(a)', 'V': 'Viudo(a)'}
DIGIT_FIELDS = {'NUMERO', 'EDAD', 'FECHA', 'OTROS DATOS FAMI', 'OTROS DATOS'}
WHITELIST = {'NUMERO': '0123456789', 'EDAD': '0123456789', 'FECHA': '0123456789',
             'OTROS DATOS FAMI': '0123456789', 'OTROS DATOS': '0123456789', 'ESTADO': 'SCV',
             'FIRMA': 'SINO'}
HEADER_KEYWORDS = ['NUME', 'APELL', 'NATUR', 'VECIN', 'ESTAD', 'EDAD', 'FIRMA', 'DESTI', 'FECHA',
                   'BARCO', 'OTRAS', 'FAMI', 'LOCAL', 'OTROS', 'CARACT']
VOCAB = {
    'DESTINO': ['CUBA', 'VENEZUELA', 'PUERTO RICO', 'AMÉRICA', 'SIN DESTINO', 'URUGUAY', 'ARGENTINA',
                'MÉXICO', 'COSTA RICA', 'BRASIL', 'MOGADOR', 'ANTILLAS ESPAÑOLAS', 'INGLATERRA',
                'FRANCIA', 'ULTRAMAR', 'SANTO DOMINGO', 'COLOMBIA', 'SENEGAL, SIERRA LEONA'],
    'LOCALIZACION': ['LA PALMA', 'TENERIFE', 'G. CANARIA'],
}
# Table geometry search windows (deskewed upright coordinates, full resolution)
Y_SEARCH = (230, 2450)
X_SEARCH = (150, 3380)
EXPECTED_COLS = {'L': len(LEFT_FIELDS), 'R': len(RIGHT_FIELDS)}


def ensure_dirs():
    for d in (JPG, PAGES, DEBUG, TMP, WEB, WEB_PAGES):
        os.makedirs(d, exist_ok=True)


def jpg_path(page):
    return os.path.join(JPG, 'p%03d.jpg' % page)


def page_json_path(page):
    return os.path.join(PAGES, '%03d.json' % page)


def parse_range(s):
    if not s:
        return None
    out = set()
    for part in s.split(','):
        if '-' in part:
            a, b = part.split('-')
            out.update(range(int(a), int(b) + 1))
        else:
            out.add(int(part))
    return out


# ----------------------------------------------------------------------------- control sheet

def load_units():
    """Units from 'Control de páginas': left PDF page L, optional right page R."""
    import openpyxl
    wb = openpyxl.load_workbook(XLSX, read_only=True, data_only=True)
    ws = wb['Control de páginas']
    units = []
    for row in ws.iter_rows(min_row=12, values_only=True):
        if row[0] in (None, '') or not str(row[0]).isdigit():
            continue
        L = int(row[0])
        R = int(row[1]) if row[1] not in (None, '') and str(row[1]).isdigit() else None
        units.append({
            'L': L, 'R': R,
            'printed_L': int(row[2]) if row[2] not in (None, '') else None,
            'printed_R': int(row[3]) if row[3] not in (None, '') and str(row[3]).isdigit() else None,
            'first_row': int(row[6]) if row[6] not in (None, '') else None,
            'note': row[8],
        })
    return units


def all_pages(units):
    pages = []
    for u in units:
        pages.append((u['L'], 'L', u))
        if u['R']:
            pages.append((u['R'], 'R', u))
    return sorted(pages)


def selected_pages(args_pages, units):
    sel = parse_range(args_pages)
    out = []
    for p, role, u in all_pages(units):
        if p in EXCLUDED:
            continue
        if sel is not None and p not in sel:
            continue
        out.append((p, role, u))
    return out


# ----------------------------------------------------------------------------- tesseract

def run_tess(img, psm, whitelist=None, tsv=False, tag='t'):
    os.makedirs(TMP, exist_ok=True)
    path = os.path.join(TMP, '%d_%s.png' % (os.getpid(), tag))
    cv2.imwrite(path, img, [cv2.IMWRITE_PNG_COMPRESSION, 1])
    cmd = ['tesseract', path, 'stdout', '--psm', str(psm), '--tessdata-dir', TESSDATA, '-l', 'spa']
    if whitelist:
        cmd += ['-c', 'tessedit_char_whitelist=' + whitelist]
    if tsv:
        cmd.append(TSV_CFG)
    out = subprocess.run(cmd, capture_output=True, text=True, env=ENV).stdout
    return out


def parse_tsv(text):
    words = []
    lines = text.splitlines()
    for ln in lines[1:]:
        f = ln.split('\t')
        if len(f) < 12 or f[0] != '5':
            continue
        t = f[11].strip()
        if not t:
            continue
        words.append({'l': int(f[6]), 't': int(f[7]), 'w': int(f[8]), 'h': int(f[9]),
                      'conf': float(f[10]), 'text': t})
    return words


# ----------------------------------------------------------------------------- orientation

def strip_accents(s):
    return ''.join(ch for ch in unicodedata.normalize('NFKD', s) if not unicodedata.combining(ch))


def orientation_score(upright):
    """(score, header_hits, confident_words) for an upright candidate; higher is better."""
    small = cv2.resize(upright, None, fx=0.6, fy=0.6, interpolation=cv2.INTER_AREA)
    band = small[: int(small.shape[0] * 0.4)]
    words = parse_tsv(run_tess(band, 6, tsv=True, tag='osd'))
    txt = strip_accents(' '.join(w['text'] for w in words)).upper()
    hits = sum(1 for k in HEADER_KEYWORDS if k in txt)
    good = sum(1 for w in words if w['conf'] >= 60 and sum(ch.isalpha() for ch in w['text']) >= 3)
    return good + 10 * hits, hits, good


def job_extract(args):
    page, role, _unit = args
    raw = cv2.imread(jpg_path(page), cv2.IMREAD_GRAYSCALE)
    H0, W0 = raw.shape
    scores, detail = {}, {}
    for k in range(4):
        scores[k], hits, good = orientation_score(rotate_raw(raw, k))
        detail[k] = [hits, good]
    order = sorted(range(4), key=lambda k: -scores[k])
    best = order[0]
    u = rotate_raw(raw, best)
    angle = estimate_skew(u)
    margin = scores[best] - scores[order[1]]
    status = 'ok' if (detail[best][1] >= 10 and margin >= 0.5 * scores[best]) else 'check_orientation'
    return str(page), {'role': role, 'k': best, 'angle': angle, 'raw_h': H0, 'raw_w': W0,
                       'orientation_scores': {str(k): scores[k] for k in range(4)},
                       'header_hits': detail[best][0], 'status': status}


def cmd_extract(args):
    ensure_dirs()
    units = load_units()
    todo = selected_pages(args.pages, units)
    # 1) JPEG passthrough extraction for all pages (one pass over the PDF)
    missing = [p for p, _, _ in todo if not os.path.exists(jpg_path(p))]
    if missing:
        tmpprefix = os.path.join(JPG, 'x')
        subprocess.run(['pdfimages', '-j', RAW_PDF, tmpprefix], check=True)
        for f in glob.glob(tmpprefix + '-*.jpg'):
            idx = int(re.search(r'-(\d+)\.jpg$', f).group(1))
            os.replace(f, jpg_path(idx + 1))
        print('extracted JPEGs', flush=True)
    data = {}
    if os.path.exists(PAGES_JSON):
        with open(PAGES_JSON) as fh:
            data = json.load(fh)
    todo = [t for t in todo if str(t[0]) not in data or args.force]
    print('extract: %d pages to do' % len(todo), flush=True)
    with mp.Pool(args.workers) as pool:
        for i, (key, rec) in enumerate(pool.imap_unordered(job_extract, todo)):
            data[key] = rec
            if i % 50 == 49:
                with open(PAGES_JSON, 'w') as fh:
                    json.dump(data, fh, indent=0, sort_keys=True)
                print('  %d/%d' % (i + 1, len(todo)), flush=True)
    with open(PAGES_JSON, 'w') as fh:
        json.dump(data, fh, indent=0, sort_keys=True)
    bad = [k for k, v in data.items() if v['status'] != 'ok']
    print('extract done: %d pages, rotations %s, low-score pages %s' % (
        len(data), {k: sum(1 for v in data.values() if v['k'] == k) for k in range(4)}, bad[:20]))


# ----------------------------------------------------------------------------- layout

def _groups(idx, gap=6):
    out = []
    for i in idx:
        if out and i - out[-1][-1] <= gap:
            out[-1].append(i)
        else:
            out.append([i])
    return [float(np.mean(g)) for g in out]


def dark_map(gray):
    d = gray.astype(np.float32)
    bg = cv2.GaussianBlur(d, (0, 0), 30)
    return np.clip(bg - d, 0, 255)


def _line_open_frac(dark, x0, x1, y0, y1, horiz, thr=4, run=150):
    """Fraction of the perpendicular extent covered by a continuous run (>= run px) of ink.

    Continuity separates table grid lines from text strokes (short, interrupted)."""
    sub = dark[y0:y1, x0:x1]
    m = ((sub > thr) * 255).astype(np.uint8)
    k = (run, 1) if horiz else (1, run)
    op = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, k))
    return (op > 0).mean(axis=1) if horiz else (op > 0).mean(axis=0)


def line_fraction_h(dark, x0, x1, y0, y1):
    return _line_open_frac(dark, x0, x1, y0, y1, True)


def line_fraction_v(dark, x0, x1, y0, y1):
    return _line_open_frac(dark, x0, x1, y0, y1, False)


def strong_positions(frac, offset, thr):
    idx = np.where(frac >= thr)[0]
    return [c + offset for c in _groups(idx)]


# Column templates (x offsets relative to the first table line), measured on reference pages
# 16 (left) and 3 (right) and checked against 17 (right); used to fit detected lines.
LEFT_TEMPLATE = [0, 146, 730, 1238, 1600, 1799, 1968, 2136, 2504, 2800]
RIGHT_TEMPLATE = [0, 377, 1190, 1626, 2004, 2550]
ROW_PITCH_NOMINAL = 50.08


def fit_row_grid(cands, n_lines=ROWS_PER_SIDE + 2, tol=5.0):
    """Find the arithmetic progression (start, pitch) of n_lines horizontal table lines that the
    most candidate lines fall on. Searching over the pitch avoids drift over 37 rows, and a
    stray line outside the table cannot win because it does not fit the progression."""
    C = np.array(sorted(cands), float)
    if len(C) < 4:
        return None
    best = None
    for p in np.arange(49.0, 51.5, 0.01):
        for s0 in C:
            k = np.round((C - s0) / p)
            err = np.abs(C - (s0 + k * p))
            ok = (k >= 0) & (k < n_lines) & (err <= tol)
            n = int(ok.sum())
            if n == 0:
                continue
            score = n - 0.05 * float(err[ok].mean())
            if best is None or score > best[0]:
                best = (score, p, s0, ok, k)
    if best is None:
        return None
    _, p, s0, ok, k = best
    ks = k[ok]
    ys = C[ok]
    if len(ks) < 4:
        return None
    b, a = np.polyfit(ks, ys, 1)
    grid = a + b * np.arange(n_lines)
    resid = float(np.max(np.abs(a + b * ks - ys)))
    return {'grid': grid, 'pitch': float(b), 'matched': int(len(ks)), 'resid': resid,
            'start_candidate': float(s0)}


def fit_col_template(cands_fracs, template, search=(-40, 40)):
    """Fit template x positions (offset + scale) to candidate vertical lines. Returns (positions, info)."""
    xs = np.array([c for c, _ in cands_fracs], float)
    fr = np.array([f for _, f in cands_fracs], float)
    if len(xs) == 0:
        return None, {}
    T = np.array(template, float)
    best = (-1.0, None, None)
    base = xs.min()
    for scale in np.arange(0.985, 1.0151, 0.0025):
        for off in np.arange(base - 80, base + 80, 1.0):
            pred = off + scale * T
            score = 0.0
            for p in pred:
                j = np.argmin(np.abs(xs - p))
                if abs(xs[j] - p) <= 6:
                    score += fr[j]
            if score > best[0]:
                best = (score, off, scale)
    score, off, scale = best
    pred = off + scale * T
    pos, filled = [], 0
    for p in pred:
        j = np.argmin(np.abs(xs - p))
        if abs(xs[j] - p) <= 6:
            pos.append(float(xs[j]))
        else:
            pos.append(float(p))
            filled += 1
    return pos, {'score': round(score, 2), 'scale': round(float(scale), 4), 'filled': filled}


def layout_page(gray, role, angle_info):
    """Return dict with deskewed row/column lines and anomalies."""
    anomalies = []
    dark = dark_map(gray)
    H, W = gray.shape
    fh = line_fraction_h(dark, X_SEARCH[0], X_SEARCH[1], 200, min(2480, H - 1))
    hc = strong_positions(fh, 200, 0.3)
    grid = fit_row_grid(hc)
    if grid is None:
        anomalies.append('no_row_grid')
        return None, anomalies
    rows = grid['grid']
    if grid['matched'] < 36:
        anomalies.append('row_lines_matched:%d' % grid['matched'])
    if grid['resid'] > 7:
        anomalies.append('row_residual:%.1f' % grid['resid'])
    top, bottom = float(rows[0]), float(rows[-1])
    fv = line_fraction_v(dark, X_SEARCH[0], X_SEARCH[1], int(top) + 10, int(bottom) - 10)
    vc = [(c, float(fv[int(round(c)) - X_SEARCH[0]])) for c in strong_positions(fv, X_SEARCH[0], 0.2)]
    template = LEFT_TEMPLATE if role == 'L' else RIGHT_TEMPLATE
    cols, info = fit_col_template(vc, template)
    if cols is None:
        anomalies.append('no_column_lines')
        return None, anomalies
    if info['filled']:
        anomalies.append('col_lines_filled:%d' % info['filled'])
    if info['score'] < 0.6 * len(template):
        anomalies.append('col_template_weak:%.2f' % info['score'])
    return {'role': role, 'top': top, 'bottom': bottom, 'pitch': grid['pitch'],
            'row_lines': [float(y) for y in rows], 'col_lines': cols,
            'row_matched': grid['matched'], 'col_fit': info,
            'detected_h': grid['matched'], 'detected_v': len(vc), 'anomalies': anomalies}, anomalies


def cell_boxes_raw(lay, k, angle, H0, W0, Hu, Wu):
    """Cell boxes (deskewed upright coords) and their raw-JPEG bounding boxes."""
    M = deskew_matrix((Hu, Wu), angle)
    cols, rows = lay['col_lines'], lay['row_lines']
    raw_boxes = {}
    for r in range(ROWS_PER_SIDE):
        for c in range(len(cols) - 1):
            x0, x1 = cols[c], cols[c + 1]
            y0, y1 = rows[r + 1], rows[r + 2]  # data row r+1 (row 0 = header)
            pts = []
            for (x, y) in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
                ux, uy = deskew_pt_to_upright(M, x, y)
                rx, ry = upright_pt_to_raw(ux, uy, k, H0, W0)
                pts.append((rx, ry))
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            raw_boxes[(r + 1, c)] = [round(min(xs), 1), round(min(ys), 1), round(max(xs), 1), round(max(ys), 1)]
    row_boxes = {}
    for r in range(ROWS_PER_SIDE):
        y0, y1 = rows[r + 1], rows[r + 2]
        x0, x1 = cols[0], cols[-1]
        pts = []
        for (x, y) in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
            ux, uy = deskew_pt_to_upright(M, x, y)
            pts.append(upright_pt_to_raw(ux, uy, k, H0, W0))
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        row_boxes[r + 1] = [round(min(xs), 1), round(min(ys), 1), round(max(xs), 1), round(max(ys), 1)]
    return raw_boxes, row_boxes


def job_layout(args):
    page, role, unit, info = args
    raw = cv2.imread(jpg_path(page), cv2.IMREAD_GRAYSCALE)
    gray = upright_gray(raw, info['k'], info['angle'])
    lay, anomalies = layout_page(gray, role, info)
    if lay is None:
        return page, {'page': page, 'role': role, 'unit_left': unit['L'], 'layout': None,
                      'anomalies': anomalies}
    Hu, Wu = gray.shape
    raw_cells, raw_rows = cell_boxes_raw(lay, info['k'], info['angle'], info['raw_h'], info['raw_w'], Hu, Wu)
    rec = {'page': page, 'role': role, 'unit_left': unit['L'], 'unit_right': unit['R'],
           'k': info['k'], 'angle': info['angle'], 'upright_w': Wu, 'upright_h': Hu,
           'raw_w': info['raw_w'], 'raw_h': info['raw_h'],
           'layout': lay, 'anomalies': anomalies,
           'row_boxes_raw': {str(k): v for k, v in raw_rows.items()},
           'cell_boxes_raw': {'%d,%d' % k: v for k, v in raw_cells.items()}}
    return page, rec


def cmd_layout(args):
    ensure_dirs()
    units = load_units()
    with open(PAGES_JSON) as fh:
        info = json.load(fh)
    todo = [(p, role, u, info[str(p)]) for p, role, u in selected_pages(args.pages, units)
            if str(p) in info]
    if not args.force:
        todo = [t for t in todo if not os.path.exists(page_json_path(t[0]))]
    print('layout: %d pages' % len(todo), flush=True)
    n = 0
    with mp.Pool(args.workers) as pool:
        for page, rec in pool.imap_unordered(job_layout, todo):
            with open(page_json_path(page), 'w') as fh:
                json.dump(rec, fh)
            n += 1
            if n % 100 == 0:
                print('  %d' % n, flush=True)
    print('layout done', n)


# ----------------------------------------------------------------------------- validation

def clean_text(s):
    s = s or ''
    s = re.sub(r'[|_—–~`¬«»\[\]{}<>=*\\^]', ' ', s)
    s = re.sub(r'(?<!\S)[^\w\s(),.\'\-/ªº"ÁÉÍÓÚÑáéíóúñü]+(?!\S)', ' ', s)
    s = re.sub(r'\s+', ' ', s).strip()
    return s


def validate(field, text):
    """Return (ok, normalised_value). Normalised value is the canonical reading of the field."""
    if field in DIGIT_FIELDS:
        # keep only glyphs that can be digits or their usual misreads (e.g. 'o)' for a printed 0)
        t = re.sub(r'[^0-9OoIlS]', '', clean_text(text))
        if t and re.search(r'\d', t):
            t = t.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        if not t:
            return False, ''
        if not re.fullmatch(r'\d+', t):
            return False, t
        n = int(t)
        if field == 'NUMERO':
            return len(t) <= 6, str(n)
        if field == 'EDAD':
            return n <= 110, str(n)
        if field == 'FECHA':
            return 1640 <= n <= 1910 and len(t) == 4, str(n)
        if field == 'OTROS DATOS FAMI':
            return n <= 99, str(n)
        return True, str(n)
    if field == 'ESTADO':
        raw = re.sub(r'[^A-Za-z]', '', clean_text(text))
        t = raw.upper()
        # a printed round C is read as lower-case 'o' (or O/c/G/Q); the printout has only S/C/V
        if t in ('O', 'Q', 'G') or raw in ('o', 'c'):
            t = 'C'
        return t in ('S', 'C', 'V'), t
    if field == 'FIRMA':
        t = re.sub(r'[^A-Za-zÁÉÍÓÚ]', '', strip_accents(clean_text(text))).upper()
        if not t:
            return False, ''
        if t in ('SI', 'NO'):
            return True, t
        m = difflib.get_close_matches(t, ['SI', 'NO'], n=1, cutoff=0.5)
        return (True, m[0]) if m else (False, t)
    if field in VOCAB:
        t = clean_text(text).upper()
        if not t:
            return False, ''
        m = difflib.get_close_matches(strip_accents(t), [strip_accents(v) for v in VOCAB[field]], n=1, cutoff=0.75)
        if not m:
            return False, t
        return True, VOCAB[field][[strip_accents(v) for v in VOCAB[field]].index(m[0])]
    t = clean_text(text)
    alnum = re.findall(r'\w', t)
    return len(alnum) >= 2, t


# ----------------------------------------------------------------------------- OCR

def _erase_lines(tbl, xs):
    for x in xs:
        xi = int(round(x))
        tbl[:, max(0, xi - 2):xi + 3] = 255
    return tbl


def ocr_side(gray, lay):
    """Column-strip pass: one tesseract call per column (psm 6) over the data rows.

    Column strips cannot merge words across grid lines (row strips did, e.g. NUMERO+NAME).
    Words are assigned to a data row by their vertical centre."""
    role = lay['role']
    fields = LEFT_FIELDS if role == 'L' else RIGHT_FIELDS
    cols, rows = lay['col_lines'], lay['row_lines']
    x_lo, x_hi = int(round(cols[0])), int(round(cols[-1]))
    y_lo, y_hi = int(round(rows[1])), int(round(rows[ROWS_PER_SIDE + 1]))
    tbl = gray[y_lo:y_hi, x_lo:x_hi].copy()
    tbl = _erase_lines(tbl, [int(round(c)) - x_lo for c in cols])
    cb = [int(round(c)) - x_lo for c in cols]
    rb = [int(round(y)) - y_lo for y in rows[1:ROWS_PER_SIDE + 2]]  # data row boundaries, 0-based offsets
    for y in rb:  # horizontal grid lines would otherwise be read as text
        tbl[max(0, y - 2):y + 3, :] = 255
    PAD = 10
    cells = {}
    for c, f in enumerate(fields):
        strip = tbl[:, cb[c] + 3:cb[c + 1] - 3]
        padded = cv2.copyMakeBorder(strip, PAD, PAD, PAD, PAD, cv2.BORDER_CONSTANT, value=255)
        words = parse_tsv(run_tess(padded, 6, tsv=True, tag='col'))
        per_row = [[] for _ in range(ROWS_PER_SIDE)]
        for w in words:
            yc = w['t'] + w['h'] / 2.0 - PAD
            r = int(np.searchsorted(rb, yc, side='right')) - 1
            if 0 <= r < ROWS_PER_SIDE:
                per_row[r].append(dict(w, l=w['l'] - PAD))
        for r in range(ROWS_PER_SIDE):
            ws = sorted(per_row[r], key=lambda w: w['l'])
            txt = ' '.join(w['text'] for w in ws)
            confs = [w['conf'] for w in ws]
            conf = float(np.mean(confs)) if confs else None
            y0 = rb[r] + 4
            y1 = rb[r + 1] - 4
            region = tbl[y0:y1, cb[c] + 4:cb[c + 1] - 4]
            ink = float((region < 170).mean()) if region.size else 0.0
            cells[(r + 1, c)] = {'raw': txt, 'conf': None if conf is None else round(conf, 1),
                                 'ink': round(ink, 4), 'merged': False, 'words': len(ws)}
    return fields, cells, tbl, cb, y_lo, x_lo


def reocr_cell(gray, field, rows, cols, r, c):
    """Re-OCR one cell: crop inside the grid, upscale x2, psm 7 (fallback psm 10 for one glyph)."""
    y0 = int(round(rows[r])) + 5
    y1 = int(round(rows[r + 1])) - 5
    x0 = int(round(cols[c])) + 5
    x1 = int(round(cols[c + 1])) - 5
    if y1 <= y0 or x1 <= x0:
        return '', None
    crop = gray[y0:y1, x0:x1]
    crop = cv2.resize(crop, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
    padded = cv2.copyMakeBorder(crop, 24, 24, 24, 24, cv2.BORDER_CONSTANT, value=255)
    wl = WHITELIST.get(field)
    for psm in (7, 10):
        words = parse_tsv(run_tess(padded, psm, whitelist=wl, tsv=True, tag='cell'))
        if words:
            txt = ' '.join(w['text'] for w in sorted(words, key=lambda w: w['l']))
            conf = float(np.mean([w['conf'] for w in words]))
            return txt, round(conf, 1)
    return '', None


def job_ocr(args):
    page, rec = args
    if rec.get('layout') is None:
        rec['ocr'] = None
        return page, rec
    raw = cv2.imread(jpg_path(page), cv2.IMREAD_GRAYSCALE)
    info = {'k': rec['k'], 'angle': rec['angle']}
    gray = upright_gray(raw, info['k'], info['angle'])
    lay = rec['layout']
    fields, cells, tbl, cb, y_lo, x_lo = ocr_side(gray, dict(lay, role=rec['role']))
    nreocr = 0
    out = {}
    for (r, c), cell in sorted(cells.items()):
        f = fields[c]
        s_ok, s_norm = validate(f, cell['raw'])
        conf = cell['conf']
        flags = []
        if cell['merged']:
            flags.append('merged')
        if not cell['raw'].strip() and cell['ink'] >= 0.01:
            flags.append('empty_inked')
        if cell['raw'].strip() and (conf is None or conf < 70):
            flags.append('low_conf')
        if cell['raw'].strip() and not s_ok:
            flags.append('invalid')
        final_text, final_ok, final_conf, method = s_norm if s_ok else cell['raw'], s_ok, conf, 'strip'
        rr_text, rr_conf = None, None
        if flags:
            rr_text, rr_conf = reocr_cell(gray, f, lay['row_lines'], lay['col_lines'], r, c)
            nreocr += 1
            r_ok, r_norm = validate(f, rr_text)
            pick_r = False
            if r_ok and not s_ok:
                pick_r = True
            elif r_ok and s_ok:
                pick_r = (rr_conf or 0) > (conf or 0) + 5
            elif not r_ok and not s_ok and rr_text and not cell['raw'].strip():
                pick_r = True
            if pick_r:
                final_text, final_ok, final_conf, method = (r_norm if r_ok else rr_text), r_ok, rr_conf, 'reocr'
                if not r_ok:
                    flags.append('still_invalid')
        if not cell['raw'].strip() and not (rr_text or '').strip():
            final_text, final_ok, method = '', True, method
        cell_out = {'t': final_text, 'ok': final_ok, 'c': final_conf, 'm': method, 'f': flags,
                    'raw': cell['raw'], 'rc': cell['conf'], 'rr': rr_text, 'rrc': rr_conf,
                    'ink': cell['ink']}
        out['%d,%d' % (r, c)] = cell_out
    rec['ocr'] = {'fields': fields, 'cells': out, 'n_reocr': nreocr}
    return page, rec


def cmd_ocr(args):
    ensure_dirs()
    todo_pages = []
    units = load_units()
    for p, role, u in selected_pages(args.pages, units):
        path = page_json_path(p)
        if not os.path.exists(path):
            continue
        with open(path) as fh:
            rec = json.load(fh)
        if rec.get('ocr') is not None and not args.force:
            continue
        todo_pages.append((p, rec))
    print('ocr: %d pages' % len(todo_pages), flush=True)
    n = 0
    t0 = time.time()
    with mp.Pool(args.workers) as pool:
        for page, rec in pool.imap_unordered(job_ocr, todo_pages):
            with open(page_json_path(page), 'w') as fh:
                json.dump(rec, fh)
            n += 1
            if n % 50 == 0:
                print('  %d done, %.0fs' % (n, time.time() - t0), flush=True)
    print('ocr done', n, round(time.time() - t0))


# ----------------------------------------------------------------------------- debug overlays

def cmd_debug(args):
    ensure_dirs()
    for p in sorted(parse_range(args.pages) or []):
        path = page_json_path(p)
        if not os.path.exists(path):
            print('no json for', p)
            continue
        rec = json.load(open(path))
        if rec.get('layout') is None:
            print('no layout', p, rec.get('anomalies'))
            continue
        raw = cv2.imread(jpg_path(p), cv2.IMREAD_GRAYSCALE)
        gray = upright_gray(raw, rec['k'], rec['angle'])
        col = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        lay = rec['layout']
        for x in lay['col_lines']:
            cv2.line(col, (int(round(x)), 0), (int(round(x)), col.shape[0]), (0, 0, 255), 3)
        for y in lay['row_lines']:
            cv2.line(col, (0, int(round(y))), (col.shape[1], int(round(y))), (255, 0, 0), 2)
        ocr = rec.get('ocr')
        if ocr:
            for key, cell in ocr['cells'].items():
                r, c = map(int, key.split(','))
                x0, x1 = lay['col_lines'][c], lay['col_lines'][c + 1]
                if cell['f']:
                    cv2.rectangle(col, (int(x0) + 3, int(lay['row_lines'][r]) + 3),
                                  (int(x1) - 3, int(lay['row_lines'][r + 1]) - 3), (0, 160, 0), 3)
        small = cv2.resize(col, None, fx=args.scale, fy=args.scale, interpolation=cv2.INTER_AREA)
        cv2.imwrite(os.path.join(DEBUG, 'overlay_p%03d.jpg' % p), small, [cv2.IMWRITE_JPEG_QUALITY, 85])
        print('wrote overlay', p, rec['role'], rec['anomalies'])


# ----------------------------------------------------------------------------- reconcile

def norm_cmp(s):
    if s is None:
        return ''
    t = strip_accents(str(s)).upper()
    t = re.sub(r'[^A-Z0-9]+', ' ', t).strip()
    return re.sub(r'\s+', ' ', t)


def load_xlsx_rows():
    import openpyxl
    wb = openpyxl.load_workbook(XLSX, read_only=True, data_only=True)
    ws = wb['Datos originales']
    rows = list(ws.iter_rows(values_only=True))
    header = list(rows[0])
    out = []
    for r in rows[1:]:
        d = {h: v for h, v in zip(header, r)}
        out.append(d)
    return out


def build_lexicon(xrows):
    lex = {}
    for d in xrows:
        for col in ('Apellidos y nombre', 'Natural de', 'Vecino de', 'Barco', 'Otras características'):
            v = d.get(col)
            if not v:
                continue
            for tok in re.findall(r'[A-Za-zÁÉÍÓÚÑáéíóúñ]{3,}', strip_accents(str(v)).upper()):
                lex[tok] = lex.get(tok, 0) + 1
    return lex


class Lexicon:
    def __init__(self, counts):
        self.words = set(w for w, c in counts.items() if c >= 2)
        self.by_first = {}
        for w in self.words:
            self.by_first.setdefault(w[0], []).append(w)
        self.cache = {}

    def token_score(self, tok):
        if tok in self.words:
            return 1.0
        if tok in self.cache:
            return self.cache[tok]
        best = 0.0
        for w in self.by_first.get(tok[:1], []):
            if abs(len(w) - len(tok)) > 2:
                continue
            r = difflib.SequenceMatcher(None, tok, w).ratio()
            if r > best:
                best = r
        self.cache[tok] = best
        return best

    def score(self, text):
        toks = re.findall(r'[A-Z]{3,}', strip_accents(text or '').upper())
        if not toks:
            return 0.0
        return sum(self.token_score(t) for t in toks) / len(toks)


def fmt_xlsx(field, v):
    if v is None:
        return ''
    if field == 'ESTADO':
        return str(v)
    if field in ('NUMERO', 'EDAD', 'FECHA', 'OTROS DATOS FAMI') and isinstance(v, (int, float)):
        return str(int(v))
    return str(v)


def field_compare_key(field, value):
    if field == 'ESTADO':
        inv = {v: k for k, v in ESTADO_MAP.items()}
        return inv.get(value, norm_cmp(value))
    return norm_cmp(value)


def reconcile_field(field, xv, ov, oconf, lex, doubtful):
    """Return dict(chosen, status, confidence) for one field.

    xv: xlsx value (string, '' if empty); ov: OCR value (normalised, '' if empty)."""
    xv = '' if xv is None else xv
    ov = '' if ov is None else ov
    if field == 'ESTADO':
        xk = field_compare_key(field, xv)
        ok = ov  # letter S/C/V or ''
        ov_disp = ESTADO_MAP.get(ov, ov)
    else:
        xk = norm_cmp(xv)
        ok = norm_cmp(ov)
        ov_disp = ov
    xvalid = bool(xk) and (field != 'ESTADO' or xk in ('S', 'C', 'V'))
    if field in VOCAB:
        xvalid = bool(xk) and any(norm_cmp(xv) == norm_cmp(v) for v in VOCAB[field])
    if field in DIGIT_FIELDS or field == 'FIRMA':
        xvalid = bool(xk) and (validate(field, xv)[0] if xv else False)
    ovalid = bool(ok)
    if xk == ok:
        if not xk:
            return {'chosen': '', 'status': 'agree', 'confidence': 1.0}
        conf = 0.95 if (oconf is None or oconf >= 80) else 0.8
        chosen = fmt_xlsx(field, xv) if xv else ov_disp
        return {'chosen': chosen, 'status': 'agree', 'confidence': conf}
    if ovalid and not xvalid:
        chosen = ov_disp if field == 'ESTADO' else ov
        conf = min(0.9, (oconf or 60) / 100.0)
        return {'chosen': chosen, 'status': 'ocr_fixes', 'confidence': round(conf, 2)}
    if xvalid and not ovalid:
        return {'chosen': fmt_xlsx(field, xv), 'status': 'xlsx_kept', 'confidence': 0.5}
    if not xvalid and not ovalid:
        return {'chosen': fmt_xlsx(field, xv) or ov_disp, 'status': 'conflict', 'confidence': 0.1}
    # both valid, different
    if field in ('APELLIDOS Y NOMBRE', 'NATURAL DE', 'VECINO DE', 'BARCO', 'OTRAS CARACTERISTICAS'):
        sx, so = lex.score(xv), lex.score(ov)
        if so - sx >= 0.15:
            return {'chosen': ov, 'status': 'ocr_fixes', 'confidence': round(min(0.9, 0.5 + (so - sx) / 2), 2)}
        if sx - so >= 0.15:
            return {'chosen': fmt_xlsx(field, xv), 'status': 'xlsx_kept', 'confidence': 0.6}
        return {'chosen': fmt_xlsx(field, xv), 'status': 'conflict', 'confidence': 0.3}
    if doubtful and (oconf or 0) >= 80:
        return {'chosen': ov_disp, 'status': 'ocr_fixes', 'confidence': round((oconf or 0) / 100.0, 2)}
    if doubtful:
        return {'chosen': fmt_xlsx(field, xv), 'status': 'xlsx_kept', 'confidence': 0.4}
    return {'chosen': fmt_xlsx(field, xv), 'status': 'conflict', 'confidence': 0.2}


def xlsx_doubtful(dval, field):
    flags = dval.get('Campos con lectura dudosa') or ''
    name = XLSX_COL[field]
    return name in flags or (field == 'ESTADO' and 'Estado' in flags) or \
        (field == 'EDAD' and 'Edad' in flags)


def load_rows_index(xrows):
    idx = {}
    for d in xrows:
        p = d.get('Página PDF inicial')
        r = d.get('Fila en la página')
        if p is None or r is None:
            continue
        idx[(int(p), int(r))] = d
    return idx


def load_units_pages():
    units = load_units()
    return units


def build_records(pages=None):
    units = load_units()
    if pages is not None:
        units = [u for u in units if u['L'] in pages]
    xrows = load_xlsx_rows()
    xidx = load_rows_index(xrows)
    lex = Lexicon(build_lexicon(xrows))
    recs = []
    for u in units:
        L, R = u['L'], u['R']
        Lrec = json.load(open(page_json_path(L))) if os.path.exists(page_json_path(L)) else None
        Rrec = json.load(open(page_json_path(R))) if R and os.path.exists(page_json_path(R)) else None
        nrows = ROWS_PER_SIDE
        for r in range(1, nrows + 1):
            global_idx = (u['first_row'] or 0) + r - 1
            xd = xidx.get((L, r))
            rec = {'pdf_left': L, 'pdf_right': R or '', 'printed_left': u['printed_L'],
                   'printed_right': u['printed_R'] or '', 'row': r, 'record_index': global_idx,
                   'xlsx_found': xd is not None,
                   'xlsx_numero_original': xd.get('Número original') if xd else None}
            statuses = {}
            for side, src in (('L', Lrec), ('R', Rrec)):
                fields = LEFT_FIELDS if side == 'L' else RIGHT_FIELDS
                if src is None or src.get('ocr') is None:
                    cells = {}
                else:
                    cells = src['ocr']['cells']
                for c, f in enumerate(fields):
                    key = '%d,%d' % (r, c)
                    cell = cells.get(key, {'t': '', 'c': None, 'ok': False, 'f': []})
                    ov_raw = cell.get('t', '')
                    if f == 'ESTADO':
                        ov = ov_raw if ov_raw in ESTADO_MAP else ''
                    elif f == 'EDAD':
                        # xlsx never stores age 0 (its 'Edad' is empty instead), so a printed 0 compares as empty
                        ov = '' if ov_raw in ('', '0') else ov_raw
                    elif f in VOCAB or f == 'FIRMA' or f in DIGIT_FIELDS:
                        ov = ov_raw if cell.get('ok') else ''
                    else:
                        ov = ov_raw
                    if side == 'R' and R is None:
                        ov = ''
                    xv = xd.get(XLSX_COL[f]) if xd else None
                    if xv is not None and not isinstance(xv, str):
                        xv = str(int(xv)) if isinstance(xv, float) and xv.is_integer() else str(xv)
                    if side == 'R' and R is None:
                        xv = xv
                    if f == 'FECHA':
                        xv = None if xv is None else str(xv)
                    doubt = bool(xd) and xlsx_doubtful(xd, f)
                    res = reconcile_field(f, xv if xv else '', ov, cell.get('c'), lex, doubt)
                    key_name = f
                    rec['%s_xlsx' % key_name] = xv or ''
                    rec['%s_ocr' % key_name] = ov_raw
                    rec['%s_chosen' % key_name] = res['chosen']
                    rec['%s_status' % key_name] = res['status']
                    rec['%s_confidence' % key_name] = res['confidence']
                    rec['%s_doubtful' % key_name] = int(doubt)
                    rec['%s_ocr_conf' % key_name] = cell.get('c')
                    statuses[f] = res['status']
            rec['row_bbox_left'] = json.dumps(Lrec['row_boxes_raw'][str(r)]) if Lrec and Lrec.get('row_boxes_raw') else ''
            rec['row_bbox_right'] = json.dumps(Rrec['row_boxes_raw'][str(r)]) if Rrec and Rrec.get('row_boxes_raw') else ''
            rec['_statuses'] = statuses
            recs.append(rec)
    return recs


def record_fieldnames():
    names = ['pdf_left', 'pdf_right', 'printed_left', 'printed_right', 'row', 'record_index',
             'xlsx_found', 'row_bbox_left', 'row_bbox_right']
    for f in FIELDS:
        for s in ('xlsx', 'ocr', 'chosen', 'status', 'confidence', 'doubtful', 'ocr_conf'):
            names.append('%s_%s' % (f, s))
    return names


def cmd_reconcile(args):
    ensure_dirs()
    recs = build_records(parse_range(args.pages))
    names = record_fieldnames()
    out = os.path.join(OCR, 'records.csv')
    with open(out, 'w', newline='') as fh:
        w = csv.DictWriter(fh, fieldnames=names, extrasaction='ignore')
        w.writeheader()
        for r in recs:
            w.writerow(r)
    print('records.csv rows', len(recs))
    with open(os.path.join(OCR, 'reconcile_cache.json'), 'w') as fh:
        json.dump([{k: v for k, v in r.items() if not k.startswith('_')} | {'_statuses': r['_statuses']}
                   for r in recs], fh)
    print('done')


# ----------------------------------------------------------------------------- webp

def job_webp(args):
    page, rec = args
    raw = cv2.imread(jpg_path(page), cv2.IMREAD_GRAYSCALE)
    gray = upright_gray(raw, rec['k'], rec['angle'])
    Hu, Wu = gray.shape
    scale = 1600.0 / Wu
    small = cv2.resize(gray, (1600, int(round(Hu * scale))), interpolation=cv2.INTER_AREA)
    out = os.path.join(WEB_PAGES, 'p-%03d.webp' % page)
    cv2.imwrite(out, small, [cv2.IMWRITE_WEBP_QUALITY, 55])
    rows = {}
    if rec.get('layout') is not None:
        M = deskew_matrix((Hu, Wu), 0.0)
        lay = rec['layout']
        for r in range(1, ROWS_PER_SIDE + 1):
            y0, y1 = lay['row_lines'][r], lay['row_lines'][r + 1]
            x0, x1 = lay['col_lines'][0], lay['col_lines'][-1]
            rows['%d:%d' % (page, r)] = [round(x0 * scale, 1), round(y0 * scale, 1),
                                         round(x1 * scale, 1), round(y1 * scale, 1)]
    return page, os.path.getsize(out), rows


def cmd_webp(args):
    ensure_dirs()
    units = load_units()
    with open(PAGES_JSON) as fh:
        info = json.load(fh)
    todo = []
    for p, role, u in selected_pages(args.pages, units):
        path = page_json_path(p)
        if not os.path.exists(path):
            continue
        rec = json.load(open(path))
        # a few page records lack orientation; take it from pages.json
        for key in ('k', 'angle'):
            if key not in rec and str(p) in info:
                rec[key] = info[str(p)][key]
        todo.append((p, rec))
    rows_all = {}
    sizes = []
    with mp.Pool(args.workers) as pool:
        for page, size, rows in pool.imap_unordered(job_webp, todo):
            sizes.append(size)
            rows_all.update(rows)
    rj = os.path.join(WEB, 'rows.json')
    existing = json.load(open(rj)) if os.path.exists(rj) and args.pages else {}
    existing.update(rows_all)
    with open(rj, 'w') as fh:
        json.dump(existing, fh)
    print('webp: %d pages, median %d KB, max %d KB, over150KB=%d' % (
        len(sizes), np.median(sizes) / 1024, max(sizes) / 1024, sum(s > 150 * 1024 for s in sizes)))


# ----------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name, fn in (('extract', cmd_extract), ('layout', cmd_layout), ('ocr', cmd_ocr),
                     ('reconcile', cmd_reconcile), ('webp', cmd_webp), ('debug', cmd_debug)):
        sp = sub.add_parser(name)
        sp.add_argument('--pages', default=None, help='e.g. 2-21 or 2,4,6')
        sp.add_argument('--workers', type=int, default=6)
        sp.add_argument('--force', action='store_true')
        if name == 'debug':
            sp.add_argument('--scale', type=float, default=0.3)
        sp.set_defaults(func=fn)
    sp = sub.add_parser('report')
    sp.set_defaults(func=lambda a: None)
    args = ap.parse_args()
    if args.cmd == 'report':
        import report as rep
        rep.main()
        return
    args.workers = min(max(1, args.workers), 6)
    args.func(args)


if __name__ == '__main__':
    mp.set_start_method('fork')
    main()
