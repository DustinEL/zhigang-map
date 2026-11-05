from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".codex-temp" / "resume_72_101_rendered_v4"
OUTPUT = ROOT / ".codex-temp" / "resume_72_101_contact_sheets_v4"


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    images = sorted(SOURCE.glob("*/*.png"), key=lambda path: (int(path.parent.name.split("_", 1)[0]), path.name))
    thumb_width, thumb_height = 255, 350
    columns, rows = 5, 4
    for sheet_number, offset in enumerate(range(0, len(images), columns * rows), start=1):
        group = images[offset : offset + columns * rows]
        canvas = Image.new("RGB", (columns * thumb_width, rows * thumb_height), "#d9dde3")
        draw = ImageDraw.Draw(canvas)
        for index, path in enumerate(group):
            image = Image.open(path).convert("RGB")
            image.thumbnail((thumb_width - 8, thumb_height - 24))
            x = (index % columns) * thumb_width + (thumb_width - image.width) // 2
            y = (index // columns) * thumb_height + 20
            canvas.paste(image, (x, y))
            draw.text((index % columns * thumb_width + 5, index // columns * thumb_height + 3), f"{path.parent.name}/{path.stem}", fill="#111111")
        canvas.save(OUTPUT / f"contact-sheet-{sheet_number:02d}.jpg", quality=90)


if __name__ == "__main__":
    main()
