"""Redact secrets from log/diagnostic strings."""

from __future__ import annotations

import re

_PATTERNS = [
    (re.compile(r'(?i)("?(?:password|api[_-]?secret|token|nightscout[_-]?secret)"?\s*[:=]\s*")([^"]*)(")'),
     r"\1***\3"),
    (re.compile(r"(?i)(password=)([^&\s]+)"), r"\1***"),
    (re.compile(r"(?i)(token=)([^&\s]+)"), r"\1***"),
    (re.compile(r'(?i)("glookoPassword"\s*:\s*")([^"]*)(")'), r"\1***\3"),
    (re.compile(r'(?i)("nightscoutSecret"\s*:\s*")([^"]*)(")'), r"\1***\3"),
]


def redact(text: str | None) -> str:
    if not text:
        return ""
    out = text
    for pattern, repl in _PATTERNS:
        out = pattern.sub(repl, out)
    return out
