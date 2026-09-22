"""HTML/plain-text email body → Markdown conversion.

Strategy (mirrors the spec):

1. Prefer the HTML body, converted with ``markdownify``.
2. Fall back to the plain-text body when HTML is missing or conversion fails.
3. Keep blockquotes as Markdown ``>`` quotes (markdownify default).
4. Replace inline images with ``[Inline image: <filename>]`` placeholders.
5. Optionally fold the signature (after a ``-- `` delimiter) into a ``<details>``.
"""

from __future__ import annotations

import logging
import re

from markdownify import MarkdownConverter

logger = logging.getLogger(__name__)

_ZERO_WIDTH = re.compile(r"[​‌‍﻿­]")

_WORD_NOISE = [
    re.compile(r"<!--.*?-->", re.DOTALL),
    re.compile(r"<style\b.*?</style>", re.DOTALL | re.IGNORECASE),
    re.compile(r"<script\b.*?</script>", re.DOTALL | re.IGNORECASE),
    re.compile(r"<xml\b.*?</xml>", re.DOTALL | re.IGNORECASE),
    re.compile(r"</?o:p[^>]*>", re.IGNORECASE),
]

# A line that is just the standard signature delimiter ("-- ").
_SIG_DELIM = re.compile(r"(?m)^--[ \t]*$")

_TAG_STRIP = re.compile(r"<[^>]+>")
_BLANK_LINES = re.compile(r"\n{3,}")


class _OutlookConverter(MarkdownConverter):
    """markdownify converter that swaps inline images for placeholders."""

    def convert_img(self, el, text, *args, **kwargs) -> str:  # noqa: ANN001, D102
        return f"[Inline image: {_image_name(el)}]"


def _image_name(el) -> str:  # noqa: ANN001
    src = (el.get("src") or "").strip()
    if src.startswith("cid:"):
        name = src[4:]
    else:
        name = src.rsplit("/", 1)[-1].split("?")[0]
    name = name or (el.get("alt") or "").strip() or (el.get("title") or "").strip()
    return name or "image"


def _strip_zero_width(text: str) -> str:
    return _ZERO_WIDTH.sub("", text)


def _strip_word_noise(html: str) -> str:
    for pattern in _WORD_NOISE:
        html = pattern.sub("", html)
    return html


def _compress_blank_lines(text: str) -> str:
    """Collapse runs of 3+ newlines down to a single blank line."""
    return _BLANK_LINES.sub("\n\n", text)


def _fold_signature(markdown: str) -> str:
    match = _SIG_DELIM.search(markdown)
    if not match:
        return markdown
    head = markdown[: match.start()].rstrip()
    signature = markdown[match.end() :].strip()
    if not signature:
        return markdown
    return (
        f"{head}\n\n"
        "<details>\n<summary>署名</summary>\n\n"
        f"{signature}\n\n"
        "</details>"
    )


def html_to_markdown(html: str, *, fold_signatures: bool = True) -> str:
    """Convert an HTML email body to Markdown.

    Falls back to a plain-text strip if markdownify raises.
    """
    if not html or not html.strip():
        return ""
    cleaned = _strip_word_noise(_strip_zero_width(html))
    try:
        markdown = _OutlookConverter(heading_style="ATX").convert(cleaned)
    except Exception:  # pragma: no cover - defensive fallback
        logger.warning("markdownify failed; falling back to plain text", exc_info=True)
        text = _TAG_STRIP.sub("", cleaned)
        return plain_to_markdown(text, fold_signatures=fold_signatures)
    markdown = _compress_blank_lines(markdown.strip())
    if fold_signatures:
        markdown = _fold_signature(markdown)
    return markdown.strip() + "\n"


def plain_to_markdown(text: str, *, fold_signatures: bool = True) -> str:
    """Normalise a plain-text body for Markdown output."""
    if not text or not text.strip():
        return ""
    normalised = _compress_blank_lines(_strip_zero_width(text).strip())
    if fold_signatures:
        normalised = _fold_signature(normalised)
    return normalised.strip() + "\n"


def message_body_markdown(
    html_body: str | None,
    plain_body: str | None,
    *,
    fold_signatures: bool = True,
) -> str:
    """Pick the best available body and return it as Markdown.

    HTML wins when present and non-empty; otherwise the plain body is used.
    """
    if html_body and html_body.strip():
        result = html_to_markdown(html_body, fold_signatures=fold_signatures)
        if result.strip():
            return result
    return plain_to_markdown(plain_body or "", fold_signatures=fold_signatures)
