package br.com.hocconsultoria.famcont;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int PICK_DRIVE = 42;
    private SharedPreferences prefs;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private String insetJs = "";
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("famcont", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xFFF3F0E8);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF3F0E8);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Respeita barra de status, recorte da câmera e barra de navegação (Android 15 edge-to-edge)
        // Android 15 desenha atrás das barras: passamos as medidas para o app ajustar cabeçalho e menu
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b, ime;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop(); r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom(); ime = b > dp(120) ? b : 0; if (ime > 0) b = 0;
            }
            v.setPadding(l, 0, r, ime);
            float d = getResources().getDisplayMetrics().density;
            insetJs = "document.documentElement.style.setProperty('--sat','" + Math.round(t / d) + "px');"
                    + "document.documentElement.style.setProperty('--sab','" + (ime > 0 ? 0 : Math.round(b / d)) + "px');";
            web.evaluateJavascript(insetJs, null);
            return insets;
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "FamBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { view.evaluateJavascript(insetJs, null); }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                String[] acc = p.getAcceptTypes();
                boolean img = acc != null && acc.length > 0 && acc[0] != null && acc[0].startsWith("image");
                it.setType(img ? "image/*" : "*/*");
                try { startActivityForResult(it, PICK_FILE); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        if (state != null) web.restoreState(state); else web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.famResume&&window.famResume()", null);
    }

    @Override protected void onPause() { web.onPause(); super.onPause(); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_DRIVE) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            String js = "window.famDriveLinked&&window.famDriveLinked(null,false)";
            if (u != null) {
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
                prefs.edit().putString("driveUri", u.toString()).apply();
                js = "window.famDriveLinked&&window.famDriveLinked(" + JSONObject.quote(nameOf(u)) + "," + isDrive(u) + ")";
            }
            web.evaluateJavascript(js, null);
            return;
        }
        if (req == PICK_FILE && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u != null ? new Uri[]{u} : null);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.famBack?window.famBack():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "FAMCONT-backup.json";
    }

    private boolean isDrive(Uri u) { String a = u.getAuthority(); return a != null && a.contains("com.google.android.apps.docs"); }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] b = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FAMCONT");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(b); }
                } else {
                    File d = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(d)) { o.write(b); }
                }
                return "Backup salvo em Downloads/FAMCONT";
            } catch (Exception e) {
                return "Não foi possível salvar: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void share(String title, String text) {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_SUBJECT, title);
            it.putExtra(Intent.EXTRA_TEXT, text);
            runOnUiThread(() -> startActivity(Intent.createChooser(it, title)));
        }

        @JavascriptInterface
        public void linkDrive() {
            runOnUiThread(() -> {
                Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                it.setType("application/json");
                String model = (Build.MODEL == null ? "Android" : Build.MODEL).replaceAll("[^A-Za-z0-9]+", "-");
                it.putExtra(Intent.EXTRA_TITLE, "FAMCONT-backup-" + model + ".json");
                try { startActivityForResult(it, PICK_DRIVE); } catch (Exception e) { web.evaluateJavascript("window.famDriveLinked&&window.famDriveLinked(null,false)", null); }
            });
        }

        @JavascriptInterface
        public String driveInfo() {
            try {
                String s = prefs.getString("driveUri", null);
                JSONObject o = new JSONObject();
                o.put("linked", s != null);
                if (s != null) { Uri u = Uri.parse(s); o.put("name", nameOf(u)); o.put("drive", isDrive(u)); }
                return o.toString();
            } catch (Exception e) { return "{\"linked\":false}"; }
        }

        @JavascriptInterface
        public String writeDrive(String text) {
            String s = prefs.getString("driveUri", null);
            if (s == null) return "sem-vinculo";
            try (OutputStream o = getContentResolver().openOutputStream(Uri.parse(s), "wt")) {
                if (o == null) return "erro: arquivo indisponível";
                o.write(text.getBytes(StandardCharsets.UTF_8));
                return "ok";
            } catch (SecurityException | java.io.FileNotFoundException e) {
                return "perdido";
            } catch (Exception e) {
                return "erro: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void unlinkDrive() {
            String s = prefs.getString("driveUri", null);
            if (s != null) {
                try { getContentResolver().releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
            }
            prefs.edit().remove("driveUri").apply();
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FAMCONT", text));
            });
        }

        @JavascriptInterface
        public void openUrl(String url) { runOnUiThread(() -> openExternal(url)); }
    }
}
