package com.samuraiflash.juego;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Voz del teléfono (Text-to-Speech de Android) para los diálogos del modo historia
@CapacitorPlugin(name = "Voz")
public class VozPlugin extends Plugin {
    private TextToSpeech tts;
    private boolean listo = false;
    private final Map<String, PluginCall> pendientes = new HashMap<>();
    private int n = 0;

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            Locale[] prefs = { new Locale("es", "US"), new Locale("es", "MX"), new Locale("es", "ES"), new Locale("es") };
            for (Locale l : prefs) {
                int r = tts.setLanguage(l);
                if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break;
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { fin(id); }
                @Override public void onError(String id) { fin(id); }
                @Override public void onStop(String id, boolean interrupted) { fin(id); }
            });
            listo = true;
        });
    }

    private void fin(String id) {
        PluginCall c;
        synchronized (pendientes) { c = pendientes.remove(id); }
        if (c != null) c.resolve();
    }

    @PluginMethod
    public void disponible(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", listo);
        call.resolve(r);
    }

    @PluginMethod
    public void hablar(PluginCall call) {
        if (!listo) { call.resolve(); return; }
        String txt = call.getString("texto", "");
        float pitch = call.getFloat("tono", 1f);
        float rate = call.getFloat("velocidad", 1f);
        float vol = Math.max(0f, Math.min(1f, call.getFloat("volumen", 1f)));
        tts.setPitch(Math.max(0.45f, pitch));
        tts.setSpeechRate(rate);
        String id = "v" + (n++);
        synchronized (pendientes) { pendientes.put(id, call); }
        android.os.Bundle params = new android.os.Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);
        tts.speak(txt, TextToSpeech.QUEUE_FLUSH, params, id);
    }

    @PluginMethod
    public void callar(PluginCall call) {
        if (tts != null) tts.stop();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}
