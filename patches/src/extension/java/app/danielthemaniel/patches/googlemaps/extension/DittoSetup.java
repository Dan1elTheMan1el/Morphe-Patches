package app.danielthemaniel.patches.googlemaps.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * First-run setup for the Ditto navigation vehicle.
 *
 * The Ditto model isn't shipped with the patch. When Maps' main activity resumes and Ditto isn't built yet,
 * this shows a full-screen in-app browser on the model's page at The Models Resource. The user taps the
 * page's download button (and passes the site's bot check themselves if it shows one); the download is
 * caught with the browser's own cookies and user agent, then handed to DittoBuilder, and the result is
 * saved in Maps' private storage. "Not now" skips setup until Maps is started again.
 */
@SuppressWarnings("unused")
public final class DittoSetup {
    private DittoSetup() {}

    /** Maps reads its vehicle list once per process, so it restarts to pick Ditto up. */
    private static void restart(android.content.Context c) {
        try {
            android.content.Context app = c.getApplicationContext();
            android.content.Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                android.app.PendingIntent pending = android.app.PendingIntent.getActivity(app, 0x0d170, launch,
                    0x04000000 /* FLAG_IMMUTABLE */ | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
                android.app.AlarmManager alarms = (android.app.AlarmManager) app.getSystemService(android.content.Context.ALARM_SERVICE);
                alarms.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 500, pending);
            }
        } catch (Throwable t) {
            Log.w(TAG, "restart", t);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    private static final String TAG = CustomNavatar.TAG;
    static final String PAGE = "https://models.spriters-resource.com/3ds/pokemonxy/asset/296368/";

    private static boolean shownThisProcess;
    private static Dialog dialog;

    /** Start of the main activity's onResume(). */
    public static void onResume(Activity activity) {
        try {
            if (shownThisProcess || dialog != null || CustomNavatar.ready()) return;
            if (CustomNavatar.built(CustomNavatar.target(activity))) return;
            if (CustomNavatar.rebuildFromCache(activity)) return; // builder updated: rebuild quietly
            shownThisProcess = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> show(activity), 1500);
        } catch (Throwable t) {
            Log.w(TAG, "Ditto setup failed", t);
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static void show(Activity activity) {
        if (activity.isFinishing() || dialog != null) return;
        Context c = activity;
        boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xff1f1f1f : 0xffffffff, fg = dark ? 0xffe8eaed : 0xff202124, sub = dark ? 0xffbdc1c6 : 0xff5f6368;

        Dialog d = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 12));
        TextView title = new TextView(c);
        title.setText("Set up your Ditto navigation vehicle");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView body = new TextView(c);
        body.setText("Maps builds Ditto from the Pokémon X/Y model at The Models Resource. Tap the download "
            + "button on the page below (pass the site's check if one appears). It's downloaded once, turned "
            + "into an animated vehicle, and kept in Maps.");
        body.setTextColor(sub);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dp(c, 6), 0, 0);
        header.addView(title);
        header.addView(body);

        FrameLayout content = new FrameLayout(c);
        WebView web = new WebView(c);
        content.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout status = new LinearLayout(c);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(bg);
        status.setVisibility(View.GONE);
        ProgressBar spinner = new ProgressBar(c);
        TextView statusText = new TextView(c);
        statusText.setTextColor(fg);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(c, 24), dp(c, 16), dp(c, 24), 0);
        status.addView(spinner);
        status.addView(statusText);
        content.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.END);
        bar.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        Button close = new Button(c, null, android.R.attr.borderlessButtonStyle);
        close.setText("Not now");
        bar.addView(close);

        root.addView(header);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bar);
        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(bg));
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient());
        String userAgent = s.getUserAgentString();

        Handler main = new Handler(Looper.getMainLooper());
        boolean[] busy = {false};
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (busy[0]) return;
            busy[0] = true;
            web.setVisibility(View.INVISIBLE);
            status.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            statusText.setText("Downloading Ditto…");
            close.setEnabled(false);
            String referer = web.getUrl();
            new Thread(() -> {
                try {
                    byte[] zip = download(url, ua != null ? ua : userAgent, referer);
                    main.post(() -> statusText.setText("Building Ditto's animations…"));
                    String summary = CustomNavatar.install(c.getApplicationContext(), zip);
                    main.post(() -> {
                        spinner.setVisibility(View.GONE);
                        statusText.setText("Ditto is ready!\n\nMaps will restart to add him to the vehicles. "
                            + "Then, while navigating, tap the arrow and pick Ditto.");
                        close.setText("Restart Maps");
                        close.setEnabled(true);
                        close.setOnClickListener(v -> restart(c));
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "Ditto download/build failed", t);
                    main.post(() -> {
                        busy[0] = false;
                        spinner.setVisibility(View.GONE);
                        statusText.setText("That didn't work: " + t.getMessage() + "\n\nTap the download button again.");
                        web.setVisibility(View.VISIBLE);
                        main.postDelayed(() -> {
                            if (!busy[0]) status.setVisibility(View.GONE);
                        }, 4000);
                        close.setEnabled(true);
                    });
                }
            }, "DittoSetup").start();
        });

        close.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(x -> {
            dialog = null;
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
        });
        dialog = d;
        d.show();
        web.loadUrl(PAGE);
    }

    /** Fetches the file the user asked for, with the in-app browser's cookies and user agent. */
    private static byte[] download(String url, String userAgent, String referer) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            if (referer != null) c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(4 << 20);
                byte[] buf = new byte[64 * 1024];
                int n;
                while (in != null && (n = in.read(buf)) > 0) o.write(buf, 0, n);
                byte[] data = o.toByteArray();
                if (code != 200 || data.length < 4 || data[0] != 'P' || data[1] != 'K') {
                    throw new IllegalStateException("the site sent HTTP " + code + " (" + c.getContentType() + ") instead of the zip");
                }
                return data;
            } finally {
                c.disconnect();
            }
        }
        throw new IllegalStateException("too many redirects");
    }
}
