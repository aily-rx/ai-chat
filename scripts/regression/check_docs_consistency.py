#!/usr/bin/env python3
"""S9 docs/DSL consistency checks.

Checks controller paths, environment keys, error codes, DSL placeholders,
dataset manifest totals, and relative Markdown links. No network or secrets.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def controller_paths() -> list[str]:
    paths: set[str] = set()
    for source in (ROOT / "backend/src/main/java").rglob("*Controller.java"):
        text = source.read_text(encoding="utf-8")
        class_match = re.search(r'@RequestMapping\("([^"]+)"\)', text)
        if not class_match:
            continue
        base = class_match.group(1)
        for match in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\((.*?)\))?', text, re.S):
            args = match.group(2) or ""
            path_match = re.search(r'"([^"]*)"', args)
            path = path_match.group(1) if path_match else ""
            full = (base + path).rstrip("/") or "/"
            paths.add(full)
    return sorted(paths)


def check_controllers(checks: list[dict], warnings: list[str]) -> None:
    paths = controller_paths()
    readme = read("README.md")
    api = read("docs/api.md")
    missing = []
    for path in paths:
        if path not in readme:
            missing.append(f"README missing {path}")
        if path not in api:
            missing.append(f"docs/api.md missing {path}")
    checks.append({
        "name": "controller_paths",
        "ok": not missing,
        "controllerCount": len(paths),
        "paths": paths,
        "missing": missing,
    })
    if not paths:
        warnings.append("no controller paths parsed")


def check_env(checks: list[dict], warnings: list[str]) -> None:
    env_example = set(re.findall(r'^([A-Z][A-Z0-9_]+)=', read(".env.example"), re.M))
    app_yml = set(re.findall(r'\$\{([A-Z][A-Z0-9_]+)(?::[^}]*)?\}', read("backend/src/main/resources/application.yml")))
    readme_keys = set(re.findall(r'`([A-Z][A-Z0-9_]+)`', read("README.md")))
    optional = {"DIFY_SEPARATOR"}
    missing_from_readme = sorted(env_example - readme_keys)
    missing_from_example = sorted(app_yml - env_example - optional)
    missing_from_app = sorted(env_example - app_yml)
    checks.append({
        "name": "env_keys",
        "ok": not missing_from_readme and not missing_from_example and not missing_from_app,
        "envExampleCount": len(env_example),
        "appYmlCount": len(app_yml),
        "readmeKeys": len(readme_keys),
        "missingFromReadme": missing_from_readme,
        "missingFromExample": missing_from_example,
        "missingFromAppYml": missing_from_app,
    })
    for name in sorted(optional & app_yml):
        warnings.append(f"{name} is documented as optional/internal and not in .env.example")


def check_error_codes(checks: list[dict]) -> None:
    codes = {int(value) for value in re.findall(r'=\s*(\d{4});', read("backend/src/main/java/com/ailyrx/aichat/common/ErrorCodes.java"))}
    api = read("docs/api.md")
    missing = sorted(code for code in codes if f"`{code}`" not in api)
    has_5000 = "`5000`" in api
    checks.append({
        "name": "error_codes",
        "ok": not missing and has_5000,
        "codes": sorted(codes),
        "missingFromApi": missing,
        "has5000": has_5000,
    })


def check_dsl(checks: list[dict]) -> None:
    dsl = read("dify/dsl/official-doc-workflow-s8.yml")
    failures = []
    if "REPLACE_WITH_DATASET_B_ID" not in dsl:
        failures.append("missing placeholder REPLACE_WITH_DATASET_B_ID")
    if "human-input" not in dsl:
        failures.append("missing human-input node type")
    if "human_confirm" not in dsl:
        failures.append("missing human_confirm node id")
    dataset_items: list[str] = []
    for block in re.findall(r'dataset_ids:\s*\n((?:\s*-\s*[^\n]+\n?)+)', dsl):
        dataset_items.extend(item.strip() for item in re.findall(r'-\s*([^\n]+)', block))
    for item in dataset_items:
        if item != "REPLACE_WITH_DATASET_B_ID":
            failures.append("dataset_ids contains a non-placeholder value")
            break
    if not dataset_items:
        failures.append("dataset_ids block is missing")
    secret_patterns = (
        r"sk-[A-Za-z0-9]{20,}",
        r"app-[A-Za-z0-9]{20,}",
        r"Bearer\s+[A-Za-z0-9._-]{20,}",
    )
    for pattern in secret_patterns:
        if re.search(pattern, dsl):
            failures.append(f"possible secret pattern: {pattern}")
    checks.append({"name": "dsl", "ok": not failures, "failures": failures})


def check_manifest(checks: list[dict], warnings: list[str]) -> None:
    text = read("docs/dataset-manifest.md")
    rows = []
    for line in text.splitlines():
        if line.startswith("|") and ".pdf" in line:
            parts = [part.strip() for part in line.strip("|").split("|")]
            if len(parts) >= 9:
                rows.append(parts)
    failures = []
    if len(rows) != 20:
        failures.append(f"manifest rows={len(rows)}, expected 20")
    try:
        total_a = sum(int(parts[5]) for parts in rows)
        total_b = sum(int(parts[7]) for parts in rows)
    except ValueError as exc:
        failures.append(f"manifest count parse failed: {exc}")
        total_a = total_b = -1
    if total_a != 229:
        failures.append(f"A total={total_a}, expected 229")
    if total_b != 138:
        failures.append(f"B total={total_b}, expected 138")
    pending = sum(1 for parts in rows if "URL 待补录" in parts[1])
    if pending != 17:
        failures.append(f"pending source URLs={pending}, expected 17")
    docs_dir = ROOT / "data/docs"
    if docs_dir.is_dir():
        local = {path.name for path in docs_dir.glob("*.pdf")}
        manifest_names = {parts[0] for parts in rows}
        if local != manifest_names:
            warnings.append(f"data/docs differs from manifest: local={len(local)}, manifest={len(manifest_names)}")
    checks.append({
        "name": "dataset_manifest",
        "ok": not failures,
        "rows": len(rows),
        "totalA": total_a,
        "totalB": total_b,
        "pendingSourceUrls": pending,
        "failures": failures,
    })


def check_links(checks: list[dict]) -> None:
    documents = {
        "README.md": read("README.md"),
        "docs/api.md": read("docs/api.md"),
    }
    missing = []
    checked = 0
    for name, text in documents.items():
        base = (ROOT / name).parent
        for link in re.findall(r'\[[^\]]*\]\(([^)]+)\)', text):
            if link.startswith(("http://", "https://", "#", "mailto:")):
                continue
            target = link.split("#", 1)[0]
            if not target:
                continue
            checked += 1
            if not (base / target).exists():
                missing.append(f"{name}: {link}")
    checks.append({"name": "relative_links", "ok": not missing, "checked": checked, "missing": missing})


def check_status_text(checks: list[dict]) -> None:
    readme = read("README.md")
    stale = [
        "下一步 S9/S10",
        "阶段二截至 S8 尚未完成",
        "S9 文档、DSL、数据清单与全量回归收口",
    ]
    found = [item for item in stale if item in readme]
    has_s9 = "S9" in readme and "阶段二 S9" in readme
    checks.append({"name": "readme_status", "ok": not found and has_s9, "stale": found, "hasS9": has_s9})


def check_git(checks: list[dict]) -> None:
    try:
        tracked = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files"],
            check=True, capture_output=True, text=True, encoding="utf-8"
        ).stdout.splitlines()
    except Exception as exc:
        checks.append({"name": "git_tracking", "ok": False, "error": str(exc)})
        return
    allowed = {"data/eval/questions.json"}
    forbidden = [
        path for path in tracked
        if path == ".env"
        or path.startswith("data/docs/")
        or (path.startswith("data/eval/") and path not in allowed)
    ]
    checks.append({
        "name": "git_tracking",
        "ok": not forbidden,
        "forbidden": forbidden,
        "allowedEvalFixture": sorted(allowed),
        "trackedCount": len(tracked),
    })


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    checks: list[dict] = []
    warnings: list[str] = []
    check_git(checks)
    check_controllers(checks, warnings)
    check_env(checks, warnings)
    check_error_codes(checks)
    check_dsl(checks)
    check_manifest(checks, warnings)
    check_links(checks)
    check_status_text(checks)

    failures = [check for check in checks if not check.get("ok")]
    result = {"passed": not failures, "checks": checks, "warnings": warnings}
    (out_dir / "consistency.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    log_lines = []
    for check in checks:
        log_lines.append(f"[{'PASS' if check.get('ok') else 'FAIL'}] {check['name']}")
        if not check.get("ok"):
            log_lines.append(json.dumps(check, ensure_ascii=False, indent=2))
    if warnings:
        log_lines.append("WARNINGS")
        log_lines.extend(warnings)
    (out_dir / "consistency.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")
    print(json.dumps({
        "passed": not failures,
        "checks": len(checks),
        "failed": [check["name"] for check in failures],
        "warnings": warnings,
    }, ensure_ascii=False))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
