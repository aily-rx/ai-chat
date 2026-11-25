#!/usr/bin/env python3
"""S6 A/B RAG evaluation runner. Raw SSE and JSONL stay on disk; stdout is compact."""
import argparse, json, re, time, urllib.error, urllib.request
from pathlib import Path

URL = "http://localhost:8080/api/rag/chat/stream"
MARKERS = ["资料不足", "无法回答该问题", "无法回答您的问题", "未找到相关", "没有找到相关", "无法回答"]
PUNCT = re.compile(r"[\s，。；：、！？,.!?;:\"'“”‘’（）()《》【】\[\]—\-]")

def clean(text):
    return PUNCT.sub("", text or "")

def parse_block(lines):
    name, data = None, []
    for line in lines:
        if line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].strip())
    if not name:
        return None
    raw = "\n".join(data)
    try:
        return name, json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return name, {"_unparsed": raw}

def call(question, dataset):
    body = json.dumps({"question": question, "datasetId": dataset}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(URL, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=UTF-8",
        "Accept": "text/event-stream, application/json",
    })
    started = time.perf_counter()
    answer, citations, done, error, order, buf, raw_lines = [], [], None, None, [], [], []
    first_ms = None
    with urllib.request.urlopen(req, timeout=120) as resp:
        status = resp.status
        for raw_line in resp:
            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
            raw_lines.append(line)
            if line:
                buf.append(line)
                continue
            event = parse_block(buf)
            buf = []
            if not event:
                continue
            name, payload = event
            order.append(name)
            if name == "message":
                delta = payload.get("delta", "")
                if delta and first_ms is None:
                    first_ms = round((time.perf_counter() - started) * 1000)
                answer.append(delta)
            elif name == "citations":
                citations = payload.get("items", [])
            elif name == "done":
                done = payload
            elif name == "error":
                error = payload
    total_ms = round((time.perf_counter() - started) * 1000)
    citations_received = "citations" in order
    refusal = bool(done and citations_received and len(citations) == 0 and not error)
    return {
        "http": status, "answer": "".join(answer), "citations": citations,
        "citationCount": len(citations), "citationDocs": sorted({c.get("docName", "") for c in citations}),
        "done": done, "error": error, "eventOrder": order, "rawSse": "\n".join(raw_lines),
        "citationsReceived": citations_received, "refusal": refusal,
        "refusalMarker": any(m in clean("".join(answer)) for m in MARKERS),
        "firstTokenMs": first_ms, "totalMs": total_ms,
    }

def failed(exception):
    return {
        "http": None, "answer": "", "citations": [], "citationCount": 0, "citationDocs": [],
        "done": None, "error": exception, "eventOrder": [], "rawSse": "", "citationsReceived": False,
        "refusal": False, "refusalMarker": False, "firstTokenMs": None, "totalMs": None,
    }

def score(record, question):
    expected = question.get("expectedFacts", [])
    answer_compact = clean(record["answer"])
    facts = [f for f in expected if clean(f) in answer_compact]
    doc_match = any(c.get("docName") == question.get("expectedDoc") for c in record["citations"])
    fields_ok = all(c.get("docName") and c.get("segmentIndex") is not None and c.get("excerpt") for c in record["citations"])
    hit = (len(facts) == len(expected) and doc_match) if question["type"] == "answerable" else record["refusal"]
    return {"autoHit": hit, "factHits": facts, "factsExpected": expected,
            "citationDocMatch": doc_match, "citationFieldsComplete": fields_ok}

def run(args):
    questions = json.loads(Path(args.questions).read_text(encoding="utf-8"))
    if args.ids:
        wanted = set(args.ids.split(","))
        questions = [q for q in questions if q["id"] in wanted]
    libs = [args.dataset.upper()] if args.dataset.upper() in ("A", "B") else ["A", "B"]
    outdir = Path(args.outdir)
    rawdir = outdir / "raw"
    outdir.mkdir(parents=True, exist_ok=True)
    for lib in libs:
        records = []
        print(f"=== dataset {lib} ===", flush=True)
        for q in questions:
            record = {"lib": lib, "id": q["id"], "type": q["type"], "question": q["question"],
                      "expectedDoc": q.get("expectedDoc", ""), "expectedFacts": q.get("expectedFacts", [])}
            attempts = 0
            while True:
                attempts += 1
                try:
                    record.update(call(q["question"], lib))
                    break
                except urllib.error.HTTPError as exc:
                    body = exc.read().decode("utf-8", errors="replace")[:300]
                    if (exc.code == 429 or exc.code >= 500) and attempts <= 2:
                        time.sleep(5 * attempts)
                        continue
                    record.update(failed({"http": exc.code, "httpBody": body}))
                    break
                except Exception as exc:
                    if attempts <= 2:
                        time.sleep(5 * attempts)
                        continue
                    record.update(failed({"transport": str(exc)}))
                    break
            record["attempts"] = attempts
            record["protocolOk"] = bool(record.get("done") and record.get("citationsReceived") and not record.get("error"))
            record.update(score(record, q))
            records.append(record)
            rawdir.joinpath(lib).mkdir(parents=True, exist_ok=True)
            rawdir.joinpath(lib, f"{q['id']}.sse").write_text(record.pop("rawSse"), encoding="utf-8")
            print(f"{lib} {q['id']} http={record.get('http')} totalMs={record.get('totalMs')} cites={record['citationCount']} "
                  f"refusal={record['refusal']} autoHit={record['autoHit']} answer={record['answer'][:50]!r}", flush=True)
            time.sleep(args.pause)
        path = outdir / f"results-{lib.lower()}.jsonl"
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records), encoding="utf-8")
        answerable = [r for r in records if r["type"] == "answerable"]
        unanswerable = [r for r in records if r["type"] == "unanswerable"]
        non_refusal = [r for r in records if not r["refusal"]]
        firsts = [r["firstTokenMs"] for r in records if r["firstTokenMs"]]
        totals = [r["totalMs"] for r in records if r["totalMs"]]
        summary = {
            "lib": lib, "questions": len(records),
            "answerable": len(answerable), "autoHits": sum(1 for r in answerable if r["autoHit"]),
            "unanswerable": len(unanswerable), "refusals": sum(1 for r in unanswerable if r["refusal"]),
            "citationComplete": sum(1 for r in non_refusal if r["citationCount"] > 0),
            "nonRefusal": len(non_refusal),
            "protocolOk": sum(1 for r in records if r["protocolOk"]),
            "errors": sum(1 for r in records if r.get("error")),
            "avgFirstTokenMs": round(sum(firsts) / max(1, len(firsts))),
            "avgTotalMs": round(sum(totals) / max(1, len(totals))),
        }
        (outdir / f"summary-{lib.lower()}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("SUMMARY", json.dumps(summary, ensure_ascii=False), flush=True)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default="both")
    ap.add_argument("--ids", default="")
    ap.add_argument("--questions", default="data/eval/questions.json")
    ap.add_argument("--outdir", default="data/eval/s6/round1")
    ap.add_argument("--pause", type=float, default=1.0)
    run(ap.parse_args())