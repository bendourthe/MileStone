"""Split milestone_mark.png into stone, ribbon, house, tree, and plane layers on the same canvas.

Every visible pixel of the mark goes to exactly one layer, so the layers stacked in order (stone, ribbon, house, tree,
plane) rebuild the original. Clear seeds come from color and position; edge and dark-line pixels join the nearest seed.
The stone layer also gets the stone hidden under the ribbon, house, and plane, blended from nearby stone colors, so it
can be shown alone at the start of the animation.

Run from the repository root (needs Pillow, numpy, scipy), then copy each mark_<name>.png from the output folder to
app/src/main/res/drawable-nodpi/milestone_mark_<name>.png:

    python branding/split_mark.py app/src/main/res/drawable-nodpi/milestone_mark.png build/mark-layers
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
from scipy.spatial import ConvexHull

ORDER = ("stone", "ribbon", "house", "tree", "plane")


def seeds(rgba: np.ndarray) -> dict[str, np.ndarray]:
    r, g, b, a = (rgba[..., i].astype(int) for i in range(4))
    h, w = a.shape
    yy, xx = np.mgrid[0:h, 0:w]
    solid = a > 200
    yellow = solid & (r > 190) & (g > 140) & (b < 110)
    green = solid & (g > 150) & (r > 90) & (r < 200) & (b < 120) & (g > r)
    outline = solid & (g > b + 5) & (b > 120) & (r < 170)
    stone = solid & (b >= g) & (g > 95) & (r > 30)
    navy = solid & (r < 40) & (g < 90)

    plane = ndimage.binary_fill_holes(ndimage.binary_closing(outline & (xx > 60) & (yy < 115), iterations=1))
    trunk = yellow & (xx < 38) & (yy < 82)
    tree = green | trunk
    house_box = (xx >= 10) & (xx < 86) & (yy >= 25) & (yy <= 122)
    house = ((yellow & ~trunk) | navy) & house_box & ~plane
    ribbon = yellow & ~trunk & ~house
    return {"stone": stone & ~plane & ~house_box | stone & ~plane & house_box & ~navy,
            "ribbon": ribbon, "house": house, "tree": tree, "plane": plane}


def assign(rgba: np.ndarray, seed: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    """Seeds keep their pixels in priority order; every other visible pixel joins the nearest seed."""
    visible = rgba[..., 3] > 0
    label = np.full(visible.shape, -1)
    for i, name in reversed(list(enumerate(ORDER))):
        label[seed[name] & (label < 0)] = i
    distances = []
    for i, name in enumerate(ORDER):
        distances.append(ndimage.distance_transform_edt(label != i))
    stack = np.stack(distances)
    nearest = np.argmin(stack, axis=0)
    # An edge pixel next to a front layer belongs to it, so the stone shown alone carries no outline of what covers it.
    front = np.argmin(stack[1:], axis=0) + 1
    near_front = np.min(stack[1:], axis=0) <= 2.5
    rest = visible & (label < 0)
    label[rest] = np.where(near_front, front, nearest)[rest]
    return {name: visible & (label == i) for i, name in enumerate(ORDER)}


def blend_fill(rgba: np.ndarray, source: np.ndarray, target: np.ndarray, sigma: float = 3.0) -> np.ndarray:
    """Colors for [target] by normalized Gaussian blending of [source] pixels, widening until every pixel is reached."""
    rgb = rgba[..., :3].astype(float)
    out = np.zeros(rgb.shape)
    todo = target.copy()
    s = sigma
    while todo.any():
        weight = ndimage.gaussian_filter(source.astype(float), s)
        acc = np.stack([ndimage.gaussian_filter(rgb[..., c] * source, s) for c in range(3)], -1)
        ready = todo & (weight > 1e-3)
        out[ready] = acc[ready] / weight[ready, None]
        todo &= ~ready
        s *= 1.6
    return out


def stone_layers(rgba: np.ndarray, layers: dict[str, np.ndarray], pure: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """The stone with what the ribbon, house, and plane hide, and the thin see-through gaps beside them.

    The gaps are see-through in the finished mark (a dark line between the ribbon and the stone), so they get their own
    layer that fades out as the front layers arrive.
    """
    stone = layers["stone"]
    covered = layers["ribbon"] | layers["house"] | layers["plane"]
    # The stone's convex outline: the plane's nose and wing tips stick out past it and stay empty.
    ys, xs = np.nonzero(stone)
    hull = ConvexHull(np.column_stack([xs, ys]))
    mask = Image.new("L", (stone.shape[1], stone.shape[0]), 0)
    ImageDraw.Draw(mask).polygon([(float(xs[i]), float(ys[i])) for i in hull.vertices], fill=1)
    inside = np.asarray(mask) > 0
    hidden = inside & covered
    clear = rgba[..., 3] == 0
    gaps = inside & clear & (ndimage.distance_transform_edt(~covered) <= 4)
    fill = blend_fill(rgba, pure & stone, hidden | gaps)
    base = np.zeros_like(rgba)
    base[stone] = rgba[stone]
    base[hidden, :3] = np.clip(np.rint(fill[hidden]), 0, 255).astype(np.uint8)
    base[hidden, 3] = 255
    gap = np.zeros_like(rgba)
    gap[gaps, :3] = np.clip(np.rint(fill[gaps]), 0, 255).astype(np.uint8)
    gap[gaps, 3] = 255
    return base, gap


def main(src: str, out_dir: str) -> None:
    rgba = np.asarray(Image.open(src).convert("RGBA"))
    seed = seeds(rgba)
    layers = assign(rgba, seed)
    total = sum(v.astype(int) for v in layers.values())
    assert total.max() <= 1, "a pixel landed in two layers"
    assert (total > 0).sum() == (rgba[..., 3] > 0).sum(), "a visible pixel landed in no layer"
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    base, gap = stone_layers(rgba, layers, seed["stone"])
    Image.fromarray(base).save(out / "mark_stone.png", optimize=True)
    Image.fromarray(gap).save(out / "mark_stone_gaps.png", optimize=True)
    for name in ORDER[1:]:
        part = np.zeros_like(rgba)
        part[layers[name]] = rgba[layers[name]]
        Image.fromarray(part).save(out / f"mark_{name}.png", optimize=True)
    # The stack must rebuild the original wherever the original has a pixel.
    stack = Image.new("RGBA", (rgba.shape[1], rgba.shape[0]))
    for name in ORDER:
        stack.alpha_composite(Image.open(out / f"mark_{name}.png"))
    diff = np.abs(np.asarray(stack).astype(int) - rgba.astype(int))[rgba[..., 3] == 255].max()
    print({k: int(v.sum()) for k, v in layers.items()}, "max difference on opaque pixels:", int(diff))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
