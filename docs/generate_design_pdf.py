#!/usr/bin/env python3
"""Generate CTO design document PDF."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from fpdf import FPDF


class DesignPDF(FPDF):
    def header(self):
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(100, 100, 100)
        self.cell(0, 8, "Glooko Gluroo Bridge - AWS Migration Design Document", align="L")
        self.cell(
            0,
            8,
            f"Confidential | {date.today().isoformat()}",
            align="R",
            new_x="LMARGIN",
            new_y="NEXT",
        )
        self.line(10, self.get_y(), 200, self.get_y())
        self.ln(4)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(120, 120, 120)
        self.cell(0, 10, f"Page {self.page_no()}/{{nb}}", align="C")

    def section_title(self, title: str, level: int = 1):
        sizes = {1: 16, 2: 13, 3: 11}
        self.ln(4 if level > 1 else 6)
        self.set_font("Helvetica", "B", sizes.get(level, 11))
        self.set_text_color(20, 20, 20)
        self.multi_cell(0, 7, title)
        self.ln(2)

    def body(self, text: str):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(30, 30, 30)
        self.multi_cell(0, 5.5, text)
        self.ln(1)

    def bullet(self, text: str):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(30, 30, 30)
        x = self.get_x()
        self.cell(6, 5.5, "-")
        self.multi_cell(0, 5.5, text)
        self.set_x(x)

    def table(self, headers: list[str], rows: list[list[str]], col_widths: list[int]):
        self.set_font("Helvetica", "B", 9)
        self.set_fill_color(240, 240, 240)
        for i, h in enumerate(headers):
            self.cell(col_widths[i], 7, h, border=1, fill=True)
        self.ln()
        self.set_font("Helvetica", "", 8)
        for row in rows:
            x0, y0 = self.get_x(), self.get_y()
            heights = []
            for i, cell in enumerate(row):
                self.set_xy(x0 + sum(col_widths[:i]), y0)
                self.multi_cell(col_widths[i], 5, cell, border=0)
                heights.append(self.get_y() - y0)
            row_h = max(heights) if heights else 6
            for i, cell in enumerate(row):
                x = x0 + sum(col_widths[:i])
                self.rect(x, y0, col_widths[i], row_h)
                self.set_xy(x + 1, y0 + 1)
                self.multi_cell(col_widths[i] - 2, 5, cell, border=0)
            self.set_xy(x0, y0 + row_h)


def build_pdf(output: Path) -> None:
    pdf = DesignPDF()
    pdf.alias_nb_pages()
    pdf.set_auto_page_break(auto=True, margin=18)
    pdf.add_page()

    pdf.set_font("Helvetica", "B", 22)
    pdf.set_text_color(10, 10, 10)
    pdf.multi_cell(0, 10, "Glooko Gluroo Bridge\nAWS Migration")
    pdf.ln(2)
    pdf.set_font("Helvetica", "", 12)
    pdf.set_text_color(60, 60, 60)
    pdf.multi_cell(0, 7, "Design Document for Engineering Review")
    pdf.ln(4)
    pdf.body(
        "Author: Principal Engineering\n"
        f"Audience: CTO / Engineering Leadership\n"
        f"Date: {date.today().strftime('%B %d, %Y')}\n"
        "Status: Draft for Review\n"
        "Version: 1.0"
    )

    pdf.section_title("1. Executive Summary")
    pdf.body(
        "This document proposes migrating the Glooko-to-Gluroo insulin and pump data sync from a "
        "standalone Android client to a serverless AWS backend. The Android app remains the "
        "configuration and control surface (Save, Test, Sync Now), but all sync execution, scheduling, "
        "deduplication state, and credential storage move to the cloud."
    )
    pdf.body(
        "The design optimizes for three constraints: minimal operational cost (~$2-3/month at 10 bridges), "
        "multi-tenant isolation on a single stack, and hard cost guardrails that cap worst-case spend "
        "in the low single-digit dollars per day even under failure conditions."
    )
    pdf.body(
        "Recommendation: Proceed with a phased rollout using AWS SAM, Python Lambda, Step Functions, "
        "DynamoDB, Cognito, and SSM Parameter Store."
    )

    pdf.section_title("2. Background")
    pdf.body(
        "Glooko Gluroo Bridge reads bolus and pump data from Glooko (unofficial browser-login API) "
        "and publishes Nightscout-compatible treatments to Gluroo Global Connect. Today the entire "
        "pipeline runs on-device with EncryptedSharedPreferences, Room SQLite, and WorkManager."
    )
    pdf.body(
        "Limitations: sync stops when the phone is off; credentials on personal devices; no centralized "
        "observability; sync logic updates require app releases."
    )

    pdf.section_title("3. Goals and Non-Goals")
    pdf.section_title("3.1 Goals", 2)
    for g in [
        "Port sync to AWS; preserve Android configuration UI.",
        "Multi-tenant: many bridges on one AWS deployment.",
        "AWS-only scheduled sync (phone not required).",
        "Workflow visibility in AWS (run status, history, step progress).",
        "Minimize cost; target under $5/month at modest scale.",
        "Secure credential storage; prevent runaway AWS charges.",
    ]:
        pdf.bullet(g)

    pdf.section_title("3.2 Non-Goals", 2)
    for ng in [
        "CGM glucose upload (unchanged).",
        "Real-time sync (Glooko delay remains 1-4 hours).",
        "Clinical dosing or FDA-regulated behavior.",
        "Web admin portal in v1.",
        "Multiple bridges per Cognito user in v1.",
    ]:
        pdf.bullet(ng)

    pdf.section_title("4. Proposed Architecture")
    pdf.body(
        "Thin-client pattern: Android authenticates via Cognito, calls API Gateway, polls async results. "
        "Step Functions orchestrates test/sync workflows. EventBridge rate(1 minute) drives scheduled sync."
    )
    pdf.table(
        ["Component", "Purpose", "Cost"],
        [
            ["API Gateway HTTP API", "REST + JWT auth", "Per-request"],
            ["Cognito User Pool", "Multi-tenant identity", "Free to 50k MAU"],
            ["Step Functions Standard", "Workflow + console visibility", "Cents/month"],
            ["Lambda Python 3.12", "API, sync, scheduler, breaker", "Per-invocation"],
            ["DynamoDB on-demand", "Config, runs, dedupe", "Per-request"],
            ["SSM Parameter Store", "Secrets + breaker state", "$0.05/bridge/mo"],
            ["EventBridge", "Scheduler + breaker poll", "Negligible"],
        ],
        [52, 78, 60],
    )

    pdf.section_title("4.1 Deliberately Excluded", 2)
    for x in [
        "ECS/Fargate, EC2, App Runner (always-on cost).",
        "VPC + NAT Gateway (surprise bills).",
        "RDS (no relational workload).",
        "Secrets Manager per user ($0.40 vs $0.05 SSM).",
        "Per-user EventBridge schedules.",
    ]:
        pdf.bullet(x)

    pdf.add_page()
    pdf.section_title("5. Workflow Design")
    pdf.body(
        "One Step Functions state machine for test and sync modes. "
        "States: LoadConfig -> TestGlooko -> TestNightscout -> FetchAndPreview -> "
        "[UploadTreatments if sync] -> UpdateState -> RecordRun."
    )
    pdf.body(
        "Execution names: {bridgeId}-{runId}. Zero retries. Lambda timeout 120s. "
        "Status mirrored in DynamoDB: QUEUED, RUNNING, SUCCEEDED, FAILED."
    )

    pdf.section_title("6. Data Model")
    pdf.table(
        ["Table", "Key", "Contents"],
        [
            ["bridges", "bridgeId (= Cognito sub)", "Settings, sync state, nextScheduledSync"],
            ["sync_runs", "bridgeId + runId", "Status, diagnostics, TTL 90 days"],
            ["synced_records", "bridgeId + dedupeKey", "Uploaded treatment dedupe"],
        ],
        [40, 55, 95],
    )
    pdf.body("Secrets in SSM SecureString at /g2g/bridges/{bridgeId}/credentials.")

    pdf.section_title("7. API Contract")
    pdf.table(
        ["Method", "Path", "Description"],
        [
            ["PUT", "/settings", "Save config; secrets to SSM"],
            ["POST", "/runs", "Start test or sync; returns runId"],
            ["GET", "/runs/{runId}", "Poll status and diagnostics"],
            ["GET", "/status", "Current run + breaker state"],
            ["POST", "/admin/circuit-breaker/override", "24h operator override"],
            ["POST", "/admin/circuit-breaker/reset", "Clear trip, resume sync"],
        ],
        [22, 68, 100],
    )

    pdf.section_title("8. Security Architecture")
    pdf.bullet("Secrets in SSM SecureString only; never DynamoDB plaintext or logs.")
    pdf.bullet("Cognito JWT authorizer; bridgeId = Cognito sub.")
    pdf.bullet("Lambda validates JWT sub matches bridgeId.")
    pdf.bullet("Admin ops require Cognito group g2g-admins.")
    pdf.bullet("TLS everywhere; API Gateway throttling.")

    pdf.section_title("9. Cost Model")
    pdf.table(
        ["Scale", "Monthly estimate"],
        [
            ["10 bridges, 15-min sync", "$2-3"],
            ["50 bridges", "$5-8"],
            ["Worst-case runaway (before breaker)", "$1-2/day Lambda ceiling"],
        ],
        [95, 95],
    )

    pdf.section_title("10. Cost Guardrails and Circuit Breaker")
    pdf.section_title("10.1 Application Caps", 2)
    pdf.table(
        ["Control", "Limit"],
        [
            ["Active runs per bridge", "1"],
            ["Scheduler fan-out per tick", "50 bridges"],
            ["Runs per bridge per day", "200"],
            ["Test runs per bridge per hour", "20"],
            ["Minimum sync interval", "5 minutes"],
            ["Step Functions retries", "0"],
        ],
        [95, 95],
    )

    pdf.section_title("10.2 Infrastructure Caps", 2)
    pdf.bullet("sync_worker Lambda: reservedConcurrentExecutions = 5.")
    pdf.bullet("scheduler Lambda: reservedConcurrentExecutions = 1.")
    pdf.bullet("trigger_run Lambda: reservedConcurrentExecutions = 10.")

    pdf.section_title("10.3 Circuit Breaker Lambda", 2)
    pdf.body(
        "Auto-trips when: Lambda invocations > 2,000/hour, Step Functions starts > 1,000/hour, "
        "or global daily runs > 3,000. Sets sync_enabled=false and sends SNS alert."
    )
    pdf.body(
        "Operator override: POST /admin/circuit-breaker/override grants 24h window where sync "
        "resumes despite trip. Full reset via POST /admin/circuit-breaker/reset. "
        "AWS Budget alarm at $10/month as billing backstop."
    )

    pdf.add_page()
    pdf.section_title("11. Observability")
    pdf.bullet("Step Functions console: execution graph, step I/O.")
    pdf.bullet("DynamoDB sync_runs: 90-day history via API.")
    pdf.bullet("CloudWatch Logs with secret redaction.")
    pdf.bullet("GET /status exposes syncPaused and overrideUntil to app.")

    pdf.section_title("12. Android Client Changes")
    pdf.body(
        "UI unchanged. ViewModel delegates to CloudSyncRepository. Cognito sign-in added. "
        "WorkManager retired. Test/Sync Now use async POST + poll. Rollback via feature flag."
    )

    pdf.section_title("13. Implementation Plan")
    pdf.table(
        ["Phase", "Deliverables", "Estimate"],
        [
            ["1 - Backend core", "Python port, pytest, SAM stack", "1-2 weeks"],
            ["2 - API + scheduler", "API, Step Functions, breaker", "1-2 weeks"],
            ["3 - Android", "Cognito, API client, ViewModel", "1 week"],
            ["4 - Hardening", "Guardrails, IAM, docs", "3-5 days"],
        ],
        [38, 102, 50],
    )

    pdf.section_title("14. Risks and Mitigations")
    pdf.table(
        ["Risk", "Mitigation"],
        [
            ["Glooko API changes", "Parity tests; FAILED run monitoring"],
            ["Python port drift", "Reuse Kotlin test fixtures"],
            ["Credential breach", "SSM, IAM scoping, log redaction"],
            ["Scheduler bug / cost spike", "Concurrency caps + circuit breaker"],
        ],
        [60, 130],
    )

    pdf.section_title("15. Alternatives Considered")
    pdf.table(
        ["Alternative", "Why rejected"],
        [
            ["On-device only", "No observability; phone-dependent"],
            ["ECS Fargate", "Always-on cost"],
            ["Secrets Manager per user", "8x SSM cost"],
            ["SQS only (no Step Functions)", "No workflow console"],
        ],
        [60, 130],
    )

    pdf.section_title("16. Approval Requested")
    pdf.body(
        "Approval to proceed with Phase 1 (backend port + SAM scaffold). No production traffic "
        "migrates until Phase 3 complete and parity tests pass. Rollback available via "
        "on-device sync feature flag."
    )

    pdf.output(str(output))
    print(f"Wrote {output}")


if __name__ == "__main__":
    out = Path(__file__).resolve().parent / "Glooko-Gluroo-Bridge-AWS-Design.pdf"
    build_pdf(out)
