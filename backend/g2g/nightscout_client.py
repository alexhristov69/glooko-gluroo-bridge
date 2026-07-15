"""Nightscout / Gluroo GGC client — ported from NightscoutClient.kt."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

import requests

from g2g.models import NightscoutTreatment

BATCH_SIZE = 50


@dataclass
class NightscoutBatchResponse:
    batch_index: int
    batch_size: int
    http_code: int
    request_url: str
    request_body: str
    response_body: str


@dataclass
class NightscoutUploadReport:
    uploaded_count: int
    batches: list[NightscoutBatchResponse]

    def format_diagnostics(self) -> str:
        lines = ["=== Nightscout upload responses ==="]
        if not self.batches:
            lines.append("(no batches sent)")
            return "\n".join(lines)
        for batch in self.batches:
            lines.append("")
            lines.append(
                f"Batch {batch.batch_index + 1}/{len(self.batches)}: "
                f"{batch.batch_size} treatment(s)"
            )
            lines.append(f"POST {batch.request_url}")
            lines.append(f"HTTP {batch.http_code}")
            lines.append("Request body:")
            lines.append(_prettify(batch.request_body))
            lines.append("Response body:")
            response = batch.response_body if batch.response_body else "(empty)"
            lines.append(response if response == "(empty)" else _prettify(response))
        return "\n".join(lines)


def _prettify(raw: str) -> str:
    try:
        return json.dumps(json.loads(raw), indent=2)
    except Exception:
        return raw


def encode_treatments(treatments: list[NightscoutTreatment]) -> str:
    return json.dumps([t.to_dict() for t in treatments], separators=(",", ":"))


class NightscoutClient:
    def __init__(
        self,
        base_url: str,
        api_secret: str,
        use_token_auth: bool = False,
        timeout: float = 30.0,
        session: requests.Session | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.api_secret = api_secret
        self.use_token_auth = use_token_auth
        self.timeout = timeout
        self.session = session or requests.Session()

    def _sha1_secret(self) -> str:
        return hashlib.sha1(self.api_secret.encode("utf-8")).hexdigest()

    def _url(self, path: str) -> str:
        url = f"{self.base_url}{path}"
        if self.use_token_auth:
            sep = "&" if "?" in url else "?"
            return f"{url}{sep}token={self.api_secret}"
        return url

    def _headers(self) -> dict[str, str]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
        }
        if not self.use_token_auth:
            headers["api-secret"] = self._sha1_secret()
        return headers

    def test_connection(self) -> str:
        url = self._url("/api/v1/status.json")
        resp = self.session.get(url, headers=self._headers(), timeout=self.timeout)
        if resp.status_code >= 400:
            raise RuntimeError(f"Nightscout status failed HTTP {resp.status_code}: {resp.text[:500]}")
        body = resp.text
        try:
            pretty = json.dumps(resp.json(), indent=2)
            return pretty[:2000]
        except Exception:
            return body[:2000]

    def post_treatments(self, treatments: list[NightscoutTreatment]) -> NightscoutUploadReport:
        if not treatments:
            return NightscoutUploadReport(uploaded_count=0, batches=[])

        batches: list[NightscoutBatchResponse] = []
        uploaded = 0
        for i in range(0, len(treatments), BATCH_SIZE):
            chunk = treatments[i : i + BATCH_SIZE]
            url = self._url("/api/v1/treatments")
            body = encode_treatments(chunk)
            resp = self.session.post(
                url,
                headers=self._headers(),
                data=body,
                timeout=self.timeout,
            )
            batches.append(
                NightscoutBatchResponse(
                    batch_index=len(batches),
                    batch_size=len(chunk),
                    http_code=resp.status_code,
                    request_url=url,
                    request_body=body,
                    response_body=resp.text,
                )
            )
            if resp.status_code < 200 or resp.status_code >= 300:
                raise RuntimeError(
                    f"Nightscout upload failed HTTP {resp.status_code} "
                    f"at {url}: {resp.text[:500]}"
                )
            uploaded += len(chunk)
        return NightscoutUploadReport(uploaded_count=uploaded, batches=batches)
