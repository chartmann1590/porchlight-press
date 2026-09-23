"""Shared HTTP client: descriptive User-Agent, 15 s timeout, retries, body cap."""
from __future__ import annotations

import time
from typing import Mapping

import httpx

APP_UA_TEMPLATE = "PorchlightPress/{version} (+https://github.com/chartmann1590/porchlight-press; {contact})"


def user_agent(version: str = "0.1", contact: str = "me@charleshartman.com") -> str:
    return APP_UA_TEMPLATE.format(version=version, contact=contact)


def build_client(contact: str, timeout_seconds: int = 15) -> httpx.Client:
    return httpx.Client(
        headers={"User-Agent": user_agent(contact=contact), "Accept": "*/*"},
        timeout=timeout_seconds,
        follow_redirects=True,
        max_redirects=5,
    )


def get_bytes(
    client: httpx.Client,
    url: str,
    *,
    max_body_bytes: int,
    extra_headers: Mapping[str, str] | None = None,
    retries: int = 2,
) -> httpx.Response:
    """GET with backoff retries. Raises httpx.HTTPError on final failure,
    ValueError if the body exceeds max_body_bytes."""
    last: Exception | None = None
    for attempt in range(retries + 1):
        try:
            resp = client.get(url, headers=dict(extra_headers or {}))
            resp.raise_for_status()
            if len(resp.content) > max_body_bytes:
                raise ValueError(f"body {len(resp.content)}B exceeds cap {max_body_bytes}B")
            return resp
        except ValueError:
            raise
        except httpx.HTTPStatusError as exc:
            # Never retry rate limits: hammering a 429 makes it worse.
            if exc.response is not None and exc.response.status_code == 429:
                raise
            last = exc
            if attempt < retries:
                time.sleep(2**attempt)
        except httpx.HTTPError as exc:
            last = exc
            if attempt < retries:
                time.sleep(2**attempt)
    assert last is not None
    raise last
