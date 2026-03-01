#!/usr/bin/env bash
set -euo pipefail

SESSION_NAME="replication-server"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_CMD="${PYTHON_CMD:-python3}"
SERVER_SCRIPT="$SCRIPT_DIR/server/file_server.py"
LOG_FILE="$SCRIPT_DIR/server.log"

usage() {
  cat <<EOF
Usage: $0 {start|stop|status|logs}
  start   - start server in detached tmux session ($SESSION_NAME)
  stop    - stop server session
  status  - show if session running
  logs    - show recent log output
EOF
}

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is not installed or not in PATH" >&2
  exit 2
fi

case "${1:-}" in
  start)
    if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
      echo "Session $SESSION_NAME already running"
      exit 0
    fi
    mkdir -p "$(dirname "$LOG_FILE")"
    tmux new-session -d -s "$SESSION_NAME" "bash -lc 'exec \"$PYTHON_CMD\" \"$SERVER_SCRIPT\" >> \"$LOG_FILE\" 2>&1'"
    echo "Started server in tmux session: $SESSION_NAME (logs: $LOG_FILE)"
    ;;
  stop)
    if ! tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
      echo "Session $SESSION_NAME not found"
      exit 0
    fi
    tmux send-keys -t "$SESSION_NAME" C-c
    sleep 1
    tmux kill-session -t "$SESSION_NAME"
    echo "Stopped server session: $SESSION_NAME"
    ;;
  status)
    if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
      echo "Session $SESSION_NAME is running"
      tmux list-panes -t "$SESSION_NAME"
    else
      echo "Session $SESSION_NAME not running"
      exit 1
    fi
    ;;
  logs)
    if [ -f "$LOG_FILE" ]; then
      tail -n 200 "$LOG_FILE"
    else
      echo "Log file not found: $LOG_FILE"
      exit 1
    fi
    ;;
  *)
    usage
    exit 2
    ;;
esac
