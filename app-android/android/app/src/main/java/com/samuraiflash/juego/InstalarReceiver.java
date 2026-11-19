package com.samuraiflash.juego;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

// Recibe el estado de la instalación de la versión nueva (PackageInstaller)
public class InstalarReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android pide confirmar: abrimos su ventana "¿Actualizar esta app?"
            Intent confirmar = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) {
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(confirmar); } catch (Exception e) { ActualizarPlugin.respaldo(ctx); }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_FAILURE_ABORTED) return; // el jugador pulsó Cancelar
            try { Toast.makeText(ctx, "No se pudo instalar" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show(); } catch (Exception e) {}
            ActualizarPlugin.respaldo(ctx);
        }
    }
}
