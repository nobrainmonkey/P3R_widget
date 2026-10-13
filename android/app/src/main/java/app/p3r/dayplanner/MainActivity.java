package app.p3r.dayplanner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Self-contained HTML planner with a native bridge shared with the Android launcher widget. */
public class MainActivity extends Activity {
    public static final String ACTION_OPEN_TASK = "app.p3r.dayplanner.action.OPEN_TASK";
    public static final String EXTRA_DATE = "gameDate";
    public static final String EXTRA_TASK_ID = "taskId";
    public static final String EXTRA_CONFIRM_COMPLETION = "confirmCompletion";
    private static final int REQUEST_IMPORT = 301;
    private static final int REQUEST_EXPORT = 302;
    private WebView webView;
    private boolean webReady;
    private String pendingDate;
    private String pendingTaskId;
    private boolean pendingCompletionConfirmation;
    private String pendingExport;
    private ValueCallback<Uri[]> fileCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webReady = false;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(6, 66, 183));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(6, 66, 183));
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((View view, WindowInsets windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                root.setPadding(0, bars.top, 0, bars.bottom);
                return windowInsets;
            });
        }
        setContentView(root);
        getWindow().setStatusBarColor(Color.rgb(6, 45, 145));
        getWindow().setNavigationBarColor(Color.rgb(6, 38, 118));
        getWindow().getDecorView().setSystemUiVisibility(0);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); // Required only for packaged file:///android_asset/index.html
        settings.setAllowContentAccess(true); // System file picker for JSON import
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"file".equals(request.getUrl().getScheme()); // no external pages
            }
            @Override public void onPageFinished(WebView view, String url) {
                webReady = true;
                pushStateAndNavigate();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_IMPORT);
                } catch (Exception error) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        readTarget(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readTarget(intent);
        if (webReady) pushStateAndNavigate();
    }

    @Override protected void onResume() {
        super.onResume();
        PlannerWidget.refreshAll(this);
        if (webReady) pushStateAndNavigate();
    }

    private void readTarget(Intent intent) {
        if (intent == null || !ACTION_OPEN_TASK.equals(intent.getAction())) return;
        pendingDate = intent.getStringExtra(EXTRA_DATE);
        pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID);
        pendingCompletionConfirmation = intent.getBooleanExtra(EXTRA_CONFIRM_COMPLETION, false);
        if (pendingDate != null) {
            PlannerStore.selectDate(this, pendingDate);
            PlannerWidget.refreshAll(this);
        }
    }

    private void pushStateAndNavigate() {
        if (!webReady) return;
        String snapshot = PlannerStore.snapshot(this);
        webView.evaluateJavascript("window.P3RNative && window.P3RNative.applyState(" + JSONObject.quote(snapshot) + ");", null);
        if (pendingDate != null) {
            String date = pendingDate, taskId = pendingTaskId;
            boolean confirm = pendingCompletionConfirmation;
            pendingCompletionConfirmation = false;
            pendingDate = null;
            pendingTaskId = null;
            webView.evaluateJavascript("window.P3RNative && window.P3RNative.open(" +
                JSONObject.quote(date) + "," + (taskId == null ? "null" : JSONObject.quote(taskId)) + ");", null);
            // Completion is handled directly by the widget; details never show an interstitial.
        }
    }

    private final class NativeBridge {
        @JavascriptInterface public void syncState(String data) {
            PlannerStore.acceptWebState(MainActivity.this, data);
            PlannerWidget.refreshAll(MainActivity.this);
        }
        @JavascriptInterface public void exportJson(String content) {
            runOnUiThread(() -> {
                pendingExport = content;
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                save.addCategory(Intent.CATEGORY_OPENABLE);
                save.setType("application/json");
                save.putExtra(Intent.EXTRA_TITLE, "P3R_打卡进度.json");
                try { startActivityForResult(save, REQUEST_EXPORT); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "无法打开保存对话框", Toast.LENGTH_SHORT).show(); }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IllegalStateException("Output stream unavailable");
                    output.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "进度已保存", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            pendingExport = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.removeJavascriptInterface("AndroidBridge"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
