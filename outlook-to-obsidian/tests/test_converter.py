from __future__ import annotations

from src.converter import (
    html_to_markdown,
    message_body_markdown,
    plain_to_markdown,
)


def test_basic_html() -> None:
    assert "Hello" in html_to_markdown("<p>Hello</p>")


def test_blockquote_becomes_markdown_quote() -> None:
    out = html_to_markdown("<blockquote>quoted text</blockquote>")
    assert "> quoted text" in out


def test_inline_image_cid_placeholder() -> None:
    out = html_to_markdown('<p><img src="cid:logo.png"></p>')
    assert "[Inline image: logo.png]" in out


def test_inline_image_url_uses_basename() -> None:
    out = html_to_markdown('<img src="https://host/path/pic.jpg?x=1" alt="Alt">')
    assert "[Inline image: pic.jpg]" in out


def test_zero_width_stripped() -> None:
    assert html_to_markdown("<p>a​b</p>").strip() == "ab"


def test_blank_lines_compressed() -> None:
    assert plain_to_markdown("a\n\n\n\n\nb").strip() == "a\n\nb"


def test_signature_folding() -> None:
    out = plain_to_markdown("Body text\n\n-- \nYoshi\nUnited Petroleum")
    assert "<details>" in out
    assert "署名" in out
    assert "Yoshi" in out


def test_no_signature_when_disabled() -> None:
    out = plain_to_markdown("Body\n\n-- \nSig", fold_signatures=False)
    assert "<details>" not in out


def test_message_body_prefers_html() -> None:
    out = message_body_markdown("<p>HTML body</p>", "PLAIN body")
    assert "HTML body" in out
    assert "PLAIN" not in out


def test_message_body_falls_back_to_plain() -> None:
    out = message_body_markdown(None, "Plain only")
    assert "Plain only" in out
