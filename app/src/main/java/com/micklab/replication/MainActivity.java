package com.micklab.replication;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PICK_FILE = 101;

    private EditText editHost, editPort;
    private Button btnConnect, btnUp, btnRefresh, btnUpload, btnDownload, btnNewFolder;
    private TextView txtStatus, txtPath, txtProgress;
    private ListView listFiles;
    private ProgressBar progressBar;
    private View layoutProgress;

    private FileServerClient client;
    private String currentPath = "/";
    private List<FileItem> fileItems = new ArrayList<>();
    private FileAdapter adapter;
    private int selectedPosition = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        setupListeners();
        requestPermissions();
    }

    private void initViews() {
        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnUp = findViewById(R.id.btnUp);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnUpload = findViewById(R.id.btnUpload);
        btnDownload = findViewById(R.id.btnDownload);
        btnNewFolder = findViewById(R.id.btnNewFolder);
        txtStatus = findViewById(R.id.txtStatus);
        txtPath = findViewById(R.id.txtPath);
        txtProgress = findViewById(R.id.txtProgress);
        listFiles = findViewById(R.id.listFiles);
        progressBar = findViewById(R.id.progressBar);
        layoutProgress = findViewById(R.id.layoutProgress);

        adapter = new FileAdapter();
        listFiles.setAdapter(adapter);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> connect());
        btnUp.setOnClickListener(v -> navigateUp());
        btnRefresh.setOnClickListener(v -> loadDirectory(currentPath));
        btnUpload.setOnClickListener(v -> pickFileForUpload());
        btnDownload.setOnClickListener(v -> downloadSelectedFile());
        btnNewFolder.setOnClickListener(v -> showNewFolderDialog());

        listFiles.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = fileItems.get(position);
            if (item.isDirectory) {
                navigateTo(item.name);
            } else {
                selectItem(position);
            }
        });

        listFiles.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemOptions(position);
            return true;
        });
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            List<String> needed = new ArrayList<>();
            for (String p : permissions) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(p);
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
            }
        }
    }

    private void connect() {
        String hostRaw = editHost.getText().toString().trim();
        final String host = hostRaw.isEmpty() ? "localhost" : hostRaw;
        final int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 8080;
        }

        client = new FileServerClient(host, port);
        setStatus("Connecting...");

        executor.execute(() -> {
            boolean available = client.isServerAvailable();
            mainHandler.post(() -> {
                if (available) {
                    setStatus("Connected to " + host + ":" + port);
                    setConnected(true);
                    loadDirectory("/");
                } else {
                    setStatus("Failed to connect");
                    client = null;
                }
            });
        });
    }

    private void setConnected(boolean connected) {
        btnUp.setEnabled(connected);
        btnRefresh.setEnabled(connected);
        btnUpload.setEnabled(connected);
        btnNewFolder.setEnabled(connected);
    }

    private void loadDirectory(String path) {
        if (client == null) return;

        executor.execute(() -> {
            try {
                JSONObject result = client.listDirectory(path);
                JSONArray items = result.getJSONArray("items");
                String actualPath = result.getString("path");

                List<FileItem> newItems = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    newItems.add(new FileItem(
                        item.getString("name"),
                        item.getBoolean("isDirectory"),
                        item.getLong("size"),
                        item.getString("modified")
                    ));
                }

                mainHandler.post(() -> {
                    currentPath = actualPath;
                    txtPath.setText(currentPath);
                    fileItems.clear();
                    fileItems.addAll(newItems);
                    selectedPosition = -1;
                    btnDownload.setEnabled(false);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                mainHandler.post(() -> showError("Failed to load: " + e.getMessage()));
            }
        });
    }

    private void navigateTo(String name) {
        String newPath = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
        loadDirectory(newPath);
    }

    private void navigateUp() {
        if (currentPath.equals("/")) return;
        int lastSlash = currentPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        loadDirectory(parent);
    }

    private void selectItem(int position) {
        selectedPosition = position;
        btnDownload.setEnabled(true);
        adapter.notifyDataSetChanged();
    }

    private void pickFileForUpload() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Select file"), REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadFile(uri);
            }
        }
    }

    private void uploadFile(Uri uri) {
        String fileName = getFileName(uri);
        if (fileName == null) fileName = "uploaded_file";

        String remotePath = currentPath.equals("/") ? "/" + fileName : currentPath + "/" + fileName;

        showProgress(true);
        String finalFileName = fileName;
        executor.execute(() -> {
            File tempFile = null;
            try {
                tempFile = new File(getCacheDir(), finalFileName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }

                File uploadFile = tempFile;
                client.uploadFile(uploadFile, remotePath, (transferred, total) -> {
                    int percent = (int) (transferred * 100 / total);
                    mainHandler.post(() -> updateProgress(percent));
                });

                File finalTempFile = tempFile;
                mainHandler.post(() -> {
                    showProgress(false);
                    showToast("Upload complete: " + finalFileName);
                    loadDirectory(currentPath);
                    if (finalTempFile != null) finalTempFile.delete();
                });
            } catch (Exception e) {
                File finalTempFile = tempFile;
                mainHandler.post(() -> {
                    showProgress(false);
                    showError("Upload failed: " + e.getMessage());
                    if (finalTempFile != null) finalTempFile.delete();
                });
            }
        });
    }

    private void downloadSelectedFile() {
        if (selectedPosition < 0 || selectedPosition >= fileItems.size()) return;
        FileItem item = fileItems.get(selectedPosition);
        if (item.isDirectory) return;

        String remotePath = currentPath.equals("/") ? "/" + item.name : currentPath + "/" + item.name;
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File localFile = new File(downloadDir, item.name);

        showProgress(true);
        executor.execute(() -> {
            try {
                client.downloadFile(remotePath, localFile, (transferred, total) -> {
                    int percent = (int) (transferred * 100 / total);
                    mainHandler.post(() -> updateProgress(percent));
                });

                mainHandler.post(() -> {
                    showProgress(false);
                    showToast("Downloaded: " + localFile.getAbsolutePath());
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    showProgress(false);
                    showError("Download failed: " + e.getMessage());
                });
            }
        });
    }

    private void showNewFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("Folder name");

        new AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    createFolder(name);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void createFolder(String name) {
        String path = currentPath.equals("/") ? "/" + name : currentPath + "/" + name;
        executor.execute(() -> {
            try {
                client.createDirectory(path);
                mainHandler.post(() -> {
                    showToast("Folder created");
                    loadDirectory(currentPath);
                });
            } catch (Exception e) {
                mainHandler.post(() -> showError("Failed: " + e.getMessage()));
            }
        });
    }

    private void showItemOptions(int position) {
        FileItem item = fileItems.get(position);
        String[] options = item.isDirectory ? 
            new String[]{"Open", "Delete"} : 
            new String[]{"Download", "Delete"};

        new AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options, (d, which) -> {
                if (item.isDirectory) {
                    if (which == 0) navigateTo(item.name);
                    else deleteItem(item);
                } else {
                    if (which == 0) {
                        selectItem(position);
                        downloadSelectedFile();
                    } else {
                        deleteItem(item);
                    }
                }
            })
            .show();
    }

    private void deleteItem(FileItem item) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete \"" + item.name + "\"?")
            .setPositiveButton("Delete", (d, w) -> {
                String path = currentPath.equals("/") ? "/" + item.name : currentPath + "/" + item.name;
                executor.execute(() -> {
                    try {
                        client.delete(path);
                        mainHandler.post(() -> {
                            showToast("Deleted");
                            loadDirectory(currentPath);
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> showError("Delete failed: " + e.getMessage()));
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void setStatus(String status) {
        txtStatus.setText(status);
    }

    private void showProgress(boolean show) {
        layoutProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) progressBar.setProgress(0);
    }

    private void updateProgress(int percent) {
        progressBar.setProgress(percent);
        txtProgress.setText(percent + "%");
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    // File item data class
    private static class FileItem {
        String name;
        boolean isDirectory;
        long size;
        String modified;

        FileItem(String name, boolean isDirectory, long size, String modified) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
            this.modified = modified;
        }
    }

    // List adapter
    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() { return fileItems.size(); }

        @Override
        public Object getItem(int position) { return fileItems.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            }

            FileItem item = fileItems.get(position);
            TextView txtIcon = convertView.findViewById(R.id.txtIcon);
            TextView txtName = convertView.findViewById(R.id.txtName);
            TextView txtInfo = convertView.findViewById(R.id.txtInfo);

            txtIcon.setText(item.isDirectory ? "📁" : "📄");
            txtName.setText(item.name);
            
            if (item.isDirectory) {
                txtInfo.setText("Folder");
            } else {
                txtInfo.setText(formatSize(item.size));
            }

            convertView.setBackgroundColor(position == selectedPosition ? 0x330000FF : 0x00000000);
            return convertView;
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            DecimalFormat df = new DecimalFormat("#.##");
            if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
            if (bytes < 1024 * 1024 * 1024) return df.format(bytes / (1024.0 * 1024)) + " MB";
            return df.format(bytes / (1024.0 * 1024 * 1024)) + " GB";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
