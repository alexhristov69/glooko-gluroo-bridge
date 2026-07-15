"""Shared HTTP helpers for API Gateway HTTP API (payload format 2.0)."""

from __future__ import annotations

import json
from typing import Any


def response(status: int, body: Any) -> dict[str, Any]:
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json",
            "Cache-Control": "no-store",
        },
        "body": json.dumps(body, default=str),
    }


def parse_body(event: dict[str, Any]) -> dict[str, Any]:
    raw = event.get("body") or "{}"
    if event.get("isBase64Encoded"):
        import base64

        raw = base64.b64decode(raw).decode("utf-8")
    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return {}


def bridge_id_from_event(event: dict[str, Any]) -> str:
    claims = (
        event.get("requestContext", {})
        .get("authorizer", {})
        .get("jwt", {})
        .get("claims", {})
    )
    sub = claims.get("sub")
    if not sub:
        raise PermissionError("Missing JWT sub")
    return str(sub)


def is_admin(event: dict[str, Any]) -> bool:
    claims = (
        event.get("requestContext", {})
        .get("authorizer", {})
        .get("jwt", {})
        .get("claims", {})
    )
    groups = claims.get("cognito:groups") or claims.get("cognito:groups".replace(":", "_")) or ""
    if isinstance(groups, list):
        return "g2g-admins" in groups
    return "g2g-admins" in str(groups)


def route_key(event: dict[str, Any]) -> tuple[str, str]:
    method = event.get("requestContext", {}).get("http", {}).get("method") or event.get(
        "httpMethod", "GET"
    )
    path = event.get("rawPath") or event.get("path") or "/"
    # HTTP API stage (e.g. "prod") is often included in rawPath as /prod/settings
    stage = event.get("requestContext", {}).get("stage")
    if stage and stage != "$default":
        prefix = f"/{stage}"
        if path == prefix:
            path = "/"
        elif path.startswith(prefix + "/"):
            path = path[len(prefix) :]
    # Normalize trailing slash (except root)
    if len(path) > 1 and path.endswith("/"):
        path = path.rstrip("/")
    return method.upper(), path
