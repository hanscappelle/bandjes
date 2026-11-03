"""Helpers for normalizing motorcycle tyre sizes."""

import re


SIZE_PATTERN = re.compile(
    r"(?P<width>\d{2,3})\s*/\s*(?P<aspect>\d{2,3})"
    r"\s*(?:-|/|\s)?\s*(?:ZR|VB|R|B)?\s*(?P<rim>\d{2})",
    re.IGNORECASE,
)


def normalize_size(value):
    match = SIZE_PATTERN.fullmatch(value.strip())
    if not match:
        raise ValueError(f"Invalid tyre size: {value!r} (expected e.g. 90/90R21)")
    return "{width}/{aspect}R{rim}".format(**match.groupdict())
