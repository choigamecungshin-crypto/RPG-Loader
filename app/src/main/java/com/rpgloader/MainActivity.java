package com.rpgloader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import net.perfectdreams.butterscotch.android.GameActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new WebAppInterface(this), "Android");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    public void selectFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION 
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                String jsonResult = scanSubtreeForDataWin(treeUri);
                webView.post(() -> webView.evaluateJavascript("onFolderScanned(" + jsonResult + ")", null));
            }
        }
    }

    private String scanSubtreeForDataWin(Uri treeUri) {
        JSONArray results = new JSONArray();
        DocumentFile rootDir = DocumentFile.fromTreeUri(this, treeUri);
        if (rootDir != null && rootDir.exists()) {
            scanRecursive(rootDir, results);
        }
        return results.toString();
    }

    private void scanRecursive(DocumentFile dir, JSONArray results) {
        if (dir == null || !dir.isDirectory()) return;
        DocumentFile[] files = dir.listFiles();
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                scanRecursive(file, results);
            } else if (file.isFile() && file.getName() != null) {
                if (file.getName().equalsIgnoreCase("data.win")) {
                    try {
                        JSONObject gameObj = new JSONObject();
                        gameObj.put("uri", file.getUri().toString());
                        gameObj.put("name", file.getName());
                        String parentName = dir.getName();
                        gameObj.put("title", (parentName != null && !parentName.isEmpty()) ? parentName : "Game");
                        gameObj.put("size", file.length());
                        results.put(gameObj);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public class WebAppInterface {
        private final Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void openFolderPicker() {
            selectFolder();
        }

        @JavascriptInterface
        public void launchGameFromUri(String uriString, String title) {
            try {
                Uri contentUri = Uri.parse(uriString);
                File localDataWin = copyUriToPrivateStorage(contentUri, uriString);
                if (localDataWin == null || !localDataWin.exists()) {
                    runOnUiThread(() -> Toast.makeText(mContext, "Lỗi copy data.win sang app storage", Toast.LENGTH_LONG).show());
                    return;
                }

                Intent intent = new Intent(mContext, GameActivity.class);
                intent.putExtra("EXTRA_DATA_WIN_PATH", localDataWin.getAbsolutePath());
                intent.putExtra("EXTRA_GAME_TITLE", title != null ? title : "Game");
                mContext.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(mContext, "Lỗi launch game: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        private File copyUriToPrivateStorage(Uri uri, String keySource) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(keySource.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                String folderHash = sb.toString();

                File gamesDir = new File(mContext.getFilesDir(), "games/" + folderHash);
                if (!gamesDir.exists()) {
                    gamesDir.mkdirs();
                }
                File destFile = new File(gamesDir, "data.win");

                if (destFile.exists() && destFile.length() > 0) {
                    return destFile;
                }

                InputStream in = mContext.getContentResolver().openInputStream(uri);
                if (in == null) return null;

                OutputStream out = new FileOutputStream(destFile);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                out.close();
                in.close();

                return destFile;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
}
