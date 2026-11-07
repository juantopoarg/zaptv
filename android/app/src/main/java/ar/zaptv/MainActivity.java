package ar.zaptv;

import android.app.Activity;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class MainActivity extends Activity {
    private static final String URL = "https://juantopoarg.github.io/zaptv/";

    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "imasdk.googleapis.com", "adservice.google.", "google-analytics.com",
            "googletagservices.com", "moatads.com", "adsystem.com"};
    private static final String[] AD_PATHS = {
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info"};

    private static boolean isAd(Uri u) {
        String h = u.getHost();
        if (h == null) return false;
        h = h.toLowerCase();
        for (String a : AD_HOSTS) if (h.contains(a)) return true;
        if (h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com")) {
            String p = u.getPath();
            if (p != null) for (String a : AD_PATHS) if (p.contains(a)) return true;
        }
        return false;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (isAd(r.getUrl())) {
                    return new WebResourceResponse("text/plain", "UTF-8", 200, "OK",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(v, r);
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            String js = readAsset("adblock.js");
            if (!js.isEmpty()) {
                WebViewCompat.addDocumentStartJavaScript(web, js, new HashSet<>(Arrays.asList(
                        "https://www.youtube.com", "https://www.youtube-nocookie.com",
                        "https://m.youtube.com")));
            }
        }
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                web.setVisibility(View.VISIBLE);
            }
        });

        hideSystemUi();
        web.loadUrl(URL);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
            return;
        }
        String js = "(function(){var d=document;return (d.body.classList.contains('gridopen')"
                + "||d.querySelector('#guide.on,#editor.on,#ccmenu.on'))?1:0})()";
        web.evaluateJavascript(js, value -> {
            if ("1".equals(value)) {
                web.evaluateJavascript(
                        "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))", null);
            } else {
                finish();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
