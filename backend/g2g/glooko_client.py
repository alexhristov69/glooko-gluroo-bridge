"""Glooko HTTP client — ported from GlookoClient.kt."""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urljoin, urlparse, urlunparse

import requests

GLOOKO_BASE_URL = "https://my.glooko.com"
GLOOKO_LOGIN_PATH = "/users/sign_in"
DEVICE_ID = uuid.uuid4().hex[:16]

_UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)


class GlookoAuthError(Exception):
    pass


@dataclass
class GlookoTestResult:
    patient_id: str
    api_base: str
    dashboard_origin: str
    device_count: int


@dataclass
class RegionalHosts:
    dashboard_origin: str
    api_base: str


def _decode_brackets(url: str) -> str:
    return url.replace("%5B", "[").replace("%5D", "]").replace("%5b", "[").replace("%5d", "]")


class BracketSession(requests.Session):
    """requests.Session that keeps literal [] in query strings."""

    def request(self, method, url, *args, **kwargs):  # type: ignore[override]
        return super().request(method, _decode_brackets(url), *args, **kwargs)


class GlookoClient:
    def __init__(
        self,
        email: str,
        password: str,
        session_timeout_minutes: int = 55,
        base_url: str = GLOOKO_BASE_URL,
        http_client: requests.Session | None = None,
    ):
        self.email = email
        self.password = password
        self.session_timeout_minutes = session_timeout_minutes
        self.base_url = base_url.rstrip("/")
        self.http = http_client or BracketSession()
        self.http.headers.update({"User-Agent": _UA})
        self.patient_id: str | None = None
        self.api_base: str | None = None
        self.dashboard_origin: str | None = None
        self.auth_method: str | None = None
        self.last_auth_time: datetime | None = None
        self.authenticate()

    def ensure_authenticated(self) -> None:
        if self.last_auth_time is None:
            self.authenticate()
            return
        elapsed = (datetime.now(timezone.utc) - self.last_auth_time).total_seconds() / 60
        if elapsed >= self.session_timeout_minutes:
            self.authenticate()

    def authenticate(self) -> None:
        self.patient_id = None
        self.api_base = None
        self.dashboard_origin = None
        self.auth_method = None

        regional = self._discover_regional_hosts()
        self.dashboard_origin = regional.dashboard_origin
        self.api_base = regional.api_base

        json_error = self._try_json_api_login(regional)
        if self.patient_id is not None:
            self.auth_method = "json-api"
            self.last_auth_time = datetime.now(timezone.utc)
            return

        self._try_web_form_login(regional, json_error)
        self.auth_method = "web-form"
        self.last_auth_time = datetime.now(timezone.utc)

    def _discover_regional_hosts(self) -> RegionalHosts:
        login_url = f"{self.base_url}{GLOOKO_LOGIN_PATH}?id=login_form&locale=en-GB"
        resp = self.http.get(login_url, allow_redirects=False, timeout=30)
        location = resp.headers.get("Location") or login_url
        if not location.startswith("http"):
            location = urljoin(self.base_url + "/", location.lstrip("/"))
        parsed = urlparse(location)
        origin = f"{parsed.scheme}://{parsed.netloc}"
        api_host = parsed.netloc.replace("my.glooko", "api.glooko")
        api_base = f"{parsed.scheme}://{api_host}"
        return RegionalHosts(dashboard_origin=origin, api_base=api_base)

    def _try_json_api_login(self, regional: RegionalHosts) -> str | None:
        url = f"{regional.api_base}/api/v2/users/sign_in"
        payload = {
            "userLogin": {"email": self.email, "password": self.password},
            "deviceInformation": {
                "applicationType": "logbook",
                "os": "android",
                "osVersion": "14",
                "device": "Android",
                "deviceManufacturer": "Google",
                "deviceModel": "Mobile",
                "serialNumber": DEVICE_ID,
                "deviceId": DEVICE_ID,
                "clinicalResearch": False,
                "applicationVersion": "6.1.3",
                "buildNumber": "0",
                "gitHash": "g4fbed2011b",
            },
        }
        headers = {
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "Origin": regional.dashboard_origin,
            "Referer": f"{regional.dashboard_origin}/",
        }
        try:
            resp = self.http.post(url, json=payload, headers=headers, timeout=30)
            if resp.status_code >= 400:
                return f"HTTP {resp.status_code}: {resp.text[:300]}"
            data = resp.json()
            code = (
                (data.get("userLogin") or {}).get("glookoCode")
                or ((data.get("user") or {}).get("userLogin") or {}).get("glookoCode")
                or data.get("glookoCode")
            )
            if not code:
                return "JSON login missing glookoCode"
            self.patient_id = str(code)
            self.api_base = regional.api_base
            return None
        except Exception as exc:
            return str(exc)

    def _try_web_form_login(self, regional: RegionalHosts, json_error: str | None) -> None:
        login_url = f"{regional.dashboard_origin}{GLOOKO_LOGIN_PATH}?id=login_form&locale=en-GB"
        page = self.http.get(login_url, allow_redirects=True, timeout=30)
        csrf_match = re.search(r'name="csrf-token"\s+content="([^"]+)"', page.text)
        if not csrf_match:
            raise GlookoAuthError(f"CSRF token not found. JSON login error: {json_error}")
        csrf = csrf_match.group(1)

        form = {
            "utf8": "✓",
            "authenticity_token": csrf,
            "user[email]": self.email,
            "user[password]": self.password,
            "commit": "Log In",
        }
        headers = {
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": regional.dashboard_origin,
            "Referer": login_url,
        }
        resp = self.http.post(
            f"{regional.dashboard_origin}{GLOOKO_LOGIN_PATH}",
            data=form,
            headers=headers,
            allow_redirects=False,
            timeout=30,
        )
        final_url = resp.url
        final_html = resp.text
        redirects = 0
        while resp.status_code in range(300, 400) and redirects < 10:
            loc = resp.headers.get("Location")
            if not loc:
                break
            next_url = urljoin(final_url, loc)
            headers["Referer"] = final_url
            resp = self.http.get(next_url, headers=headers, allow_redirects=False, timeout=30)
            final_url = next_url
            final_html = resp.text if resp.text else final_html
            redirects += 1

        if resp.status_code < 300:
            final_html = resp.text

        patient = self._extract_patient_id(final_html)
        if not patient and GLOOKO_LOGIN_PATH not in final_url:
            home = self.http.get(f"{regional.dashboard_origin}/", timeout=30)
            final_html = home.text
            patient = self._extract_patient_id(final_html)

        if not patient:
            raise GlookoAuthError(
                f"Web form login failed — patient ID not found. "
                f"JSON login error: {json_error}. Final URL: {final_url}"
            )

        self.patient_id = patient
        api_from_html = re.search(r"apiUrl:\s*'([^']+)'", final_html)
        if api_from_html:
            self.api_base = api_from_html.group(1).rstrip("/")
        else:
            parsed = urlparse(final_url)
            api_host = parsed.netloc.replace("my.glooko", "api.glooko")
            self.api_base = f"{parsed.scheme}://{api_host}" if parsed.scheme else regional.api_base
        self.dashboard_origin = regional.dashboard_origin

    @staticmethod
    def _extract_patient_id(html: str) -> str | None:
        patterns = [
            r'window\.patient\s*=\s*["\']([^"\']+)["\']',
            r'window\.patientId\s*=\s*["\']([^"\']+)["\']',
            r'"patient_glooko_code"\s*:\s*"([^"]+)"',
            r'"glooko_code"\s*:\s*"([^"]+)"',
        ]
        for pattern in patterns:
            m = re.search(pattern, html)
            if m:
                return m.group(1)
        return None

    def _format_ts(self, instant: datetime) -> str:
        if instant.tzinfo is None:
            instant = instant.replace(tzinfo=timezone.utc)
        return instant.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

    def _api_get(self, path_and_query: str) -> dict[str, Any] | None:
        self.ensure_authenticated()
        assert self.api_base and self.dashboard_origin and self.patient_id
        url = _decode_brackets(f"{self.api_base}{path_and_query}")
        headers = {
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "Origin": self.dashboard_origin,
            "Referer": f"{self.dashboard_origin}/",
        }

        def do_get() -> requests.Response:
            return self.http.get(url, headers=headers, timeout=30)

        resp = do_get()
        if resp.status_code == 401:
            self.authenticate()
            resp = do_get()
        if resp.status_code >= 400 or not resp.text.strip():
            return None
        try:
            data = resp.json()
            return data if isinstance(data, dict) else None
        except Exception:
            return None

    def get_graph_data(self, start: datetime, end: datetime) -> dict[str, Any] | None:
        assert self.patient_id
        q = (
            f"/api/v3/graph/data?patient={self.patient_id}"
            f"&startDate={self._format_ts(start)}&endDate={self._format_ts(end)}"
            f"&locale=en-GB&insulinTooltips=true&filterBgReadings=true&splitByDay=false"
            f"&series[]=deliveredBolus"
        )
        return self._api_get(q)

    def get_statistics(self, start: datetime, end: datetime) -> dict[str, Any] | None:
        assert self.patient_id
        q = (
            f"/api/v3/graph/statistics/overall?patient={self.patient_id}"
            f"&startDate={self._format_ts(start)}&endDate={self._format_ts(end)}"
            f"&egv=false&includeInsulin=true&includeExercise=true"
            f"&dow=monday,tuesday,wednesday,thursday,friday,saturday,sunday"
            f"&includePumpModes=true"
        )
        return self._api_get(q)

    def get_device_settings(self) -> dict[str, Any] | None:
        assert self.patient_id
        return self._api_get(f"/api/v3/devices_and_settings?patient={self.patient_id}")

    def test_connection(self) -> GlookoTestResult:
        from g2g.glooko_parser import parse_devices

        data = self.get_device_settings()
        if data is None:
            raise RuntimeError("Failed to fetch device settings")
        devices = parse_devices(data)
        assert self.patient_id and self.api_base and self.dashboard_origin
        return GlookoTestResult(
            patient_id=self.patient_id,
            api_base=self.api_base,
            dashboard_origin=self.dashboard_origin,
            device_count=len(devices),
        )

    def session_summary(self) -> str | None:
        if not self.patient_id:
            return None
        return (
            f"patient={self.patient_id}\n"
            f"api={self.api_base}\n"
            f"origin={self.dashboard_origin}\n"
            f"auth={self.auth_method}"
        )
