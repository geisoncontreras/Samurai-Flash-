package com.samuraiflash.juego;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.pm.PackageInstaller;
import java.io.FileInputStream;
import java.io.OutputStream;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Environment;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Actualización automática: revisa la versión publicada, descarga el APK nuevo y abre el instalador de Android
@CapacitorPlugin(name = "Actualizar")
public class ActualizarPlugin extends Plugin {
    private volatile boolean bajando = false;

    private HttpURLConnection abrir(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    @PluginMethod
    public void revisar(PluginCall call) {
        String url = call.getString("url");
        new Thread(() -> {
            try {
                HttpURLConnection c = abrir(url);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close();
                JSObject r = new JSObject();
                r.put("texto", out.toString("UTF-8"));
                long code = 0;
                try {
                    android.content.pm.PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
                    code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                } catch (Exception e) {}
                r.put("codigo", code);
                call.resolve(r);
            } catch (Exception e) { call.reject("sin conexión"); }
        }).start();
    }

    private boolean puedeInstalar() {
        return Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls();
    }

    @PluginMethod
    public void permiso(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", puedeInstalar());
        call.resolve(r);
    }

    @PluginMethod
    public void pedirPermiso(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
            call.resolve();
        } catch (Exception e) { call.reject("no se pudo abrir ajustes"); }
    }

    private File archivo() { return archivo(getContext()); }

    private void progreso(long hecho, long total) {
        JSObject ev = new JSObject();
        ev.put("p", total > 0 ? (int) (hecho * 100 / total) : -1);
        ev.put("mb", hecho / 1048576.0);
        notifyListeners("progreso", ev);
    }

    // 1) con el gestor de descargas de Android (aguanta cambios de red y reintentos)
    private String conGestor(String url, File f) throws Exception {
        DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return "sin gestor";
        if (f.exists()) f.delete();
        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
        rq.setTitle("Samurai Flash");
        rq.setDescription("Descargando la versión nueva");
        rq.setMimeType("application/vnd.android.package-archive");
        rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        rq.setAllowedOverMetered(true);
        rq.setAllowedOverRoaming(true);
        rq.setDestinationUri(Uri.fromFile(f));
        long id = dm.enqueue(rq);
        long inicio = System.currentTimeMillis(), ultHecho = -1, ultCambio = inicio;
        while (true) {
            Thread.sleep(400);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null || !c.moveToFirst()) { if (c != null) c.close(); return "descarga cancelada"; }
            int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long hecho = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int razon = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            c.close();
            if (st == DownloadManager.STATUS_SUCCESSFUL) return null;
            if (st == DownloadManager.STATUS_FAILED) { dm.remove(id); return "error " + razon; }
            long ahora = System.currentTimeMillis();
            if (hecho != ultHecho) { ultHecho = hecho; ultCambio = ahora; progreso(Math.max(0, hecho), total); }
            if (ahora - ultCambio > 60000) { dm.remove(id); return "sin avance"; }
        }
    }

    // 2) respaldo: descarga directa
    private void directa(String url, File f) throws Exception {
        HttpURLConnection c = abrir(url);
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] b = new byte[65536]; int n; long hecho = 0; int ult = -1;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n); hecho += n;
            int p = total > 0 ? (int) (hecho * 100 / total) : (int) (hecho / 1048576);
            if (p != ult) { ult = p; progreso(hecho, total); }
        }
        out.close(); in.close();
        if (total > 0 && hecho < total) throw new Exception("incompleta");
    }

    @PluginMethod
    public void descargar(PluginCall call) {
        String url = call.getString("url");
        if (bajando) { call.reject("ya descargando"); return; }
        bajando = true;
        new Thread(() -> {
            File f = archivo();
            String fallo;
            try { fallo = conGestor(url, f); } catch (Exception e) { fallo = String.valueOf(e.getMessage()); }
            try {
                if (fallo != null || !f.exists() || f.length() < 100000) directa(url, f);
                instalar(f);
                call.resolve();
            } catch (Exception e) { call.reject("falló la descarga (" + fallo + " / " + e.getMessage() + ")"); }
            finally { bajando = false; }
        }).start();
    }

    // abre la página de descarga en el navegador del teléfono
    @PluginMethod
    public void abrirWeb(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("no se pudo abrir"); }
    }

    @PluginMethod
    public void instalarOtraVez(PluginCall call) {
        File f = archivo();
        if (!f.exists()) { call.reject("no hay archivo"); return; }
        try { instalar(f); call.resolve(); } catch (Exception e) { call.reject("no se pudo abrir"); }
    }

    static File archivo(Context ctx) {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (d == null) d = ctx.getCacheDir();
        return new File(d, "actualizacion.apk");
    }

    // respaldo: el instalador clásico de Android con el archivo descargado
    static void respaldo(Context ctx) {
        try {
            File f = archivo(ctx);
            if (!f.exists()) return;
            Uri u = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {}
    }

    // Instala con PackageInstaller: Android cierra el juego, pone la versión nueva encima y conserva el progreso
    private void instalar(File f) throws Exception {
        Context ctx = getContext();
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(ctx.getPackageName());
            sp.setSize(f.length());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                OutputStream out = s.openWrite("samurai-flash.apk", 0, f.length());
                FileInputStream in = new FileInputStream(f);
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close();
                s.fsync(out);
                out.close();
                Intent it = new Intent(ctx, InstalarReceiver.class);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent p = PendingIntent.getBroadcast(ctx, id, it, fl);
                s.commit(p.getIntentSender());
            } finally { s.close(); }
        } catch (Exception e) {
            respaldo(ctx);
        }
    }
}
