package com.micklab.replication;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * HTTP client for file server communication
 */
public class FileServerClient {
    private final String baseUrl;
    private static final int TIMEOUT = 30000;
    private static final int BUFFER_SIZE = 8192;

    public interface ProgressListener {
        void onProgress(long bytesTransferred, long totalBytes);
    }

    public FileServerClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
    }

    /**
     * List directory contents
     */
    public JSONObject listDirectory(String path) throws IOException {
        String url = baseUrl + "/api/list?path=" + URLEncoder.encode(path, "UTF-8");
        return getJson(url);
    }

    /**
     * Get file/directory info
     */
    public JSONObject getInfo(String path) throws IOException {
        String url = baseUrl + "/api/info?path=" + URLEncoder.encode(path, "UTF-8");
        return getJson(url);
    }

    /**
     * Download file from server
     */
    public void downloadFile(String remotePath, File localFile, ProgressListener listener) throws IOException {
        String url = baseUrl + "/api/download?path=" + URLEncoder.encode(remotePath, "UTF-8");
        HttpURLConnection conn = null;
        
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned: " + responseCode);
            }

            long totalBytes = conn.getContentLengthLong();
            long bytesRead = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(localFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    bytesRead += len;
                    if (listener != null) {
                        listener.onProgress(bytesRead, totalBytes);
                    }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Upload file to server
     */
    public JSONObject uploadFile(File localFile, String remotePath, ProgressListener listener) throws IOException {
        String url = baseUrl + "/api/upload?path=" + URLEncoder.encode(remotePath, "UTF-8");
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Content-Length", String.valueOf(localFile.length()));
            conn.setFixedLengthStreamingMode(localFile.length());

            long totalBytes = localFile.length();
            long bytesWritten = 0;

            try (FileInputStream in = new FileInputStream(localFile);
                 OutputStream out = conn.getOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    bytesWritten += len;
                    if (listener != null) {
                        listener.onProgress(bytesWritten, totalBytes);
                    }
                }
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned: " + responseCode);
            }

            return readJsonResponse(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Create directory on server
     */
    public JSONObject createDirectory(String path) throws IOException {
        String url = baseUrl + "/api/mkdir?path=" + URLEncoder.encode(path, "UTF-8");
        return postEmpty(url);
    }

    /**
     * Delete file or directory on server
     */
    public JSONObject delete(String path) throws IOException {
        String url = baseUrl + "/api/delete?path=" + URLEncoder.encode(path, "UTF-8");
        return deleteRequest(url);
    }

    /**
     * Check if server is available
     */
    public boolean isServerAvailable() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject getJson(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(conn);
                throw new IOException("Server error: " + error);
            }

            return readJsonResponse(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject postEmpty(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setFixedLengthStreamingMode(0);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(conn);
                throw new IOException("Server error: " + error);
            }

            return readJsonResponse(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject deleteRequest(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readErrorResponse(conn);
                throw new IOException("Server error: " + error);
            }

            return readJsonResponse(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject readJsonResponse(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            throw new IOException("Failed to parse response: " + e.getMessage());
        }
    }

    private String readErrorResponse(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            if (in == null) return "Unknown error";
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "Unknown error";
        }
    }
}
