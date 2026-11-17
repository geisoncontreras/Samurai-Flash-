// Servidor del modo en línea de Samurai Flash (2 a 4 jugadores).
//
// Es un "cartero" muy simple: guarda en memoria el estado de cada sala
// (jugadores, rondas, resultados) como un árbol JSON y avisa por WebSocket
// a todos los teléfonos de la sala cada vez que algo cambia. Toda la lógica
// del duelo la hace el juego; el servidor solo guarda y reparte.
//
// Mensajes (JSON, siempre con "op"):
//   cliente → servidor
//     set     {p, v, id}     escribe v en la ruta p (null borra)
//     update  {p, v, id}     escribe cada clave de v dentro de p
//     remove  {p, id}        borra la ruta p
//     get     {p, id}        pide el valor de p → "res" {id, v}
//     on      {p, sid}       avísame cuando cambie p → "val" {sid, v}
//     off     {sid}          deja de avisarme
//     disc    {p}            borra p si me desconecto
//     undisc  {p}            ya no borres p al desconectarme
//   servidor → cliente
//     hola    {t}            hora del servidor (para sincronizar relojes)
//     ok      {id}           escritura hecha
//     res     {id, v}
//     val     {sid, v}
"use strict";
const http = require("http");
const { WebSocketServer } = require("ws");

const PUERTO = Number(process.env.PORT || 8080);
const RAIZ = "samuraiFlash/rooms";          // única parte del árbol que se puede tocar
const MAX_MSG = 16 * 1024;                  // tamaño máximo de un mensaje
const MAX_SALAS = 2000;
const VIDA_SALA = 3 * 3600 * 1000;          // una sala vive como mucho 3 horas

let arbol = {};                              // el "árbol" de datos

const partes = (p) => String(p || "").split("/").filter(Boolean);
const esObj = (v) => v && typeof v === "object" && !Array.isArray(v);
const permitida = (k) => k.join("/").startsWith(RAIZ) && k.length >= 3 && k.length <= 8 && k.every((x) => /^[A-Za-z0-9_-]{1,40}$/.test(x));

function leer(k) { let c = arbol; for (const x of k) { if (!esObj(c) || !(x in c)) return null; c = c[x]; } return c === undefined ? null : c; }
function sellos(v) {                         // {".sv":"timestamp"} → hora del servidor
  if (esObj(v)) { if (v[".sv"]) return Date.now(); const o = {}; for (const a in v) { const b = sellos(v[a]); if (b != null) o[a] = b; } return Object.keys(o).length ? o : null; }
  return v;
}
function escribir(k, v) {
  v = sellos(v);
  if (v == null) { borrar(k); return; }
  let c = arbol;
  for (let i = 0; i < k.length - 1; i++) { if (!esObj(c[k[i]])) c[k[i]] = {}; c = c[k[i]]; }
  c[k[k.length - 1]] = v;
}
function borrar(k) {
  const pila = [arbol]; let c = arbol;
  for (let i = 0; i < k.length - 1; i++) { if (!esObj(c[k[i]])) return; c = c[k[i]]; pila.push(c); }
  delete c[k[k.length - 1]];
  for (let i = k.length - 2; i >= 0; i--) { if (Object.keys(pila[i + 1]).length) break; delete pila[i][k[i]]; }
}
function contarSalas() { const r = leer(partes(RAIZ)); return esObj(r) ? Object.keys(r).length : 0; }

// --- avisos ---------------------------------------------------------------
const clientes = new Set();
let pendiente = new Set(), programado = false;
function cambio(k) {
  const ruta = k.join("/");
  for (const ws of clientes) for (const [sid, s] of ws.subs) {
    if (ruta === s.ruta || ruta.startsWith(s.ruta + "/") || s.ruta.startsWith(ruta + "/")) pendiente.add([ws, sid]);
  }
  if (!programado) { programado = true; setImmediate(enviarAvisos); }
}
function enviarAvisos() {
  programado = false; const lista = pendiente; pendiente = new Set(); const hecho = new Set();
  for (const [ws, sid] of lista) {
    const clave = sid + "@" + ws.n; if (hecho.has(clave)) continue; hecho.add(clave);
    const s = ws.subs.get(sid); if (s) enviar(ws, { op: "val", sid, v: leer(s.k) });
  }
}
function enviar(ws, m) { if (ws.readyState === 1) ws.send(JSON.stringify(m)); }

// --- servidor -------------------------------------------------------------
const web = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8", "Access-Control-Allow-Origin": "*" });
  res.end("Servidor de Samurai Flash funcionando. Salas abiertas: " + contarSalas() + "\n");
});
const wss = new WebSocketServer({ server: web, maxPayload: MAX_MSG });
let numero = 0;
wss.on("connection", (ws) => {
  ws.n = ++numero; ws.subs = new Map(); ws.disc = new Set(); ws.vivo = true;
  clientes.add(ws);
  enviar(ws, { op: "hola", t: Date.now() });
  ws.on("pong", () => { ws.vivo = true; });
  ws.on("message", (data) => {
    let m; try { m = JSON.parse(data); } catch (_) { return; }
    if (!m || typeof m.op !== "string") return;
    const k = partes(m.p);
    if (m.op === "off") { ws.subs.delete(m.sid); return; }
    if (!permitida(k)) { if (m.id != null) enviar(ws, { op: "ok", id: m.id, error: "ruta" }); return; }
    switch (m.op) {
      case "set":
        if (k.length === 3 && leer(k) == null && contarSalas() >= MAX_SALAS) { enviar(ws, { op: "ok", id: m.id, error: "lleno" }); return; }
        escribir(k, m.v); cambio(k); enviar(ws, { op: "ok", id: m.id }); break;
      case "update":
        if (esObj(m.v)) for (const a in m.v) { const kk = k.concat(partes(a)); if (permitida(kk)) escribir(kk, m.v[a]); }
        cambio(k); enviar(ws, { op: "ok", id: m.id }); break;
      case "remove": borrar(k); cambio(k); enviar(ws, { op: "ok", id: m.id }); break;
      case "get": enviar(ws, { op: "res", id: m.id, v: leer(k) }); break;
      case "on": ws.subs.set(m.sid, { k, ruta: k.join("/") }); enviar(ws, { op: "val", sid: m.sid, v: leer(k) }); break;
      case "disc": ws.disc.add(k.join("/")); break;
      case "undisc": ws.disc.delete(k.join("/")); break;
    }
  });
  ws.on("close", () => {
    clientes.delete(ws);
    for (const r of ws.disc) { const k = partes(r); borrar(k); cambio(k); }
  });
});

// conexiones muertas y salas viejas
setInterval(() => {
  for (const ws of clientes) { if (!ws.vivo) { ws.terminate(); continue; } ws.vivo = false; try { ws.ping(); } catch (_) {} }
  const salas = leer(partes(RAIZ)); const ahora = Date.now();
  if (esObj(salas)) for (const c in salas) {
    const s = salas[c];
    if (c === "LIST") {                      // lista de salas públicas: quita las que ya no existen o no se renuevan
      if (esObj(s)) for (const k in s) if (!esObj(salas[k]) || ahora - ((s[k] && s[k].t) || 0) > 120000) { const kk = partes(RAIZ + "/LIST/" + k); borrar(kk); cambio(kk); }
      continue;
    } const sinJugadores = !esObj(s) || !esObj(s.players) || !Object.keys(s.players).length;
    if (sinJugadores || ahora - (s.created || ahora) > VIDA_SALA) { const k = partes(RAIZ + "/" + c); borrar(k); cambio(k); }
  }
}, 30000);

web.listen(PUERTO, () => console.log("Samurai Flash: servidor en el puerto " + PUERTO));
module.exports = { web };
