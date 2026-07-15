"""Nightscout client tests."""

import hashlib

import responses

from g2g.models import NightscoutTreatment
from g2g.nightscout_client import NightscoutClient


def test_sha1_of_test_secret():
    expected = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
    assert hashlib.sha1(b"test").hexdigest() == expected


@responses.activate
def test_connection_and_upload():
    responses.add(
        responses.GET,
        "https://ns.example/api/v1/status.json",
        json={"status": "ok"},
        status=200,
    )
    responses.add(
        responses.POST,
        "https://ns.example/api/v1/treatments",
        json=[],
        status=200,
    )
    client = NightscoutClient("https://ns.example", "test", use_token_auth=False)
    details = client.test_connection()
    assert "ok" in details
    report = client.post_treatments(
        [
            NightscoutTreatment(
                event_type="Correction Bolus",
                insulin=1.0,
                created_at="2026-03-29T10:00:00Z",
            )
        ]
    )
    assert report.uploaded_count == 1
    assert responses.calls[1].request.headers["api-secret"] == hashlib.sha1(b"test").hexdigest()


@responses.activate
def test_token_auth():
    responses.add(
        responses.GET,
        "https://ns.example/api/v1/status.json",
        json={"status": "ok"},
        status=200,
        match=[responses.matchers.query_param_matcher({"token": "mytoken"})],
    )
    client = NightscoutClient("https://ns.example", "mytoken", use_token_auth=True)
    client.test_connection()
