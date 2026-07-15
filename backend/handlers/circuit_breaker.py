"""Circuit breaker Lambda — auto-trip on metric / run-count thresholds."""

from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone
from typing import Any

import boto3

from g2g import aws_store as store

LAMBDA_INVOCATIONS_1H = 2000
SFN_STARTS_1H = 1000
GLOBAL_RUNS_DAY = 3000


def _sum_metric(namespace: str, metric: str, minutes: int = 60) -> float:
    cw = boto3.client("cloudwatch")
    end = datetime.now(timezone.utc)
    start = end - timedelta(minutes=minutes)
    resp = cw.get_metric_statistics(
        Namespace=namespace,
        MetricName=metric,
        StartTime=start,
        EndTime=end,
        Period=minutes * 60,
        Statistics=["Sum"],
    )
    points = resp.get("Datapoints") or []
    if not points:
        return 0.0
    return float(max(p.get("Sum", 0) for p in points))


def _publish_alert(subject: str, message: str) -> None:
    topic = os.environ.get("ALERT_TOPIC_ARN")
    if not topic:
        return
    try:
        sns = boto3.client("sns")
        sns.publish(
            TopicArn=topic,
            Subject=subject[:100],
            Message=message,
        )
    except Exception:
        # Alert delivery must not undo or fail an otherwise successful trip/reset.
        pass


def trip(reason: str) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    store.put_global_param("sync_enabled", "false")
    store.put_global_param("circuit_breaker_tripped_at", now)
    store.put_global_param("circuit_breaker_tripped_reason", reason)
    # Do NOT clear override_until — operator-controlled
    _publish_alert(
        subject="G2G circuit breaker tripped — sync paused",
        message=(
            f"Circuit breaker tripped.\n"
            f"Reason: {reason}\n"
            f"At: {now}\n"
            f"sync_enabled set to false.\n"
            f"Override for 24h: POST /admin/circuit-breaker/override\n"
            f"Full reset: POST /admin/circuit-breaker/reset\n"
        ),
    )
    return {"tripped": True, "reason": reason, "at": now}


def reset() -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    store.put_global_param("sync_enabled", "true")
    store.put_global_param("circuit_breaker_tripped_at", "")
    store.put_global_param("circuit_breaker_tripped_reason", "")
    store.put_global_param("circuit_breaker_override_until", "")
    _publish_alert(
        subject="G2G circuit breaker reset — sync allowed",
        message=(
            f"Circuit breaker reset.\n"
            f"At: {now}\n"
            f"sync_enabled set to true.\n"
            f"Trip state and override cleared — scheduled sync may proceed.\n"
        ),
    )
    return {"reset": True, "at": now}


def handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    # Already tripped and no override needed for this function — still check metrics
    reasons: list[str] = []

    try:
        inv = _sum_metric("AWS/Lambda", "Invocations", 60)
        if inv > LAMBDA_INVOCATIONS_1H:
            reasons.append(f"lambda_invocations_{int(inv)}")
    except Exception as exc:
        reasons.append(f"metric_error_lambda:{exc}")

    try:
        starts = _sum_metric("AWS/States", "ExecutionsStarted", 60)
        if starts > SFN_STARTS_1H:
            reasons.append(f"sfn_starts_{int(starts)}")
    except Exception as exc:
        reasons.append(f"metric_error_sfn:{exc}")

    try:
        runs = store.count_global_runs_today()
        if runs > GLOBAL_RUNS_DAY:
            reasons.append(f"global_runs_day_{runs}")
    except Exception as exc:
        reasons.append(f"count_error:{exc}")

    # Alarm event from SNS/EventBridge may force a trip
    if event.get("forceTrip") or event.get("Records"):
        # SNS alarm notification
        if not reasons:
            reasons.append("cloudwatch_alarm")

    # Ignore metric_error* alone unless forceTrip
    hard = [r for r in reasons if not r.startswith("metric_error") and not r.startswith("count_error")]
    if not hard:
        return {"tripped": False, "checked": True, "reasons": reasons}

    # Only trip once reasons are real threshold breaches
    globals_ = store.get_global_params()
    if globals_.get("sync_enabled") == "false" and globals_.get("circuit_breaker_tripped_at"):
        return {"tripped": False, "alreadyTripped": True, "reasons": hard}

    return trip(hard[0])
