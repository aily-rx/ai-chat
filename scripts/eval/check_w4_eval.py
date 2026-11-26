#!/usr/bin/env python3
"""W4 硬性检查汇总：读取 run_s6_eval.py 的逐题 JSONL，打印可现场核验的指标。"""
import argparse, json, re, sys
from pathlib import Path

PUNCT = re.compile(r"[\s，。；：、！？,.!?;:\"'“”‘’（）()《》【】\[\]—\-]")

def clean(text):
    return PUNCT.sub("", text or "")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", required=True, help="results-b.jsonl 等逐题结果")
    parser.add_argument("--min-hits", type=int, default=7)
    parser.add_argument("--min-refusals", type=int, default=4)
    args = parser.parse_args()

    rows = [json.loads(line) for line in Path(args.results).read_text(encoding="utf-8").splitlines() if line.strip()]
    answerable = [r for r in rows if r.get("type") == "answerable"]
    unanswerable = [r for r in rows if r.get("type") == "unanswerable"]
    non_refusal = [r for r in rows if not r.get("refusal")]

    hits = sum(1 for r in answerable if r.get("autoHit"))
    refusals = sum(1 for r in unanswerable if r.get("refusal"))
    doc_match = sum(1 for r in answerable if r.get("citationDocMatch"))
    fields = sum(1 for r in non_refusal if r.get("citationFieldsComplete"))
    protocol_ok = sum(1 for r in rows if r.get("protocolOk"))
    errors = sum(1 for r in rows if r.get("error"))

    fact_answer = 0
    fact_excerpt = 0
    for r in answerable:
        expected = r.get("expectedFacts", [])
        answer = clean(r.get("answer", ""))
        excerpt = clean(" ".join(c.get("excerpt", "") for c in r.get("citations", [])))
        if expected and all(clean(f) in answer for f in expected):
            fact_answer += 1
        if expected and all(clean(f) in excerpt for f in expected):
            fact_excerpt += 1

    passed = (
        len(rows) == 15
        and len(answerable) == 10
        and len(unanswerable) == 5
        and hits >= args.min_hits
        and refusals >= args.min_refusals
        and fields == len(non_refusal)
        and protocol_ok == len(rows)
        and errors == 0
    )

    print(f"题目总数: {len(rows)}（有答案 {len(answerable)} / 无答案 {len(unanswerable)}）")
    print(f"有答案命中: {hits}/{len(answerable)}（门槛 >= {args.min_hits}）")
    print(f"无答案正确拒答: {refusals}/{len(unanswerable)}（门槛 >= {args.min_refusals}）")
    print(f"引用含预期文档: {doc_match}/{len(answerable)}")
    print(f"非拒答引用字段完整（文件名+段落+摘录）: {fields}/{len(non_refusal)}")
    print(f"回答含预期事实: {fact_answer}/{len(answerable)}")
    print(f"引用摘录含预期事实: {fact_excerpt}/{len(answerable)}")
    print(f"SSE 协议完整: {protocol_ok}/{len(rows)}；请求/协议错误: {errors}")
    print(f"RESULT: {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1

if __name__ == "__main__":
    sys.exit(main())
