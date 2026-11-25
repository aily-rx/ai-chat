#!/usr/bin/env python3
"""Import data/docs into Dify datasets A/B and export chunk records.

The script never prints the API key. Real documents and eval exports stay in
gitignored directories; only the manifest is intended to be committed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from statistics import median
from typing import Any

import requests

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DOCS = REPO_ROOT / "data" / "docs"
DEFAULT_EVAL = REPO_ROOT / "data" / "eval"
DEFAULT_MANIFEST = REPO_ROOT / "docs" / "dataset-manifest.md"
DATASET_NAMES = {"a": "ai-chat-kb-a", "b": "ai-chat-kb-b"}
EMBEDDING_MODEL = "Pro/BAAI/bge-m3"
EMBEDDING_PROVIDER = "langgenius/siliconflow/siliconflow"
SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".txt", ".md"}
MIME_TYPES = {
    ".pdf": "application/pdf",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".txt": "text/plain",
    ".md": "text/markdown",
}
NEWLINE = chr(10)
FIXED_SPLIT_SENTINEL = "@@AI_CHAT_FIXED_CHUNK@@"
STRATEGIES = {
    "a": {
        "name": "A",
        "separator": FIXED_SPLIT_SENTINEL,
        "max_tokens": 500,
        "chunk_overlap": 50,
        "description": "不出现的哨兵分隔符 + 递归 500 token 上限，近似固定长度 500 字符",
    },
    "b": {
        "name": "B",
        "separator": NEWLINE + NEWLINE,
        "max_tokens": 800,
        "chunk_overlap": 100,
        "description": "按段落分隔，最大 800 token，重叠 100",
    },
}


class ScriptError(RuntimeError):
    pass


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def list_documents(api: "DifyApi", dataset_id: str) -> list[dict[str, Any]]:
    documents: list[dict[str, Any]] = []
    page = 1
    while True:
        payload = api.request(
            "GET",
            f"/datasets/{dataset_id}/documents",
            params={"page": page, "limit": 100},
        )
        documents.extend(payload.get("data", []))
        if not payload.get("has_more"):
            break
        page += 1
    return documents


def list_segments(api: "DifyApi", dataset_id: str, document_id: str) -> list[dict[str, Any]]:
    segments: list[dict[str, Any]] = []
    page = 1
    while True:
        payload = api.request(
            "GET",
            f"/datasets/{dataset_id}/documents/{document_id}/segments",
            params={"page": page, "limit": 100},
        )
        segments.extend(payload.get("data", []))
        if not payload.get("has_more"):
            break
        page += 1
    return segments


def page_count(path: Path) -> int | None:
    if path.suffix.lower() != ".pdf":
        return None
    try:
        from pypdf import PdfReader
    except Exception:
        return None
    try:
        return len(PdfReader(str(path)).pages)
    except Exception:
        return None


class DifyApi:
    def __init__(self, base_url: str, api_key: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({"Authorization": f"Bearer {api_key}"})

    def request(
        self,
        method: str,
        path: str,
        *,
        expected: tuple[int, ...] = (200,),
        timeout: int = 60,
        **kwargs: Any,
    ) -> dict[str, Any]:
        try:
            response = self.session.request(
                method, self.base_url + path, timeout=timeout, **kwargs
            )
        except requests.RequestException as exc:
            raise ScriptError(f"{method} {path} 网络错误: {type(exc).__name__}") from exc
        if response.status_code not in expected:
            body = response.text.replace("\n", " ")[:300]
            raise ScriptError(f"{method} {path} HTTP {response.status_code}: {body}")
        if not response.content:
            return {}
        try:
            return response.json()
        except ValueError as exc:
            raise ScriptError(f"{method} {path} 返回非 JSON") from exc

    def find_dataset(self, name: str) -> dict[str, Any] | None:
        payload = self.request("GET", "/datasets", params={"page": 1, "limit": 100})
        for dataset in payload.get("data", []):
            if dataset.get("name") == name:
                return dataset
        return None

    def create_dataset(self, name: str) -> dict[str, Any]:
        payload = {
            "name": name,
            "description": "阶段二 RAG 知识库；同一批资料按 A/B 两种切分策略建库",
            "indexing_technique": "high_quality",
            "permission": "only_me",
            "provider": "vendor",
            "embedding_model": EMBEDDING_MODEL,
            "embedding_model_provider": EMBEDDING_PROVIDER,
        }
        result = self.request("POST", "/datasets", json=payload)
        dataset = result.get("data", result)
        if not dataset.get("id"):
            raise ScriptError(f"创建数据集 {name} 后未返回 id")
        return dataset

    def ensure_dataset(self, name: str, recreate: bool) -> tuple[str, bool]:
        existing = self.find_dataset(name)
        if existing and recreate:
            self.request("DELETE", f"/datasets/{existing['id']}", expected=(204,))
            existing = None
        if existing:
            if (
                existing.get("embedding_model") != EMBEDDING_MODEL
                or existing.get("embedding_model_provider") != EMBEDDING_PROVIDER
            ):
                raise ScriptError(
                    f"数据集 {name} 已存在但 Embedding 不匹配，拒绝复用"
                )
            return existing["id"], False
        created = self.create_dataset(name)
        return created["id"], True

    def upload_document(
        self, dataset_id: str, path: Path, strategy: dict[str, Any]
    ) -> tuple[str, str]:
        data = {
            "indexing_technique": "high_quality",
            "doc_form": "text_model",
            "doc_language": "Chinese",
            "process_rule": {
                "mode": "custom",
                "rules": {
                    "pre_processing_rules": [
                        {"id": "remove_extra_spaces", "enabled": True},
                        {"id": "remove_urls_emails", "enabled": False},
                    ],
                    "segmentation": {
                        "separator": strategy["separator"],
                        "max_tokens": strategy["max_tokens"],
                        "chunk_overlap": strategy["chunk_overlap"],
                    },
                },
            },
        }
        mime = MIME_TYPES.get(path.suffix.lower(), "application/octet-stream")
        with path.open("rb") as handle:
            payload = self.request(
                "POST",
                f"/datasets/{dataset_id}/document/create-by-file",
                data={"data": json.dumps(data, ensure_ascii=False)},
                files={"file": (path.name, handle, mime)},
                timeout=180,
            )
        document = payload.get("document", {})
        document_id = document.get("id")
        if not document_id:
            raise ScriptError(f"{path.name} 上传后未返回 document id")
        return document_id, payload.get("batch", "")

    def wait_batch(
        self, dataset_id: str, batch_id: str, timeout: int
    ) -> list[dict[str, Any]]:
        if not batch_id:
            raise ScriptError("上传结果缺少 batch id，无法轮询索引状态")
        deadline = time.time() + timeout
        while True:
            payload = self.request(
                "GET", f"/datasets/{dataset_id}/documents/{batch_id}/indexing-status"
            )
            items = payload.get("data", [])
            statuses = [item.get("indexing_status") for item in items]
            if statuses and all(status == "completed" for status in statuses):
                return items
            failed = [
                item for item in items
                if item.get("indexing_status") in {"error", "paused", "stopped"}
            ]
            if failed:
                errors = ", ".join(
                    f"{item.get('id')}:{item.get('error') or item.get('indexing_status')}"
                    for item in failed
                )
                raise ScriptError(f"{batch_id} 索引失败: {errors}")
            if time.time() >= deadline:
                raise ScriptError(f"{batch_id} 索引超时")
            time.sleep(3)

    def wait_document(self, dataset_id: str, document_id: str, timeout: int) -> dict[str, Any]:
        deadline = time.time() + timeout
        while True:
            payload = self.request(
                "GET", f"/datasets/{dataset_id}/documents/{document_id}"
            )
            document = payload.get("data", payload)
            status = document.get("indexing_status")
            if status == "completed":
                return document
            if status in {"error", "paused", "stopped"}:
                raise ScriptError(
                    f"文档 {document_id} 索引失败: {document.get('error') or status}"
                )
            if time.time() >= deadline:
                raise ScriptError(f"文档 {document_id} 索引超时")
            time.sleep(3)


def display_separator(value: str) -> str:
    if value == "":
        return "空"
    return value.replace(NEWLINE, chr(92) + "n")


def chunk_stats(segments: list[dict[str, Any]]) -> dict[str, Any]:
    char_counts = [len(segment.get("content", "")) for segment in segments]
    return {
        "chunkCount": len(segments),
        "minChars": min(char_counts) if char_counts else 0,
        "medianChars": round(median(char_counts)) if char_counts else 0,
        "maxChars": max(char_counts) if char_counts else 0,
    }


def export_chunks(
    api: DifyApi,
    key: str,
    dataset_id: str,
    docs: list[Path],
    docs_by_name: dict[str, dict[str, Any]],
    output_dir: Path,
    timeout: int,
) -> dict[str, Any]:
    exported_docs = []
    for path in docs:
        doc = docs_by_name[path.name]
        status = doc.get("indexing_status")
        if status != "completed":
            doc = api.wait_document(dataset_id, doc["id"], timeout)
        segments = list_segments(api, dataset_id, doc["id"])
        if not segments:
            raise ScriptError(f"{path.name} 索引完成但没有分段")
        ordered = sorted(segments, key=lambda item: item.get("position", 0))
        stats = chunk_stats(ordered)
        exported_docs.append(
            {
                "docId": doc["id"],
                "docName": path.name,
                "sourceHash": sha256_file(path),
                "chunkCount": stats["chunkCount"],
                "minChars": stats["minChars"],
                "medianChars": stats["medianChars"],
                "maxChars": stats["maxChars"],
                "chunks": [
                    {
                        "segmentId": segment.get("id"),
                        "segmentIndex": segment.get("position"),
                        "charCount": len(segment.get("content", "")),
                        "wordCount": segment.get("word_count"),
                        "tokens": segment.get("tokens"),
                    }
                    for segment in ordered
                ],
            }
        )
    payload = {
        "datasetName": DATASET_NAMES[key],
        "datasetId": dataset_id,
        "strategy": STRATEGIES[key]["name"],
        "parameters": {
            "separator": STRATEGIES[key]["separator"],
            "maxTokens": STRATEGIES[key]["max_tokens"],
            "chunkOverlap": STRATEGIES[key]["chunk_overlap"],
        },
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "documents": exported_docs,
    }
    output_path = output_dir / f"chunks-{key}.json"
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return payload


def write_manifest(
    path: Path,
    dataset_ids: dict[str, str],
    docs: list[Path],
    exports: dict[str, Any],
    sources: dict[str, str],
    generated_at: str,
) -> None:
    by_key = {key: {doc["docName"]: doc for doc in payload["documents"]} for key, payload in exports.items()}
    lines = [
        "# 阶段二知识库数据清单",
        "",
        f"> 生成时间：{generated_at}",
        f"> 资料数量：{len(docs)} 份（目标 20–30 份，至少 20 份）",
        f"> Embedding：`{EMBEDDING_MODEL}`（`{EMBEDDING_PROVIDER}`）",
        "> 真实资料与切分导出位于 `data/docs/`、`data/eval/`，已被 gitignore；本清单只记录可追溯信息。",
        "",
        "## 数据集",
        "",
        "| 策略 | 数据集 | ID | 切分参数 |",
        "|---|---|---|---|",
    ]
    for key in ("a", "b"):
        strategy = STRATEGIES[key]
        lines.append(
            f"| {strategy['name']} | `{DATASET_NAMES[key]}` | `{dataset_ids[key]}` | "
            f"separator=`{display_separator(strategy['separator'])}`，"
            f"max_tokens={strategy['max_tokens']}，overlap={strategy['chunk_overlap']} |"
        )
    lines.extend(
        [
            "",
            "## 资料与切分结果",
            "",
            "| 文档 | 来源 | 页数 | 大小 | SHA256 | A 段数 | A 字符范围 | B 段数 | B 字符范围 |",
            "|---|---|---:|---:|---|---:|---|---:|---|",
        ]
    )
    for doc in docs:
        a_stats = by_key["a"].get(doc.name, {})
        b_stats = by_key["b"].get(doc.name, {})
        source = sources.get(doc.name, "待补录来源")
        pages = page_count(doc)
        lines.append(
            f"| {doc.name} | {source} | {pages if pages is not None else '-'} | "
            f"{doc.stat().st_size} | `{sha256_file(doc)}` | "
            f"{a_stats.get('chunkCount', 0)} | {a_stats.get('minChars', 0)}–{a_stats.get('maxChars', 0)} | "
            f"{b_stats.get('chunkCount', 0)} | {b_stats.get('minChars', 0)}–{b_stats.get('maxChars', 0)} |"
        )
    lines.extend(
        [
            "",
            "## 口径偏差与说明",
            "",
            f"- Dify 自定义分段参数 `max_tokens` 按 token 计，不是字符数；策略 A 使用正文中不出现的哨兵分隔符 `{FIXED_SPLIT_SENTINEL}`，让 Dify 对整个文档递归切分到 500 token 上限，近似“固定 500 字符”，实际字符范围见上表。",
            f"- 策略 B 使用段落分隔 `{display_separator(STRATEGIES['b']['separator'])}`，上限 800 token，重叠 100；同一批资料与 A 使用相同 Embedding 和生成模型。",
            "- 策略 A 在 500 token 上限下可能出现标题行孤立导致的短碎段（本次 3 份资料中《基金会管理条例》最短 5 字符）；S6 评测前需评估是否接受，评测报告不得隐去该偏差。",
            "- 当前资料数量未达到 S2 要求的 20 份，清单如实记录实际数量；来源 URL 未确认前保留“待补录来源”，不得编造。",
            "- 页码字段在 Dify 分段/检索结果中不可用，引用提供文档名、分段序号和摘录；页码缺省。",
            "- `数据/eval/chunks-{a,b}.json` 为逐段切分记录，包含文档名、策略、段落序号和字符数，供 S6 评测使用。",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导入 A/B 知识库并导出切分记录")
    parser.add_argument("--docs-dir", default=str(DEFAULT_DOCS))
    parser.add_argument("--output-dir", default=str(DEFAULT_EVAL))
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--env-file", default=str(REPO_ROOT / ".env"))
    parser.add_argument("--source-map", default=None, help="JSON: {文件名: 来源URL}")
    parser.add_argument("--recreate", action="store_true", help="删除并重建 A/B 数据集")
    parser.add_argument("--limit", type=int, default=0, help="仅处理前 N 份，便于冒烟")
    parser.add_argument("--min-docs", type=int, default=0, help="少于该数量时拒绝导入")
    parser.add_argument("--timeout", type=int, default=900, help="每批索引最长等待秒数")
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = parse_args()
    load_env(Path(args.env_file))
    base_url = os.environ.get("DIFY_BASE_URL", "").strip()
    api_key = os.environ.get("DIFY_DATASET_API_KEY", "").strip()
    if not base_url or not api_key:
        print("缺少 DIFY_BASE_URL 或 DIFY_DATASET_API_KEY", file=sys.stderr)
        return 2

    docs_dir = Path(args.docs_dir)
    output_dir = Path(args.output_dir)
    manifest_path = Path(args.manifest)
    if not docs_dir.is_dir():
        print(f"资料目录不存在: {docs_dir}", file=sys.stderr)
        return 2
    docs = sorted(
        path for path in docs_dir.iterdir()
        if path.is_file() and path.suffix.lower() in SUPPORTED_EXTENSIONS
    )
    if args.limit > 0:
        docs = docs[: args.limit]
    if len(docs) < args.min_docs:
        print(f"资料数量 {len(docs)} 少于 --min-docs {args.min_docs}", file=sys.stderr)
        return 2
    if len(docs) < 20:
        print(f"警告：当前只有 {len(docs)} 份资料，目标为 20–30 份。")

    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    sources: dict[str, str] = {}
    if args.source_map:
        sources = json.loads(Path(args.source_map).read_text(encoding="utf-8"))

    state_path = output_dir / "import-state.json"
    if args.recreate and state_path.exists():
        state_path.unlink()
    state: dict[str, Any] = {"datasets": {}}
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding="utf-8"))

    api = DifyApi(base_url, api_key)
    dataset_ids: dict[str, str] = {}
    docs_by_name_by_key: dict[str, dict[str, dict[str, Any]]] = {}

    for key in ("a", "b"):
        name = DATASET_NAMES[key]
        dataset_id, created = api.ensure_dataset(name, args.recreate)
        dataset_ids[key] = dataset_id
        print(f"数据集 {name}: {dataset_id} ({'新建' if created else '复用'})")
        existing = {doc["name"]: doc for doc in list_documents(api, dataset_id)}
        state_files = state.setdefault("datasets", {}).setdefault(name, {"files": {}}).setdefault("files", {})
        strategy = STRATEGIES[key]

        for path in docs:
            digest = sha256_file(path)
            previous = state_files.get(path.name)
            current = existing.get(path.name)
            if current:
                if previous is None:
                    raise ScriptError(
                        f"{name} 已存在同名文档 {path.name}，但无本地哈希记录；请使用 --recreate 或先清理"
                    )
                if previous.get("sha256") != digest:
                    raise ScriptError(
                        f"{name} 中 {path.name} 内容已变化；请使用 --recreate"
                    )
                print(f"  跳过已存在：{path.name}")
                continue
            document_id, batch_id = api.upload_document(dataset_id, path, strategy)
            state_files[path.name] = {
                "sha256": digest,
                "documentId": document_id,
                "batchId": batch_id,
                "status": "indexing",
            }
            state_path.write_text(
                json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            existing[path.name] = {
                "id": document_id,
                "name": path.name,
                "indexing_status": "waiting",
                "batchId": batch_id,
            }
            print(f"  已上传：{path.name} -> {document_id}")

        for path in docs:
            doc = existing[path.name]
            if doc.get("batchId"):
                api.wait_batch(dataset_id, doc["batchId"], args.timeout)
            completed = api.wait_document(dataset_id, doc["id"], args.timeout)
            state_files[path.name]["status"] = "completed"
            state_files[path.name]["chunkCount"] = completed.get("segment_count")
            state_path.write_text(
                json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        docs_by_name_by_key[key] = existing

    exports: dict[str, Any] = {}
    for key in ("a", "b"):
        exports[key] = export_chunks(
            api,
            key,
            dataset_ids[key],
            docs,
            docs_by_name_by_key[key],
            output_dir,
            args.timeout,
        )
        print(f"已导出：{output_dir / f'chunks-{key}.json'}")

    generated_at = datetime.now(timezone.utc).isoformat()
    write_manifest(
        manifest_path, dataset_ids, docs, exports, sources, generated_at
    )
    print(f"已生成清单：{manifest_path}")
    print("A/B 数据集 ID：")
    for key in ("a", "b"):
        print(f"  {DATASET_NAMES[key]}={dataset_ids[key]}")
    print("提醒：将 B 的 ID 写入 .env 的 DIFY_DEFAULT_DATASET_ID（以及 A/B ID 配置）。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ScriptError as exc:
        print(f"导入失败：{exc}", file=sys.stderr)
        raise SystemExit(1)
