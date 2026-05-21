#!/bin/bash
# Start the file server
cd "$(dirname "$0")"
export FILE_SERVER_PORT="${FILE_SERVER_PORT:-9000}"
echo "Starting file server on port ${FILE_SERVER_PORT}"
python3 file_server.py
