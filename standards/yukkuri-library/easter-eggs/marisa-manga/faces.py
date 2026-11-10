"""Draw the face textures of the manga Marisa (transparent PNG, planar face map).

  <py> faces.py            -> textures/<variant>_<expression>.png
The face map spans face.frame [x0, x1, y0, y1] in model units (u along x, v up).
M01 face (measured in eye widths E): eyes ~0.18 body width, one E apart at the inner corners,
set high under the bangs; each a solid black oval with a big white window in the upper half and
small sparkles low. The mouth hangs between the eyes from ~0.25 E below the eye centre; hatched
blush under the outer corners; the lower body stays blank.
"""
import json, math, os
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'textures')
os.makedirs(OUT, exist_ok=True)
SIZE, SS = 1024, 3
S = SIZE * SS
INK = (30, 20, 14, 255)
WHITE = (255, 255, 255, 255)


class Face:
    def __init__(self, spec):
        self.f = spec
        self.x0, self.x1, self.y0, self.y1 = spec['frame']

    def P(self, x, y):
        return ((x - self.x0) / (self.x1 - self.x0) * S, (1 - (y - self.y0) / (self.y1 - self.y0)) * S)

    def U(self, v):
        return max(1, int(v / (self.x1 - self.x0) * S))

    def ell(self, d, cx, cy, w, h, **kw):
        a, b = self.P(cx - w / 2, cy + h / 2), self.P(cx + w / 2, cy - h / 2)
        d.ellipse([a[0], a[1], b[0], b[1]], **kw)

    def stroke(self, d, pts, w0, w1=None, fill=INK):
        """Tapered brush stroke through model-space points (width w0 -> w1)."""
        w1 = w0 if w1 is None else w1
        n = len(pts)
        left, right = [], []
        for i, (x, y) in enumerate(pts):
            ax, ay = pts[max(0, i - 1)]
            bx, by = pts[min(n - 1, i + 1)]
            tx, ty = bx - ax, by - ay
            L = math.hypot(tx, ty) or 1
            w = (w0 + (w1 - w0) * i / max(1, n - 1)) / 2
            left.append((x - ty / L * w, y + tx / L * w))
            right.append((x + ty / L * w, y - tx / L * w))
        d.polygon([self.P(*p) for p in left + right[::-1]], fill=fill)
        for (x, y), w in ((pts[0], w0), (pts[-1], w1)):
            self.ell(d, x, y, w, w, fill=fill)

    def band(self, d, cx, cy, rx, ry, a0, a1, thick, n=60):
        """Filled band outside the ellipse arc a0 -> a1 (degrees); thick(t) is its width along the arc."""
        inner, outer = [], []
        for i in range(n + 1):
            t = i / n
            a = math.radians(a0 + (a1 - a0) * t)
            c, s = math.cos(a), math.sin(a)
            inner.append((cx + rx * c, cy + ry * s))
            k = thick(t)
            outer.append((cx + (rx + k) * c, cy + (ry + k) * s))
        d.polygon([self.P(*p) for p in inner + outer[::-1]], fill=INK)

    def arc(self, cx, cy, rx, ry, a0, a1, n=28):
        return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
                for i in range(n + 1)]

    # ------------------------------------------------------------ eyes
    def eye_open(self, img, side):
        """M01 eye: full round black shape, a big white window in the upper half, small sparkles low."""
        f = self.f
        cx, cy, w, h = side * f['eye_x'], f['eye_y'], f['eye_w'], f['eye_h']
        d = ImageDraw.Draw(img)
        outline = [self.P(*p) for p in self.eye_shape(cx, cy, w, h)]
        d.polygon(outline, fill=INK)
        # faint warm glow low inside the black (the manga's grey, in Marisa's amber)
        mask = Image.new('L', img.size, 0)
        ImageDraw.Draw(mask).polygon([self.P(*p) for p in self.eye_shape(cx, cy, w * 0.94, h * 0.94)], fill=255)
        glow = Image.new('RGBA', img.size, (0, 0, 0, 0))
        self.ell(ImageDraw.Draw(glow), cx, cy - h * 0.22, w * 0.62, h * 0.4, fill=(132, 84, 34, 95))
        glow = glow.filter(ImageFilter.GaussianBlur(self.U(0.12)))
        glow.putalpha(ImageChops.multiply(glow.split()[3], mask))
        img.alpha_composite(glow)
        d = ImageDraw.Draw(img)
        # big highlight window: rounded on top, its lower edge flatter with a small upward dip in the middle
        hx, hy, rx, ry = cx, cy + h * 0.12, w * 0.33, h * 0.27
        pts = []
        for i in range(72):
            a = 2 * math.pi * i / 72
            c, s = math.cos(a), math.sin(a)
            y = ry * s if s > 0 else ry * 0.62 * s + h * 0.07 * math.exp(-(c / 0.3) ** 2)
            pts.append((hx + rx * c, hy + y))
        d.polygon([self.P(*p) for p in pts], fill=WHITE)
        # sparkles on the lower rim
        self.ell(d, cx - w * 0.12, cy - h * 0.3, w * 0.18, h * 0.085, fill=WHITE)
        self.ell(d, cx + w * 0.09, cy - h * 0.31, w * 0.075, h * 0.07, fill=WHITE)

    @staticmethod
    def eye_shape(cx, cy, w, h, n=72):
        """Round eye outline: an ellipse whose lower half is a touch wider, so the bottom stays round
        on the curved, downward-turning face instead of pinching in."""
        pts = []
        for i in range(n):
            a = 2 * math.pi * i / n
            c, s = math.cos(a), math.sin(a)
            x = w / 2 * c * (1 + 0.03 * max(0.0, -s))
            y = h / 2 * s
            pts.append((cx + x, cy + y))
        return pts

    def eye_closed(self, img, side, kind):
        f = self.f
        cx, cy, w, h = side * f['eye_x'], f['eye_y'], f['eye_w'], f['eye_h']
        d = ImageDraw.Draw(img)
        if kind == 'happy':      # "^" arcs
            pts = [(cx + (t - 0.5) * w * 1.0, cy - h * 0.15 + math.sin(math.pi * t) * h * 0.36) for t in [i / 24 for i in range(25)]]
            self.stroke(d, pts[:13], 0.08, 0.24)
            self.stroke(d, pts[12:], 0.24, 0.08)
        elif kind == 'content':  # relaxed downward curves
            pts = [(cx + (t - 0.5) * w * 1.0, cy - h * 0.05 - math.sin(math.pi * t) * h * 0.22) for t in [i / 24 for i in range(25)]]
            self.stroke(d, pts[:13], 0.08, 0.23)
            self.stroke(d, pts[12:], 0.23, 0.09)
        else:                    # blink: low flat lid
            pts = [(cx + (t - 0.5) * w * 0.95, cy - h * 0.18 - 0.06 * math.sin(math.pi * t)) for t in [i / 16 for i in range(17)]]
            self.stroke(d, pts, 0.2, 0.2)

    # ------------------------------------------------------------ mouths
    def fill_mouth(self, img, pts, tongue_box, shine=None):
        d = ImageDraw.Draw(img)
        poly = [self.P(*p) for p in pts]
        d.polygon(poly, fill=(140, 40, 48, 255))
        m = Image.new('L', img.size, 0)
        ImageDraw.Draw(m).polygon(poly, fill=255)
        t = Image.new('L', img.size, 0)
        self.ell(ImageDraw.Draw(t), *tongue_box, fill=255)
        img.paste(Image.new('RGBA', img.size, (232, 128, 138, 255)), (0, 0), ImageChops.multiply(t, m))
        d = ImageDraw.Draw(img)
        if shine:
            self.ell(d, *shine, fill=(255, 255, 255, 235))
        self.stroke(d, pts + [pts[0]], 0.075, 0.075)

    def oval(self, top, ww, hh, n=48):
        """Points of an oval mouth hanging from y = top; the lower half a little fuller."""
        out = []
        for i in range(n):
            a = 2 * math.pi * i / n
            s, c = math.sin(a), math.cos(a)
            out.append((ww / 2 * s * (1 if c > 0 else 1.04), top - hh / 2 + hh / 2 * c))
        return out

    @staticmethod
    def cat_mouth(top, ww, dip, dep, fullness=1.0, n=24):
        """Open mouth whose top edge is a cat "ω" (two arcs meeting at a centre point) over a round opening."""
        pts = []
        for i in range(n + 1):           # ω top, left to right
            t = i / n
            u = (2 * t) % 1.0 if t < 1 else 1.0
            pts.append((-ww / 2 + ww * t, top + 0.03 * (2 * abs(t - 0.5)) ** 3 - dip * math.sin(math.pi * u)))
        for i in range(1, n):            # round bottom, right to left
            a = math.pi * i / n
            pts.append((ww / 2 * math.cos(a), top - dep * math.sin(a) ** fullness))
        return pts

    def mouth(self, img, kind):
        f = self.f
        top, E = f['mouth_y'], f['eye_w']
        d = ImageDraw.Draw(img)
        if kind == 'neutral':    # small cat mouth: "ω" top over a round opening
            ww, dep = E * 0.5, E * 0.42
            self.fill_mouth(img, self.cat_mouth(top, ww, E * 0.07, dep, 0.85), (0, top - dep * 0.88, ww * 0.56, dep * 0.5))
        elif kind == 'yu':       # tall open cat mouth ("yuwaa")
            ww, hh = E * 0.66, E * 0.88
            self.fill_mouth(img, self.cat_mouth(top, ww, E * 0.07, hh, 0.7), (0, top - hh * 0.78, ww * 0.8, hh * 0.5),
                            shine=(ww * 0.1, top - hh * 0.66, ww * 0.12, ww * 0.12))
        elif kind == 'happy':    # wide open cat mouth
            ww, hh = E * 0.74, E * 0.66
            self.fill_mouth(img, self.cat_mouth(top, ww, E * 0.08, hh, 0.75), (0, top - hh * 0.8, ww * 0.76, hh * 0.5),
                            shine=(ww * 0.1, top - hh * 0.66, ww * 0.11, ww * 0.11))
        elif kind == 'chew_open':
            ww, hh = E * 0.34, E * 0.3
            self.fill_mouth(img, self.oval(top - 0.1, ww, hh, 36), (0, top - 0.1 - hh * 0.8, ww * 0.6, hh * 0.4))
        else:                    # closed small "w"
            ww = E * 0.44
            pts = [(-ww / 2 + ww * t, top - 0.18 - 0.13 * abs(math.sin(2 * math.pi * t))) for t in [i / 24 for i in range(25)]]
            self.stroke(d, pts, 0.075, 0.075)

    def blush(self, img):
        """Hatched blush under the outer eye corners: a soft pink patch crossed by fine diagonal lines."""
        f = self.f
        bw = f['blush_w']
        layer = Image.new('RGBA', img.size, (255, 145, 160, 0))
        d = ImageDraw.Draw(layer)
        for side in (-1, 1):
            self.ell(d, side * f['blush_x'], f['blush_y'], bw, bw * 0.42, fill=(255, 150, 160, 90))
        img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(self.U(0.2))))
        d = ImageDraw.Draw(img)
        for side in (-1, 1):
            n = 8
            for k in range(n):
                u = (k - (n - 1) / 2) / (n - 1)
                x = side * f['blush_x'] + u * bw * 0.9
                L = 0.17 * (1 - 0.55 * (2 * u) ** 2)
                self.stroke(d, [(x - L, f['blush_y'] - L * 0.75), (x + L, f['blush_y'] + L * 0.75)], 0.035, 0.035, fill=(200, 92, 108, 210))


RECIPES = {  # expression -> (eye, mouth)
    'neutral': ('open', 'neutral'), 'blink': ('blink', 'closed'), 'yu': ('open', 'yu'),
    'chew_open': ('content', 'chew_open'), 'chew_closed': ('content', 'closed'),
    'happy': ('happy', 'happy'), 'content': ('content', 'closed'), 'push': ('happy', 'yu'),
}

if __name__ == '__main__':
    for name, v in json.load(open(os.path.join(HERE, 'variants.json'), encoding='utf-8')).items():
        if name.startswith('_'):
            continue
        face = Face(v['face'])
        for expr, (eye, mouth) in RECIPES.items():
            img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
            face.blush(img)
            for side in (-1, 1):
                if eye == 'open':
                    face.eye_open(img, side)
                else:
                    face.eye_closed(img, side, eye)
            face.mouth(img, mouth)
            img.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(OUT, '%s_%s.png' % (name, expr)))
        print('faces', name, len(RECIPES))
