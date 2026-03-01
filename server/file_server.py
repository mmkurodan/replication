#!/usr/bin/env python3
"""
HTTP File Server for UserLand Ubuntu
Provides directory listing, file upload/download functionality
"""

import os
import json
import shutil
import tempfile
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote
from datetime import datetime

PORT = 8080
BASE_DIR = os.path.expanduser("~")


class FileServerHandler(BaseHTTPRequestHandler):
    """HTTP request handler for file operations"""

    def send_json_response(self, data, status=200):
        """Send JSON response"""
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def send_error_json(self, message, status=400):
        """Send error as JSON"""
        self.send_json_response({"error": message}, status)

    def get_safe_path(self, path):
        """Validate and return safe absolute path"""
        if not path:
            path = "/"
        path = unquote(path)
        abs_path = os.path.normpath(os.path.join(BASE_DIR, path.lstrip("/")))
        if not abs_path.startswith(BASE_DIR):
            return None
        return abs_path

    def do_OPTIONS(self):
        """Handle CORS preflight"""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        """Handle GET requests"""
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        if path == "/api/list":
            self.handle_list(params)
        elif path == "/api/download":
            self.handle_download(params)
        elif path == "/api/info":
            self.handle_info(params)
        elif path == "/":
            self.send_json_response({
                "service": "FileServer",
                "version": "1.0",
                "endpoints": ["/api/list", "/api/download", "/api/upload", "/api/mkdir", "/api/delete", "/api/info"]
            })
        else:
            self.send_error_json("Unknown endpoint", 404)

    def do_POST(self):
        """Handle POST requests"""
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        if path == "/api/upload":
            self.handle_upload(params)
        elif path == "/api/mkdir":
            self.handle_mkdir(params)
        else:
            self.send_error_json("Unknown endpoint", 404)

    def do_DELETE(self):
        """Handle DELETE requests"""
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        if path == "/api/delete":
            self.handle_delete(params)
        else:
            self.send_error_json("Unknown endpoint", 404)

    def handle_list(self, params):
        """List directory contents"""
        req_path = params.get("path", ["/"])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        if not os.path.exists(abs_path):
            self.send_error_json("Path not found", 404)
            return

        if not os.path.isdir(abs_path):
            self.send_error_json("Not a directory", 400)
            return

        try:
            items = []
            for name in os.listdir(abs_path):
                item_path = os.path.join(abs_path, name)
                try:
                    stat = os.stat(item_path)
                    items.append({
                        "name": name,
                        "isDirectory": os.path.isdir(item_path),
                        "size": stat.st_size if os.path.isfile(item_path) else 0,
                        "modified": datetime.fromtimestamp(stat.st_mtime).isoformat()
                    })
                except (OSError, PermissionError):
                    continue

            items.sort(key=lambda x: (not x["isDirectory"], x["name"].lower()))
            rel_path = os.path.relpath(abs_path, BASE_DIR)
            if rel_path == ".":
                rel_path = "/"
            else:
                rel_path = "/" + rel_path

            self.send_json_response({
                "path": rel_path,
                "items": items
            })
        except PermissionError:
            self.send_error_json("Permission denied", 403)

    def handle_download(self, params):
        """Download a file or directory (as zip)"""
        req_path = params.get("path", [""])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        if not os.path.exists(abs_path):
            self.send_error_json("Path not found", 404)
            return

        temp_archive = None

        try:
            download_path = abs_path
            download_name = os.path.basename(abs_path)

            if os.path.isdir(abs_path):
                dir_name = os.path.basename(abs_path.rstrip(os.sep)) or "archive"
                with tempfile.NamedTemporaryFile(delete=False, suffix=".zip") as temp_file:
                    temp_archive = temp_file.name
                archive_base = os.path.splitext(temp_archive)[0]
                download_path = shutil.make_archive(
                    archive_base,
                    "zip",
                    root_dir=os.path.dirname(abs_path),
                    base_dir=os.path.basename(abs_path)
                )
                download_name = f"{dir_name}.zip"
            elif not os.path.isfile(abs_path):
                self.send_error_json("Not a file or directory", 400)
                return

            file_size = os.path.getsize(download_path)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(file_size))
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            with open(download_path, "rb") as f:
                while chunk := f.read(8192):
                    self.wfile.write(chunk)
        except PermissionError:
            self.send_error_json("Permission denied", 403)
        finally:
            if temp_archive and os.path.exists(temp_archive):
                os.remove(temp_archive)

    def handle_upload(self, params):
        """Upload a file"""
        req_path = params.get("path", [""])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        parent_dir = os.path.dirname(abs_path)
        if not os.path.exists(parent_dir):
            self.send_error_json("Parent directory not found", 404)
            return

        try:
            content_length = int(self.headers.get("Content-Length", 0))
            with open(abs_path, "wb") as f:
                remaining = content_length
                while remaining > 0:
                    chunk_size = min(8192, remaining)
                    chunk = self.rfile.read(chunk_size)
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)

            self.send_json_response({
                "success": True,
                "path": req_path,
                "size": os.path.getsize(abs_path)
            })
        except PermissionError:
            self.send_error_json("Permission denied", 403)

    def handle_mkdir(self, params):
        """Create a directory"""
        req_path = params.get("path", [""])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        try:
            os.makedirs(abs_path, exist_ok=True)
            self.send_json_response({
                "success": True,
                "path": req_path
            })
        except PermissionError:
            self.send_error_json("Permission denied", 403)

    def handle_delete(self, params):
        """Delete a file or directory"""
        req_path = params.get("path", [""])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        if not os.path.exists(abs_path):
            self.send_error_json("Path not found", 404)
            return

        if abs_path == BASE_DIR:
            self.send_error_json("Cannot delete root", 400)
            return

        try:
            if os.path.isdir(abs_path):
                shutil.rmtree(abs_path)
            else:
                os.remove(abs_path)
            self.send_json_response({
                "success": True,
                "path": req_path
            })
        except PermissionError:
            self.send_error_json("Permission denied", 403)

    def handle_info(self, params):
        """Get file/directory info"""
        req_path = params.get("path", ["/"])[0]
        abs_path = self.get_safe_path(req_path)

        if not abs_path:
            self.send_error_json("Invalid path", 400)
            return

        if not os.path.exists(abs_path):
            self.send_error_json("Path not found", 404)
            return

        try:
            stat = os.stat(abs_path)
            self.send_json_response({
                "path": req_path,
                "name": os.path.basename(abs_path) or "/",
                "isDirectory": os.path.isdir(abs_path),
                "size": stat.st_size,
                "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(),
                "readable": os.access(abs_path, os.R_OK),
                "writable": os.access(abs_path, os.W_OK)
            })
        except PermissionError:
            self.send_error_json("Permission denied", 403)


def main():
    server = HTTPServer(("0.0.0.0", PORT), FileServerHandler)
    print(f"File Server started on port {PORT}")
    print(f"Base directory: {BASE_DIR}")
    print(f"Endpoints:")
    print(f"  GET  /api/list?path=<path>     - List directory")
    print(f"  GET  /api/download?path=<path> - Download file/folder")
    print(f"  GET  /api/info?path=<path>     - Get file info")
    print(f"  POST /api/upload?path=<path>   - Upload file")
    print(f"  POST /api/mkdir?path=<path>    - Create directory")
    print(f"  DELETE /api/delete?path=<path> - Delete file/dir")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer stopped.")
        server.server_close()


if __name__ == "__main__":
    main()
