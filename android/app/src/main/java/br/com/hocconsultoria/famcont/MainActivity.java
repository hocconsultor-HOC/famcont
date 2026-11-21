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
import android.os.CancellationSignal;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
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
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // ===== Atualização automática do app (sem baixar APK): baixa a versão nova da tela e usa na próxima abertura =====
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final String REMOTE_URL = "https://raw.githubusercontent.com/hocconsultor-HOC/famcont/main/web/index.html";
    // API do GitHub: entrega o arquivo novo na hora (o endereço raw guarda cópia por até 5 minutos)
    private static final String API_URL = "https://api.github.com/repos/hocconsultor-HOC/famcont/contents/web/index.html?ref=main";
    private static final String MARKER = "FAMCONT-APP";
    private int bundledBuild = -1;
    private volatile int runningBuild = -1;
    private volatile boolean servedCached = false;
    private volatile int okBuild = -1;
    private long lastCheck = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File webFile() { return new File(getFilesDir(), "www/index.html"); }
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toByteArray();
    }
    private static int buildOf(String html) { Matcher m = Pattern.compile("version:'([0-9.]+)',build:(\\d+)").matcher(html); return m.find() ? Integer.parseInt(m.group(2)) : -1; }
    private static String versionOf(String html) { Matcher m = Pattern.compile("version:'([0-9.]+)',build:(\\d+)").matcher(html); return m.find() ? m.group(1) : ""; }
    private int bundled() {
        if (bundledBuild < 0) { try (InputStream in = getAssets().open("index.html")) { bundledBuild = buildOf(new String(readAll(in), StandardCharsets.UTF_8)); } catch (Exception e) { bundledBuild = 0; } }
        return bundledBuild;
    }
    private void checkWebUpdate(boolean force) {
        long t = System.currentTimeMillis();
        if (!force && t - lastCheck < 30 * 60 * 1000) return;
        lastCheck = t;
        new Thread(() -> {
            try {
                byte[] data = fetch(API_URL, true);
                if (data == null) data = fetch(REMOTE_URL + "?t=" + t, false);
                if (data == null) return;
                String html = new String(data, StandardCharsets.UTF_8);
                int b = buildOf(html);
                if (b > Math.max(runningBuild, bundled()) && b != prefs.getInt("badBuild", -1) && html.contains(MARKER) && html.trim().endsWith("</html>")) {
                    File f = webFile(); File dir = f.getParentFile(); if (dir != null) dir.mkdirs();
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
                    if (!tmp.renameTo(f)) return;
                    String v = versionOf(html);
                    ui.post(() -> web.evaluateJavascript("window.famWebUpdate&&window.famWebUpdate(" + JSONObject.quote(v) + ")", null));
                }
            } catch (Exception ignored) { }
        }).start();
    }
    private static byte[] fetch(String url, boolean api) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(20000); c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (api) { c.setRequestProperty("Accept", "application/vnd.github.raw"); c.setRequestProperty("User-Agent", "CONTAS-app"); }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Exception e) { return null; }
    }
    // Abre a tela: a versão baixada (se for mais nova e válida) ou a que veio instalada.
    // Usa o MESMO endereço da versão instalada, então os dados do aparelho continuam os mesmos.
    private void loadApp() {
        servedCached = false; runningBuild = bundled();
        try {
            File f = webFile();
            if (f.exists()) {
                byte[] data; try (InputStream in = new java.io.FileInputStream(f)) { data = readAll(in); }
                String html = new String(data, StandardCharsets.UTF_8);
                int b = buildOf(html);
                if (b > bundled() && html.contains(MARKER) && html.trim().endsWith("</html>")) {
                    runningBuild = b; servedCached = true; okBuild = -1;
                    web.loadDataWithBaseURL(ASSET_URL, html, "text/html", "utf-8", ASSET_URL);
                    final int expected = b;
                    // rede de segurança: se a versão baixada não abrir direito em 12 s, volta para a instalada
                    ui.postDelayed(() -> { if (servedCached && runningBuild == expected && okBuild != expected) rollback(); }, 12000);
                    return;
                }
                f.delete(); // versão antiga ou inválida: descarta
            }
        } catch (Exception ignored) { }
        web.loadUrl(ASSET_URL);
    }
    private void rollback() { prefs.edit().putInt("badBuild", runningBuild).apply(); webFile().delete(); servedCached = false; runningBuild = bundled(); web.loadUrl(ASSET_URL); }

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
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(insetJs, null);
                checkWebUpdate(false);
            }
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
        loadApp();
    }


    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.famResume&&window.famResume()", null);
        checkWebUpdate(false);
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
        return "CONTAS-backup.json";
    }

    private void googleResult(String token, String err) {
        String js = "window.famGoogle&&window.famGoogle(" + (token == null ? "null" : JSONObject.quote(token)) + "," + (err == null ? "null" : JSONObject.quote(err)) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
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
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CONTAS");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(b); }
                } else {
                    File d = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(d)) { o.write(b); }
                }
                return "Backup salvo em Downloads/CONTAS";
            } catch (Exception e) {
                return "Não foi possível salvar: " + e.getMessage();
            }
        }

        // Salva qualquer arquivo (ex.: PDF do resumo) em Downloads/CONTAS e já abre para ver ou compartilhar
        @JavascriptInterface
        public String saveBytes(String name, String mime, String b64) {
            try {
                byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CONTAS");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) return "Não foi possível salvar o arquivo";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(b); }
                    final Uri fu = u;
                    runOnUiThread(() -> {
                        try {
                            Intent it = new Intent(Intent.ACTION_VIEW);
                            it.setDataAndType(fu, mime);
                            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(Intent.createChooser(it, "Abrir " + name));
                        } catch (Exception ignored) { }
                    });
                    return "PDF salvo em Downloads/CONTAS";
                } else {
                    File d = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                    try (FileOutputStream o = new FileOutputStream(d)) { o.write(b); }
                    return "PDF salvo em Android/data/br.com.hocconsultoria.famcont/files/Download";
                }
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
        public int nativeBuild() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception e) { return -1; }
        }

        @JavascriptInterface
        public void webOk(int build) { okBuild = build; }

        // rede de segurança dos dados: se a versão baixada não enxergar os dados que o aparelho já tinha, volta para a instalada
        @JavascriptInterface
        public void storeOk(boolean has) {
            if (has) { if (!prefs.getBoolean("hadData", false)) prefs.edit().putBoolean("hadData", true).apply(); }
            else if (!servedCached) prefs.edit().putBoolean("hadData", false).apply(); // aparelho zerado de propósito
            else if (prefs.getBoolean("hadData", false)) ui.post(MainActivity.this::rollback);
        }

        @JavascriptInterface
        public int runningBuild() { return runningBuild; }

        @JavascriptInterface
        public void reloadApp() { ui.post(MainActivity.this::loadApp); }

        @JavascriptInterface
        public void checkUpdate() { checkWebUpdate(true); }

        @JavascriptInterface
        public void googleSignIn(String webClientId) {
            runOnUiThread(() -> {
                try {
                    CredentialManager cm = CredentialManager.create(MainActivity.this);
                    GetSignInWithGoogleOption opt = new GetSignInWithGoogleOption.Builder(webClientId).build();
                    GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(opt).build();
                    cm.getCredentialAsync(MainActivity.this, req, new CancellationSignal(), command -> runOnUiThread(command),
                        new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                            @Override
                            public void onResult(GetCredentialResponse r) {
                                Credential c = r.getCredential();
                                if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                    try {
                                        GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData());
                                        googleResult(g.getIdToken(), null);
                                    } catch (Exception e) { googleResult(null, "Resposta do Google inválida."); }
                                } else googleResult(null, "Tipo de credencial inesperado.");
                            }
                            @Override
                            public void onError(GetCredentialException e) {
                                googleResult(null, (e instanceof GetCredentialCancellationException) ? "cancelado" : (e.getClass().getSimpleName() + ": " + e.getMessage()));
                            }
                        });
                } catch (Exception e) { googleResult(null, "Erro ao abrir o login do Google: " + e.getMessage()); }
            });
        }

        @JavascriptInterface
        public void linkDrive() {
            runOnUiThread(() -> {
                Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                it.setType("application/json");
                String model = (Build.MODEL == null ? "Android" : Build.MODEL).replaceAll("[^A-Za-z0-9]+", "-");
                it.putExtra(Intent.EXTRA_TITLE, "CONTAS-backup-" + model + ".json");
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
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CONTAS", text));
            });
        }

        @JavascriptInterface
        public void openUrl(String url) { runOnUiThread(() -> openExternal(url)); }
    }
}
