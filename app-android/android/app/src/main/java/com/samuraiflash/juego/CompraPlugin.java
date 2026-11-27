package com.samuraiflash.juego;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Compras dentro de la app (Google Play Billing): la Temporada 2 es una compra única
@CapacitorPlugin(name = "Compra")
public class CompraPlugin extends Plugin implements PurchasesUpdatedListener {
    private BillingClient cliente;
    private PluginCall compraPendiente;
    private String idPendiente;
    private final List<Runnable> enEspera = new ArrayList<>();
    private boolean conectando = false;

    @Override
    public void load() {
        cliente = BillingClient.newBuilder(getContext())
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build();
    }

    private void conectar(Runnable listo, PluginCall call) {
        if (cliente.isReady()) { listo.run(); return; }
        synchronized (enEspera) {
            enEspera.add(listo);
            if (conectando) return;
            conectando = true;
        }
        cliente.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult r) {
                List<Runnable> q;
                synchronized (enEspera) { conectando = false; q = new ArrayList<>(enEspera); enEspera.clear(); }
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) { for (Runnable x : q) x.run(); }
                else if (call != null) call.reject("Google Play no está disponible (" + r.getResponseCode() + ")");
            }
            @Override public void onBillingServiceDisconnected() {
                synchronized (enEspera) { conectando = false; }
            }
        });
    }

    private void detalles(String id, PluginCall call, java.util.function.Consumer<ProductDetails> fin) {
        QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(BillingClient.ProductType.INAPP).build()))
            .build();
        cliente.queryProductDetailsAsync(p, (r, res) -> {
            List<ProductDetails> l = res != null ? res.getProductDetailsList() : null;
            fin.accept(r.getResponseCode() == BillingClient.BillingResponseCode.OK && l != null && !l.isEmpty() ? l.get(0) : null);
        });
    }

    // ¿Ya está comprado? (también sirve para recuperar la compra al reinstalar) + precio en la moneda del jugador
    @PluginMethod
    public void estado(PluginCall call) {
        String id = call.getString("id", "temporada_2");
        conectar(() -> cliente.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (r, compras) -> {
                boolean comprado = false, pendiente = false;
                for (Purchase c : compras) {
                    if (!c.getProducts().contains(id)) continue;
                    if (c.getPurchaseState() == Purchase.PurchaseState.PURCHASED) { comprado = true; reconocer(c); }
                    else if (c.getPurchaseState() == Purchase.PurchaseState.PENDING) pendiente = true;
                }
                final boolean fc = comprado, fp = pendiente;
                detalles(id, call, d -> {
                    JSObject o = new JSObject();
                    o.put("comprado", fc);
                    o.put("pendiente", fp);
                    o.put("ok", r.getResponseCode() == BillingClient.BillingResponseCode.OK);
                    if (d != null && d.getOneTimePurchaseOfferDetails() != null)
                        o.put("precio", d.getOneTimePurchaseOfferDetails().getFormattedPrice());
                    call.resolve(o);
                });
            }), call);
    }

    @PluginMethod
    public void comprar(PluginCall call) {
        String id = call.getString("id", "temporada_2");
        conectar(() -> detalles(id, call, d -> {
            if (d == null) { call.reject("El producto aún no está disponible en Google Play"); return; }
            getActivity().runOnUiThread(() -> {
                if (compraPendiente != null) compraPendiente.reject("cancelado");
                compraPendiente = call;
                idPendiente = id;
                BillingFlowParams f = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                    .build();
                BillingResult r = cliente.launchBillingFlow(getActivity(), f);
                if (r.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    compraPendiente = null;
                    JSObject o = new JSObject(); o.put("comprado", true); call.resolve(o);
                } else if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    compraPendiente = null;
                    call.reject("No se pudo abrir la compra (" + r.getResponseCode() + ")");
                }
            });
        }), call);
    }

    @Override
    public void onPurchasesUpdated(BillingResult r, List<Purchase> compras) {
        PluginCall call = compraPendiente;
        String id = idPendiente;
        compraPendiente = null;
        JSObject o = new JSObject();
        int code = r.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && compras != null) {
            for (Purchase c : compras) {
                if (id != null && !c.getProducts().contains(id)) continue;
                if (c.getPurchaseState() == Purchase.PurchaseState.PURCHASED) { o.put("comprado", true); reconocer(c); }
                else if (c.getPurchaseState() == Purchase.PurchaseState.PENDING) o.put("pendiente", true);
            }
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            o.put("comprado", true);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            o.put("cancelado", true);
        } else {
            o.put("error", code);
        }
        if (call != null) call.resolve(o);
        else if (o.has("comprado")) notifyListeners("comprado", o);
    }

    // Google reembolsa las compras que no se confirman en 3 días
    private void reconocer(Purchase c) {
        if (c.isAcknowledged()) return;
        cliente.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(c.getPurchaseToken()).build(), r -> {});
    }
}
