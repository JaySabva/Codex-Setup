#!/usr/bin/env bash
set -euo pipefail

java -jar "/Users/ontic/.codex/codex-index-generator.jar"

# ------------------ CONFIG (edit these) ------------------
JSON_FILE_PATH="/Users/ontic/.codex/codex_sessions_index.json"
PAGE_SIZE=10
CODEX_BIN="/opt/homebrew/bin/codex"   # codex binary path
# ---------------------------------------------------------
# --- flags ---
DISABLE_BRANCH_FILTER=false
while getopts ":f" opt; do
  case "$opt" in
    f) DISABLE_BRANCH_FILTER=true ;;
    *) echo "Usage: $0 [-f]"; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "Error: jq is required."; exit 1; }

CALL_DIR="$(pwd -P 2>/dev/null || pwd)"
CWD="$CALL_DIR"

# --- branch detection ---
detect_branch() {
  if command -v git >/dev/null 2>&1; then
    if git -C "$CWD" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      local b
      b="$(git -C "$CWD" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
      if [[ -n "$b" && "$b" != "HEAD" ]]; then printf "%s" "$b"; return 0; fi
      local sha
      sha="$(git -C "$CWD" rev-parse --short HEAD 2>/dev/null || true)"
      if [[ -n "$sha" ]]; then printf "%s" "$sha"; return 0; fi
    fi
  fi
  printf ""
}

if $DISABLE_BRANCH_FILTER; then
  BRANCH_FILTER=""
else
  BRANCH_FILTER="$(detect_branch)"
fi

[[ -f "$JSON_FILE_PATH" ]] || { echo "Error: JSON not found -> $JSON_FILE_PATH"; exit 1; }

lower() { printf "%s" "$1" | tr '[:upper:]' '[:lower:]'; }
BF_LOWER="$(lower "$BRANCH_FILTER")"

# --- load + flatten sessions ---
load_sessions() {
  local cwd="$1"
  jq -c --arg cwd "$cwd" '
    .[$cwd] // empty
    | to_entries[]
    | .key as $branch
    | .value[]
    | {
        branch:$branch,
        time:(.time // ""),
        sessionId:(.sessionId // ""),
        file:(.file // ""),
        firstUserMessage:(.firstUserMessage // ""),
        jsonlFilePath:(.jsonlFilePath // "")
      }
  ' "$JSON_FILE_PATH" || true
}

refresh_sessions() {
  java -jar "/Users/ontic/.codex/generate_codex_session_index.jar" || true
  SESSIONS_JSON=$(load_sessions "$CWD")
  if [[ -z "${SESSIONS_JSON:-}" ]]; then
    SESSIONS_SORTED='[]'
    COUNT=0
    total_pages=1
  else
    SESSIONS_SORTED=$(
      printf "%s\n" "$SESSIONS_JSON" |
      jq -cs --arg bf "$BF_LOWER" '
        map(select(.sessionId != "")) |
        (if ($bf // "") == "" then . else map(select((.branch // "" | ascii_downcase) == $bf)) end) |
        sort_by(.time) | reverse
      '
    )
    COUNT=$(jq 'length' <<<"$SESSIONS_SORTED")
    total_pages=$(( (COUNT + PAGE_SIZE - 1) / PAGE_SIZE ))
    (( total_pages == 0 )) && total_pages=1
  fi
  PAGE=0
}

SESSIONS_JSON=$(load_sessions "$CWD")
if [[ -z "${SESSIONS_JSON:-}" ]]; then
  SESSIONS_SORTED='[]'
else
  SESSIONS_SORTED=$(
    printf "%s\n" "$SESSIONS_JSON" |
    jq -cs --arg bf "$BF_LOWER" '
      map(select(.sessionId != "")) |
      (if ($bf // "") == "" then . else map(select((.branch // "" | ascii_downcase) == $bf)) end) |
      sort_by(.time) | reverse
    '
  )
fi
COUNT=$(jq 'length' <<<"$SESSIONS_SORTED")

echo "Directory: $CWD"
if $DISABLE_BRANCH_FILTER; then
  echo "Branch filter disabled (-f) — showing all branches."
elif [[ -n "$BRANCH_FILTER" ]]; then
  echo "Filtering by branch: $BRANCH_FILTER"
else
  echo "No git branch detected — showing all branches."
fi

PAGE=0
total_pages=$(( (COUNT + PAGE_SIZE - 1) / PAGE_SIZE ))
(( total_pages == 0 )) && total_pages=1

# --- helpers ---
one_line() { printf "%s" "$1" | tr '\r\n' '  ' | awk '{$1=$1; print}'; }
truncate60() { local s="$1"; local max=60; (( ${#s} > max )) && printf "%s…\n" "${s:0:59}" || printf "%s\n" "$s"; }
short_id() { local id="$1"; (( ${#id} > 12 )) && printf "%s…\n" "${id:0:12}" || printf "%s\n" "$id"; }

run_codex_new() {
  echo "Starting a new Codex session..."
  if [[ -x "$CODEX_BIN" ]]; then
    "$CODEX_BIN" || echo "codex exited with non-zero status."
  else
    echo "Failed to run 'codex'. Expected at: $CODEX_BIN"
  fi
}

delete_session() {
  local sid="$1"
  local path
  path=$(jq -r --arg sid "$sid" '.[] | select(.sessionId == $sid) | .jsonlFilePath' <<<"$SESSIONS_SORTED" | head -n 1)
  if [[ -z "$path" || "$path" == "null" ]]; then
    echo "Could not locate file for session $sid."
    return
  fi
  echo "About to delete session:"
  echo "  $path"
  printf "Are you sure? (y/N): "
  read -r confirm
  if [[ "$(lower "$confirm")" == "y" ]]; then
    if [[ -f "$path" ]]; then
      rm -f "$path"
      echo "Deleted $path"
    else
      echo "File not found: $path"
    fi
    refresh_sessions
  else
    echo "Cancelled."
  fi
}

render_page() {
  local page=$1
  local from=$(( page * PAGE_SIZE ))
  local to=$(( from + PAGE_SIZE ))
  (( to > COUNT )) && to=$COUNT

  echo
  echo "Sessions for this directory  [Page $((page+1)) / $total_pages]"
  echo "---------------------------------------------------------------------"
  echo "  0) [NEW] Create a new Codex session"
  if (( from >= to )); then
    echo "     (no existing sessions)"
  else
    jq -r --argjson from "$from" --argjson to "$to" '
      .[$from:$to][]
      | [.branch, .time, .sessionId, .file, .firstUserMessage] | @tsv
    ' <<<"$SESSIONS_SORTED" |
    nl -w2 -s$'\t' |
    while IFS=$'\t' read -r idx branch time sid file fum; do
      preview=$(truncate60 "$(one_line "${fum:-}")")
      printf " %2d) [%s] %s\n    %s\n" "$idx" "${branch:-}" "${time:-}" "$preview"
    done
  fi
  echo "---------------------------------------------------------------------"
  echo "Select 0=new, number=resume, d<number>=delete, or n/p/q"
}

# --- main loop ---
while true; do
  render_page "$PAGE"
  printf "> "
  IFS= read -r input || break
  input_trimmed="${input//[[:space:]]/}"
  input_lc="$(lower "$input_trimmed")"

  case "$input_lc" in
    q) break ;;
    n)
      if (( PAGE + 1 < total_pages )); then ((PAGE++)); else echo "(already on last page)"; fi ;;
    p)
      if (( PAGE > 0 )); then ((PAGE--)); else echo "(already on first page)"; fi ;;
    0|new|c)
      run_codex_new
      refresh_sessions ;;
    d*)
      num="${input_lc#d}"
      if [[ "$num" =~ ^[0-9]+$ ]]; then
        idx=$(( PAGE * PAGE_SIZE + num - 1 ))
        if (( idx < 0 || idx >= COUNT )); then
          echo "Invalid selection."
        else
          sid=$(jq -r --argjson idx "$idx" '.[$idx].sessionId' <<<"$SESSIONS_SORTED")
          delete_session "$sid"
        fi
      else
        echo "Usage: d<number> (e.g., d2 to delete item 2)"
      fi
      ;;
    *)
      if [[ "$input_lc" =~ ^[0-9]+$ ]]; then
        sel=$(( input_lc ))
        if (( sel <= 0 )); then echo "Use 0 for NEW."; continue; fi
        idx=$(( PAGE * PAGE_SIZE + sel - 1 ))
        if (( idx < 0 || idx >= COUNT )); then echo "Invalid selection."; continue; fi
        sid=$(jq -r --argjson idx "$idx" '.[$idx].sessionId' <<<"$SESSIONS_SORTED")
        branch=$(jq -r --argjson idx "$idx" '.[$idx].branch' <<<"$SESSIONS_SORTED")
        echo "Resuming session: $sid (branch: $branch)"
        if [[ -x "$CODEX_BIN" ]]; then
          "$CODEX_BIN" resume "$sid" || echo "codex exited with non-zero status."
        else
          echo "Failed to run 'codex'. Expected at: $CODEX_BIN"
        fi
      else
        echo "Unknown command. Use 0/new/c, a number, d<number>, n/p, or q."
      fi
      ;;
  esac
done
