import json
import os
import sys
import time

log_path = os.environ.get("MCTRANSLATOR_FAKE_LOG", "")
early_turn = os.environ.get("MCTRANSLATOR_FAKE_EARLY_TURN", "") == "1"
completed_first = os.environ.get("MCTRANSLATOR_FAKE_COMPLETED_FIRST", "") == "1"
signed_in = True
turn_number = 0

if log_path:
    with open(log_path, "w", encoding="utf-8"):
        pass

def log(kind, value):
    if not log_path:
        return
    with open(log_path, "a", encoding="utf-8") as stream:
        stream.write(json.dumps({"kind": kind, kind: value}, ensure_ascii=False) + "\n")

def send(value):
    sys.stdout.write(json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n")
    sys.stdout.flush()

log("argv", sys.argv[1:])
if "--version" in sys.argv[1:]:
    print("codex-fake 1.0.4")
    raise SystemExit(0)

for raw in sys.stdin:
    try:
        request = json.loads(raw)
    except Exception:
        continue
    log("request", request)
    method = request.get("method", "")
    request_id = request.get("id")
    params = request.get("params") or {}
    if method == "initialized":
        continue
    if method == "initialize":
        result = {}
    elif method == "account/read":
        result = {"account": {"type": "chatgpt", "email": "inline@example.test", "planType": "plus"}} if signed_in else {"account": None}
    elif method == "account/login/start":
        signed_in = True
        result = {"loginId": "login-inline", "authUrl": "https://example.test/login"}
    elif method == "account/logout":
        signed_in = False
        result = {}
    elif method == "model/list":
        if params.get("cursor") == "page-2":
            result = {"data": [{
                "model": "gpt-5.6-sol",
                "displayName": "GPT-5.6 Sol",
                "supportedReasoningEfforts": [{"reasoningEffort": "low"}, {"reasoningEffort": "high"}],
                "serviceTiers": [{"id": "default"}],
                "defaultReasoningEffort": "low",
                "isDefault": False
            }], "nextCursor": None}
        else:
            result = {"data": [{
                "model": "gpt-5.6-terra",
                "displayName": "GPT-5.6 Terra",
                "supportedReasoningEfforts": [{"reasoningEffort": "low"}, {"reasoningEffort": "medium"}, {"reasoningEffort": "high"}],
                "serviceTiers": [{"id": "default"}, {"id": "priority"}],
                "defaultReasoningEffort": "medium",
                "isDefault": True
            }], "nextCursor": "page-2"}
    elif method == "thread/start":
        result = {"thread": {"id": "thread-inline"}}
    elif method == "turn/start":
        turn_number += 1
        turn_id = "turn-inline-" + str(turn_number)
        result = {"turn": {"id": turn_id}}
    elif method == "thread/unsubscribe":
        result = {}
    else:
        result = {}
    defer_turn_response = early_turn and method == "turn/start" and request_id is not None
    if request_id is not None and not defer_turn_response:
        send({"id": request_id, "result": result})
    if method == "account/login/start":
        send({"method": "account/login/completed", "params": {"loginId": "login-inline", "success": True}})
    elif method == "turn/start":
        item_completed = {"method": "item/completed", "params": {
            "turnId": turn_id,
            "item": {"type": "agentMessage", "text": json.dumps({"translation": "帶有箱子的花船"}, ensure_ascii=False)}
        }}
        turn_completed = {"method": "turn/completed", "params": {
            "turn": {"id": turn_id, "status": "completed"}
        }}
        if completed_first:
            send(turn_completed)
            time.sleep(0.12)
            send(item_completed)
        else:
            send(item_completed)
        send({"method": "thread/tokenUsage/updated", "params": {
            "threadId": "thread-inline",
            "tokenUsage": {"total": {"inputTokens": 10, "cachedInputTokens": 3, "outputTokens": 4, "reasoningOutputTokens": 1, "totalTokens": 14}}
        }})
        if not completed_first:
            send(turn_completed)
        if defer_turn_response:
            send({"id": request_id, "result": result})
