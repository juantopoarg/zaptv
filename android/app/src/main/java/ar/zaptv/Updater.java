package ar.zaptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Actualización automática desde la release "apk-latest" de GitHub. */
final class Updater {
    private static final String API = "https://api.github.com/repos/juantopoarg/zaptv/releases/tags/apk-latest";
    private static final String ACTION = "ar.zaptv.INSTALL_RESULT";
    private static final long ASK_EVERY_MS = 12L * 60 * 60 * 1000;

    private final Activity act;
    private final SharedPreferences prefs;
    private boolean busy = false;

    Updater(Activity a) {
        act = a;
        prefs = a.getSharedPreferences("zaptv_update", Context.MODE_PRIVATE);
    }

    /** Consulta en segundo plano; si hay versión nueva, pregunta si instalar. */
    void check() {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                Matcher m = Pattern.compile("verCode=(\\d+)").matcher(j.optString("body", ""));
                if (!m.find()) return;
                long remote = Long.parseLong(m.group(1));
                JSONArray assets = j.optJSONArray("assets");
                if (assets == null || assets.length() == 0) return;
                String apkUrl = assets.getJSONObject(0).getString("browser_download_url");
                if (remote <= localVersion()) return;
                long last = prefs.getLong("asked_" + remote, 0);
                if (System.currentTimeMillis() - last < ASK_EVERY_MS) return;
                act.runOnUiThread(() -> ask(remote, apkUrl));
            } catch (Exception ignored) {
            } finally {
                busy = false;
            }
        }).start();
    }

    private long localVersion() throws Exception {
        PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private void ask(long remote, String apkUrl) {
        if (act.isFinishing()) return;
        prefs.edit().putLong("asked_" + remote, System.currentTimeMillis()).apply();
        new AlertDialog.Builder(act)
                .setTitle("Actualización disponible")
                .setMessage("Hay una versión nueva de ZapTV. ¿Descargar e instalar ahora?")
                .setPositiveButton("Instalar", (d, w) -> download(apkUrl))
                .setNegativeButton("Más tarde", null)
                .show();
    }

    private void download(String apkUrl) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            toast("Habilita \"Instalar apps desconocidas\" para ZapTV y vuelve a abrir la app");
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Exception ignored) {}
            prefs.edit().clear().apply();   // volver a preguntar en el próximo inicio
            return;
        }
        toast("Descargando actualización…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                install(c.getInputStream(), c.getContentLengthLong());
            } catch (Exception e) {
                act.runOnUiThread(() -> toast("No se pudo descargar la actualización"));
            }
        }).start();
    }

    private void install(InputStream in, long size) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (size > 0) sp.setSize(size);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try (OutputStream out = s.openWrite("zaptv.apk", 0, size > 0 ? size : -1)) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            s.fsync(out);
        }
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(confirm);
                    }
                    return;
                }
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
                if (st != PackageInstaller.STATUS_SUCCESS) toast("No se pudo instalar la actualización");
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else act.registerReceiver(r, f);
        Intent cb = new Intent(ACTION).setPackage(act.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        s.commit(PendingIntent.getBroadcast(act, id, cb, flags).getIntentSender());
    }

    private void toast(String t) {
        act.runOnUiThread(() -> Toast.makeText(act, t, Toast.LENGTH_LONG).show());
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int k;
        while ((k = in.read(b)) > 0) o.write(b, 0, k);
        return o.toString("UTF-8");
    }
}
