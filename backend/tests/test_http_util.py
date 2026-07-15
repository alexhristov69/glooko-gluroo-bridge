"""Tests for HTTP path normalization (API Gateway stage prefix)."""

from handlers.http_util import route_key


def test_strips_stage_prefix():
    method, path = route_key(
        {
            "rawPath": "/prod/settings",
            "requestContext": {
                "stage": "prod",
                "http": {"method": "PUT"},
            },
        }
    )
    assert method == "PUT"
    assert path == "/settings"


def test_admin_path_with_stage():
    method, path = route_key(
        {
            "rawPath": "/prod/admin/circuit-breaker",
            "requestContext": {
                "stage": "prod",
                "http": {"method": "GET"},
            },
        }
    )
    assert method == "GET"
    assert path == "/admin/circuit-breaker"


def test_path_without_stage_unchanged():
    method, path = route_key(
        {
            "rawPath": "/runs",
            "requestContext": {
                "stage": "$default",
                "http": {"method": "POST"},
            },
        }
    )
    assert method == "POST"
    assert path == "/runs"
