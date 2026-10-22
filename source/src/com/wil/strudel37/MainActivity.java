package com.wil.strudel37;

import android.app.Activity;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private WebView web;
    private MidiBridge midi;
    private NativeSynth synth;
    private boolean synthWasRunning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                recreate(); // page crashed: rebuild the activity instead of letting Android close the app
                return true;
            }
        });
        web.setBackgroundColor(0xFF191512);
        midi = new MidiBridge(this, web);
        web.addJavascriptInterface(midi, "AndroidMidi");
        synth = new NativeSynth(this, web);
        midi.synth = synth;
        web.addJavascriptInterface(synth, "AndroidSynth");
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(web);
        hideBars();
        watchKeyboard();
        web.loadDataWithBaseURL("https://strudel37.app/", readAsset("index.html"), "text/html", "utf-8", null);
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "<h1>Error: " + e + "</h1>";
        }
    }

    /**
     * The window is fullscreen, so Android does not resize it for the soft keyboard.
     * Only the keyboard height is passed to the page (no relayout here: resizing the WebView from these
     * callbacks fed back into them and froze the app); the page floats the Strudel editor above it.
     * API 30+ uses the IME insets, older versions the visible display frame.
     */
    private int imeLast = -1;
    private void watchKeyboard() {
        final View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            decor.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    reportIme(insets.isVisible(WindowInsets.Type.ime()) ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0);
                    return v.onApplyWindowInsets(insets);
                }
            });
        } else {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    Rect r = new Rect();
                    decor.getWindowVisibleDisplayFrame(r);
                    int full = decor.getHeight();
                    reportIme(full - r.bottom > full / 5 ? full - r.bottom : 0);
                }
            });
        }
    }

    private void reportIme(int px) {
        if (px == imeLast) return;
        final boolean closed = imeLast > 0 && px == 0;
        imeLast = px;
        final float css = px / getResources().getDisplayMetrics().density;
        web.post(new Runnable() { public void run() {
            web.evaluateJavascript("window.__ime&&__ime(" + css + ")", null);
            if (closed) hideBars();
        } });
    }

    private void hideBars() {
        // LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY
        getWindow().getDecorView().setSystemUiVisibility(0x100 | 0x200 | 0x400 | 0x2 | 0x4 | 0x1000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        midi.onPermissions(code, results);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.__pause&&window.__pause()", null);
        synthWasRunning = synth != null && synth.info().contains("\"running\":true");
        if (synth != null) synth.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (synthWasRunning) synth.start(null);
        if (web != null) web.evaluateJavascript("window.__resume&&window.__resume()", null);
    }

    @Override
    protected void onDestroy() {
        if (midi != null) midi.closeAll();
        if (synth != null) synth.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
