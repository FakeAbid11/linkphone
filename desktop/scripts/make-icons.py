"""Generate LinkPhone icon assets from resources/logo-source.png.

Run from anywhere with Pillow installed:
    python desktop/scripts/make-icons.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

DESKTOP = Path(__file__).resolve().parents[1]
ROOT = DESKTOP.parent
SRC = DESKTOP / "resources" / "logo-source.png"
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"
ANDROID_DRAWABLE = ANDROID_RES / "drawable-nodpi"
BLACK = (0, 0, 0)
NOTIFICATION_SIZES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}


def glyph_alpha(image: Image.Image) -> tuple[Image.Image, Image.Image, tuple[int, int, int, int]]:
    gray = image.convert("L")
    bbox = gray.point(lambda value: 255 if value > 8 else 0).getbbox()
    if bbox is None:
        raise SystemExit("logo-source.png has no visible glyph")
    glyph = image.convert("RGB").crop(bbox)
    transparent = Image.new("RGBA", glyph.size, (255, 255, 255, 0))
    transparent.putalpha(gray.crop(bbox))
    return glyph, transparent, bbox


def write_desktop(master: Image.Image) -> None:
    (DESKTOP / "build").mkdir(parents=True, exist_ok=True)
    master.save(DESKTOP / "build" / "icon.png")

    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    frames: dict[int, Image.Image] = {}
    for size in sizes:
        frame = master.resize((size, size), Image.LANCZOS)
        if size <= 32:
            frame = frame.filter(ImageFilter.UnsharpMask(radius=0.6, percent=60))
        frames[size] = frame
    frames[256].save(
        DESKTOP / "build" / "icon.ico",
        format="ICO",
        sizes=[(size, size) for size in sizes],
        append_images=[frames[size] for size in sizes if size != 256],
    )
    master.resize((512, 512), Image.LANCZOS).save(DESKTOP / "resources" / "icon.png")


def write_tray(master: Image.Image) -> None:
    # Match the app icon: the full trio on a black rounded plate.
    logo = master.resize((32, 32), Image.LANCZOS).filter(
        ImageFilter.UnsharpMask(radius=0.6, percent=60)
    )
    mask = Image.new("L", (32, 32), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 31, 31), radius=6, fill=255)
    tray = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    tray.paste(logo, (0, 0), mask)
    tray.save(DESKTOP / "resources" / "tray.png")


def write_renderer(transparent: Image.Image) -> None:
    padding = round(transparent.width * 0.06)
    canvas = Image.new(
        "RGBA",
        (transparent.width + padding * 2, transparent.height + padding * 2),
        (0, 0, 0, 0),
    )
    canvas.paste(transparent, (padding, padding), transparent)
    canvas = canvas.resize((512, round(canvas.height * 512 / canvas.width)), Image.LANCZOS)
    canvas.save(DESKTOP / "src" / "renderer" / "src" / "assets" / "logo.png")


def write_android(transparent: Image.Image) -> None:
    ANDROID_DRAWABLE.mkdir(parents=True, exist_ok=True)

    # 58% width: the 66 dp adaptive safe circle clips nothing at this size.
    size = 432
    target = round(size * 0.58)
    scale = target / transparent.width
    glyph = transparent.resize(
        (target, round(transparent.height * scale)), Image.LANCZOS
    )
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(glyph, ((size - glyph.width) // 2, (size - glyph.height) // 2), glyph)
    canvas.save(ANDROID_DRAWABLE / "ic_launcher_logo.png")

    padding = round(transparent.width * 0.04)
    mark = Image.new(
        "RGBA",
        (transparent.width + padding * 2, transparent.height + padding * 2),
        (0, 0, 0, 0),
    )
    mark.paste(transparent, (padding, padding), transparent)
    mark = mark.resize((176, round(mark.height * 176 / mark.width)), Image.LANCZOS)
    mark.save(ANDROID_DRAWABLE / "logo_mark.png")


def write_notification_icon() -> None:
    # Notification small icons are alpha masks: the full trio turns to mush at
    # 24 dp, so keep the phone and chain link and drop the laptop.
    units = 24
    scale = 40
    size = units * scale

    def u(value: float) -> int:
        return round(value * scale)

    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)

    draw.rounded_rectangle(
        [u(3.2), u(3.0), u(9.4), u(21.0)],
        radius=u(1.8),
        outline=255,
        width=u(1.9),
    )
    draw.rounded_rectangle(
        [u(5.25), u(5.8), u(7.35), u(6.5)],
        radius=u(0.35),
        fill=255,
    )
    for left in (10.2, 14.8):
        draw.rounded_rectangle(
            [u(left), u(9.7), u(left + 6.0), u(14.3)],
            radius=u(2.1),
            outline=255,
            width=u(1.5),
        )

    for density, px in NOTIFICATION_SIZES.items():
        alpha = mask.resize((px, px), Image.LANCZOS)
        icon = Image.new("RGBA", (px, px), (255, 255, 255, 0))
        icon.putalpha(alpha)
        folder = ANDROID_RES / f"drawable-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        icon.save(folder / "ic_stat_logo.png")

    preview = mask.resize((24, 24), Image.LANCZOS)
    visible = preview.point(lambda value: 255 if value > 8 else 0)
    alpha_sum = sum(index * count for index, count in enumerate(preview.histogram()))
    print(
        "notification icon",
        "bbox",
        visible.getbbox(),
        "coverage",
        round(alpha_sum / (255 * 24 * 24), 3),
    )


def main() -> None:
    source = Image.open(SRC).convert("RGB")
    glyph, transparent, bbox = glyph_alpha(source)
    print("source", source.size, "glyph", bbox, "glyph size", glyph.size)

    master = Image.new("RGB", (1024, 1024), BLACK)
    target_width = round(1024 * 0.86)
    scale = target_width / glyph.width
    resized = glyph.resize((target_width, round(glyph.height * scale)), Image.LANCZOS)
    master.paste(resized, ((1024 - resized.width) // 2, (1024 - resized.height) // 2))

    write_desktop(master)
    write_tray(master)
    write_renderer(transparent)
    write_android(transparent)
    write_notification_icon()
    print("wrote desktop/build/icon.png, icon.ico, resources/icon.png, resources/tray.png")
    print("wrote renderer logo, Android adaptive/header marks, and notification icon")


if __name__ == "__main__":
    main()
