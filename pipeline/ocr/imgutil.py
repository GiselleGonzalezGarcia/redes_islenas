"""Image helpers: rotation, deskew and coordinate mapping (upright <-> raw JPEG)."""
import cv2
import numpy as np

# k = number of clockwise 90-degree turns applied to the raw JPEG to get the upright page
ROT = {0: None, 1: cv2.ROTATE_90_CLOCKWISE, 2: cv2.ROTATE_180, 3: cv2.ROTATE_90_COUNTERCLOCKWISE}


def rotate_raw(raw, k):
    if k == 0:
        return raw
    return cv2.rotate(raw, ROT[k])


def raw_pt_to_upright(x, y, k, H0, W0):
    """Map a point of the raw image (size H0 x W0) to the rotated (upright, pre-deskew) image."""
    if k == 0:
        return x, y
    if k == 1:  # clockwise: raw (r,c) -> (x=H0-1-r, y=c)
        return H0 - 1 - y, x
    if k == 2:
        return W0 - 1 - x, H0 - 1 - y
    if k == 3:  # counter-clockwise: raw (r,c) -> (x=r, y=W0-1-c)
        return y, W0 - 1 - x
    raise ValueError(k)


def upright_pt_to_raw(x, y, k, H0, W0):
    if k == 0:
        return x, y
    if k == 1:
        return y, H0 - 1 - x
    if k == 2:
        return W0 - 1 - x, H0 - 1 - y
    if k == 3:
        return W0 - 1 - y, x
    raise ValueError(k)


def deskew_matrix(shape, angle):
    h, w = shape[:2]
    return cv2.getRotationMatrix2D(((w - 1) / 2.0, (h - 1) / 2.0), angle, 1.0)


def apply_affine_pt(M, x, y):
    return M[0, 0] * x + M[0, 1] * y + M[0, 2], M[1, 0] * x + M[1, 1] * y + M[1, 2]


def deskew_pt_to_upright(M, x, y):
    Mi = cv2.invertAffineTransform(M)
    return apply_affine_pt(Mi, x, y)


def upright_gray(raw_gray, k, angle):
    """Upright, deskewed grayscale page (full resolution)."""
    u = rotate_raw(raw_gray, k)
    if abs(angle) < 1e-6:
        return u
    M = deskew_matrix(u.shape, angle)
    return cv2.warpAffine(u, M, (u.shape[1], u.shape[0]), flags=cv2.INTER_LINEAR,
                          borderMode=cv2.BORDER_CONSTANT, borderValue=255)


def estimate_skew(gray_upright, dark=None):
    """Angle (degrees) that makes the table's horizontal ink lines horizontal.

    Uses a thresholded ink mask of the table area (long grid lines dominate the row
    projection) and maximises the variance of the row projection over angles."""
    if dark is None:
        d = gray_upright.astype(np.float32)
        bg = cv2.GaussianBlur(d, (0, 0), 30)
        dark = np.clip(bg - d, 0, 255)
    small = dark[200:2480:2, 120:3400:2]
    m = (small > 4).astype(np.float32)
    h, w = m.shape

    def score(a):
        M = cv2.getRotationMatrix2D(((w - 1) / 2.0, (h - 1) / 2.0), a, 1.0)
        r = cv2.warpAffine(m, M, (w, h), flags=cv2.INTER_LINEAR)
        return r.sum(axis=1, dtype=np.float64).var()

    best = max(np.arange(-3.0, 3.001, 0.05), key=score)
    best = max(np.arange(best - 0.05, best + 0.0501, 0.005), key=score)
    return float(round(best, 3))
