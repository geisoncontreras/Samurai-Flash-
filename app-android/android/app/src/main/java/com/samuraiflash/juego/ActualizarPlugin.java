package com.samuraiflash.juego;

import android.content.Intent;
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

    @PluginMethod
    public void descargar(PluginCall call) {
        String url = call.getString("url");
        if (bajando) { call.reject("ya descargando"); return; }
        bajando = true;
        new Thread(() -> {
            File f = new File(getContext().getCacheDir(), "actualizacion.apk");
            try {
                HttpURLConnection c = abrir(url);
                long total = c.getContentLength();
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(f);
                byte[] b = new byte[65536]; int n; long hecho = 0; int ult = -1;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n); hecho += n;
                    int p = total > 0 ? (int) (hecho * 100 / total) : -1;
                    if (p != ult) { ult = p; JSObject ev = new JSObject(); ev.put("p", p); ev.put("mb", hecho / 1048576.0); notifyListeners("progreso", ev); }
                }
                out.close(); in.close();
                if (total > 0 && hecho < total) throw new Exception("incompleto");
                instalar(f);
                call.resolve();
            } catch (Exception e) { call.reject("falló la descarga"); }
            finally { bajando = false; }
        }).start();
    }

    @PluginMethod
    public void instalarOtraVez(PluginCall call) {
        File f = new File(getContext().getCacheDir(), "actualizacion.apk");
        if (!f.exists()) { call.reject("no hay archivo"); return; }
        try { instalar(f); call.resolve(); } catch (Exception e) { call.reject("no se pudo abrir"); }
    }

    private void instalar(File f) {
        Uri u = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
    }
}
