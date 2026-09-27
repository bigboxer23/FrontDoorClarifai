"""Timestamp overlay for captured stills."""

from __future__ import annotations

from datetime import datetime

from PIL import Image, ImageDraw, ImageFont


def stamp(image: Image.Image, when: datetime, time_format: str) -> None:
    """Draw when in the bottom right corner as white text with a black outline, sized to the image."""
    size = max(10, image.height // 60)
    try:
        font = ImageFont.load_default(size=size)
    except TypeError:  # Pillow < 10.1 only has the small fixed bitmap font
        font = ImageFont.load_default()
    text = when.strftime(time_format)
    stroke = max(1, size // 12)
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font, stroke_width=stroke)
    margin = size // 2
    position = (image.width - right - margin, image.height - bottom - margin)
    draw.text(position, text, font=font, fill="white", stroke_width=stroke, stroke_fill="black")
