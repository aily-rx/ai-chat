#!/usr/bin/env python3
"""S9 real Workflow E2E regression.

Requires: backend :8080, Dify running, .env configured with a real workflow key.
Writes evidence only under the supplied output directory; never prints keys.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import requests

SSE_TIMEOUT = 300
HTTP_TIMEOUT = 30


def sse_post(url: str, payload: dict) -> list[dict]:
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream, application/json",
    }
    events: list[dict] = []
    current: dict = {}
    with requests.post(url, json=payload, headers=headers, stream=True, timeout=(10, SSE_TIMEOUT)) as response:
        response.raise_for_status()
        response.encoding = "utf-8"
        for raw in response.iter_lines(decode_unicode=True):
            if raw is None:
                continue
            if raw == "":
                if current:
                    events.append(current)
                    current = {}
                continue
            if raw.startswith("event:"):
                current["event"] = raw[6:].strip()
            elif raw.startswith("data:"):
                text = raw[5:].strip()
                try:
                    current["data"] = json.loads(text)
                except json.JSONDecodeError:
                    current["data"] = text
    if current:
        events.append(current)
    return events


def json_request(method: str, url: str, **kwargs) -> tuple[int, dict]:
    response = requests.request(method, url, timeout=HTTP_TIMEOUT, **kwargs)
    try:
        body = response.json()
    except ValueError:
        body = {"raw": response.text[:500]}
    return response.status_code, body


def start_run(base: str, task: str, draft: str = "") -> tuple[str, list[dict]]:
    events = sse_post(f"{base}/api/workflow/run/stream", {"input": task, "draft": draft})
    errors = [event for event in events if event.get("event") == "error"]
    if errors:
        raise AssertionError(f"workflow emitted error: {errors[-1].get('data')}")
    done = next((event.get("data") for event in reversed(events) if event.get("event") == "done"), None)
    if not isinstance(done, dict):
        raise AssertionError("workflow did not emit done")
    if str(done.get("status", "")).upper() != "WAITING_CONFIRM":
        raise AssertionError(f"unexpected done.status={done.get('status')}")
    run_id = done.get("runId")
    if not run_id:
        raise AssertionError("done.runId is missing")
    return run_id, events


def get_view(base: str, run_id: str) -> dict:
    status, body = json_request("GET", f"{base}/api/workflow/{run_id}")
    if status != 200 or body.get("code") != 0:
        raise AssertionError(f"GET run failed http={status} body={body}")
    data = body.get("data") or {}
    raw = json.dumps(body, ensure_ascii=False)
    if "formToken" in raw or "form_token" in raw:
        raise AssertionError("form token leaked in run view")
    return data


def confirm(base: str, run_id: str, action: str, content: str | None = None) -> dict:
    payload = {"action": action}
    if content is not None:
        payload["content"] = content
    status, body = json_request("POST", f"{base}/api/workflow/{run_id}/confirm", json=payload)
    if status != 200:
        raise AssertionError(f"confirm http={status} body={body}")
    return body


def assert_business_code(body: dict, expected: int, label: str) -> None:
    if body.get("code") != expected:
        raise AssertionError(f"{label}: expected code={expected}, got {body.get('code')} body={body}")


def export(base: str, run_id: str, fmt: str) -> requests.Response:
    return requests.get(
        f"{base}/api/workflow/{run_id}/export",
        params={"format": fmt},
        timeout=HTTP_TIMEOUT,
    )


def docx_text(payload: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        xml = archive.read("word/document.xml")
    root = ET.fromstring(xml)
    parts = []
    for node in root.iter():
        if node.tag.endswith("}t") and node.text:
            parts.append(node.text)
    return "".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--out", required=True)
    parser.add_argument("--task", default="起草一份关于开展安全生产检查的通知")
    args = parser.parse_args()

    base = args.base.rstrip("/")
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    started = time.time()
    result: dict = {"base": base, "task": args.task, "steps": [], "passed": False}
    marker = f"S9_E2E_{int(started)}"

    def step(name: str, **data) -> None:
        entry = {"name": name, "ok": True}
        entry.update(data)
        result["steps"].append(entry)

    try:
        run_id, first_events = start_run(base, args.task)
        counts = {
            name: sum(1 for event in first_events if event.get("event") == name)
            for name in ("node", "message", "done", "error")
        }
        step("run_paused", runId=run_id, eventCounts=counts)

        view = get_view(base, run_id)
        if view.get("status") != "WAITING_CONFIRM":
            raise AssertionError(f"expected WAITING_CONFIRM, got {view.get('status')}")
        if not str(view.get("draft", "")).strip():
            raise AssertionError("draft is empty while WAITING_CONFIRM")
        step("view_waiting", status=view.get("status"), draftChars=len(view.get("draft", "")))

        invalid = confirm(base, run_id, "__invalid__")
        assert_business_code(invalid, 5005, "invalid action")
        step("invalid_action_5005")

        base_content = str(view.get("draft", "")).strip()
        content = f"{base_content}\n\n{marker} 请各部门于本周五前反馈。"
        if len(content) > 50000:
            raise AssertionError("modified content exceeds 50000 characters")
        modified = confirm(base, run_id, "modify", content)
        assert_business_code(modified, 0, "modify confirm")
        modified_view = modified.get("data") or {}
        if modified_view.get("status") != "SUCCEEDED":
            raise AssertionError(f"modify did not succeed: {modified_view.get('status')}")
        step("modify_succeeded", status=modified_view.get("status"))

        md = export(base, run_id, "md")
        if md.status_code != 200 or marker not in md.text:
            raise AssertionError(f"markdown export failed http={md.status_code} marker={marker in md.text}")
        (out_dir / "export.md").write_bytes(md.content)
        step("export_md", bytes=len(md.content), contentType=md.headers.get("Content-Type", ""))

        docx = export(base, run_id, "docx")
        if docx.status_code != 200:
            raise AssertionError(f"docx export failed http={docx.status_code}")
        text = docx_text(docx.content)
        if marker not in text or "校验摘要" not in text:
            raise AssertionError("docx text missing marker or Chinese summary")
        (out_dir / "export.docx").write_bytes(docx.content)
        step("export_docx", bytes=len(docx.content), chineseText=True)

        run2, _ = start_run(base, args.task)
        unconfirmed = export(base, run2, "md").json()
        assert_business_code(unconfirmed, 5006, "unconfirmed export")
        step("unconfirmed_export_5006")

        rejected = confirm(base, run2, "reject")
        assert_business_code(rejected, 0, "reject")
        rejected_view = rejected.get("data") or {}
        if rejected_view.get("status") != "REJECTED":
            raise AssertionError(f"reject did not set REJECTED: {rejected_view.get('status')}")
        step("reject_rejected", status=rejected_view.get("status"))

        rejected_export = export(base, run2, "md").json()
        assert_business_code(rejected_export, 5006, "rejected export")
        step("rejected_export_5006")

        duplicate = confirm(base, run2, "reject")
        assert_business_code(duplicate, 5005, "duplicate reject")
        step("duplicate_confirm_5005")

        unknown = export(base, "s9-does-not-exist", "md").json()
        assert_business_code(unknown, 5006, "unknown run export")
        step("unknown_run_export_5006")

        unsupported = export(base, run_id, "pdf").json()
        assert_business_code(unsupported, 5006, "unsupported format")
        step("unsupported_format_5006")

        result["passed"] = True
        result["elapsedMs"] = int((time.time() - started) * 1000)
        result["note"] = "in-memory runs remain until backend restart; no API delete exists"
        print(json.dumps({"passed": True, "steps": len(result["steps"]), "elapsedMs": result["elapsedMs"]}, ensure_ascii=False))
        return 0
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
        result["elapsedMs"] = int((time.time() - started) * 1000)
        print(json.dumps({"passed": False, "error": result["error"]}, ensure_ascii=False))
        return 1
    finally:
        (out_dir / "e2e.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
