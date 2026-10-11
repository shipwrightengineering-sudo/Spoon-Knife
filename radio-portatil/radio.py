#!/usr/bin/env python3
"""
Rádio Portátil — transforma o smartphone numa estação de rádio Wi-Fi.

Toda a gente ligada à mesma rede (ou ao hotspot do telemóvel) abre o
endereço mostrado no ecrã e ouve a mesma música, ao mesmo tempo, como
numa rádio a sério.

Requisitos (Termux no Android):  pkg install python ffmpeg
Uso:  python radio.py [pasta_de_musica] [--porta 8000] [--kbps 128] [--aleatorio]
"""

import argparse
import collections
import html
import json
import os
import random
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

EXTENSOES = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav", ".wma"}
CHUNK = 4096


class Ouvinte:
    def __init__(self):
        self.fila = collections.deque(maxlen=512)
        self.evento = threading.Event()
        self.fechada = False


class Estacao:
    """Lê a playlist em tempo real com o ffmpeg e distribui o áudio a todos os ouvintes."""

    def __init__(self, ficheiros, kbps, aleatorio, nome):
        self.ficheiros = ficheiros
        self.kbps = kbps
        self.aleatorio = aleatorio
        self.nome = nome
        self.ouvintes = set()
        self.lock = threading.Lock()
        # ~3 s de áudio guardado para quem se liga começar a ouvir logo
        self.buffer_inicial = collections.deque(maxlen=max(1, kbps * 1000 // 8 * 3 // CHUNK))
        self.a_tocar = None
        self.inicio_faixa = None
        self.proc = None
        self.saltar = threading.Event()

    def playlist(self):
        while True:
            ordem = list(self.ficheiros)
            if self.aleatorio:
                random.shuffle(ordem)
            yield from ordem

    def emitir(self):
        for caminho in self.playlist():
            self.a_tocar = os.path.splitext(os.path.basename(caminho))[0]
            self.inicio_faixa = time.time()
            self.saltar.clear()
            print(f"♪ A tocar: {self.a_tocar}", flush=True)
            cmd = [
                "ffmpeg", "-hide_banner", "-loglevel", "fatal", "-nostdin",
                "-re", "-i", caminho, "-vn", "-map_metadata", "-1",
                "-ac", "2", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", f"{self.kbps}k",
                "-write_xing", "0", "-id3v2_version", "0", "-f", "mp3", "-",
            ]
            try:
                self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
            except FileNotFoundError:
                sys.exit("Erro: ffmpeg não encontrado. No Termux: pkg install ffmpeg")
            while not self.saltar.is_set():
                dados = self.proc.stdout.read1(CHUNK) if hasattr(self.proc.stdout, "read1") else self.proc.stdout.read(CHUNK)
                if not dados:
                    break
                self.distribuir(dados)
            self.proc.kill()
            self.proc.wait()
            if time.time() - self.inicio_faixa < 1 and not self.saltar.is_set():
                time.sleep(1)  # ficheiro ilegível: evita ciclo rápido

    def distribuir(self, dados):
        with self.lock:
            self.buffer_inicial.append(dados)
            for o in list(self.ouvintes):
                if len(o.fila) >= o.fila.maxlen:
                    # ouvinte demasiado lento: desliga-o em vez de atrasar os outros
                    self.ouvintes.discard(o)
                    o.fechada = True
                else:
                    o.fila.append(dados)
                o.evento.set()

    def novo_ouvinte(self):
        o = Ouvinte()
        with self.lock:
            o.fila.extend(self.buffer_inicial)
            o.evento.set()
            self.ouvintes.add(o)
        return o

    def remover_ouvinte(self, o):
        with self.lock:
            self.ouvintes.discard(o)

    def estado(self):
        return {
            "estacao": self.nome,
            "a_tocar": self.a_tocar,
            "segundos": int(time.time() - self.inicio_faixa) if self.inicio_faixa else 0,
            "ouvintes": len(self.ouvintes),
            "faixas": len(self.ficheiros),
        }


PAGINA = """<!doctype html>
<html lang="pt"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{nome}</title>
<style>
 body{{margin:0;font-family:system-ui,sans-serif;background:#111;color:#eee;display:flex;
      min-height:100vh;align-items:center;justify-content:center;text-align:center}}
 main{{padding:24px;max-width:420px;width:100%}}
 h1{{font-size:1.6em;margin:0 0 4px}} .sub{{color:#999;margin:0 0 28px}}
 button{{font-size:1.2em;padding:16px 36px;border:0;border-radius:40px;background:#e5484d;
        color:#fff;cursor:pointer}}
 #faixa{{font-size:1.15em;margin:28px 0 6px;min-height:1.4em}} #info{{color:#999;font-size:.9em}}
 .dj{{margin-top:24px}} .dj button{{background:#333;font-size:1em;padding:10px 24px}}
</style></head><body><main>
<h1>📻 {nome}</h1><p class="sub">Rádio ao vivo</p>
<button id="play">▶ Ouvir</button>
<div id="faixa"></div><div id="info"></div>
{dj}
<audio id="audio" preload="none"></audio>
<script>
const a=document.getElementById('audio'), b=document.getElementById('play');
b.onclick=()=>{{ if(a.paused){{ a.src='/stream?'+Date.now(); a.play(); b.textContent='■ Parar'; }}
  else {{ a.pause(); a.removeAttribute('src'); a.load(); b.textContent='▶ Ouvir'; }} }};
async function atualizar(){{ try{{ const e=await (await fetch('/estado')).json();
  document.getElementById('faixa').textContent='♪ '+(e.a_tocar||'—');
  document.getElementById('info').textContent=e.ouvintes+' a ouvir · '+e.faixas+' faixas';
  if('mediaSession' in navigator) navigator.mediaSession.metadata=new MediaMetadata({{title:e.a_tocar||'',artist:e.estacao}});
 }}catch(_){{}} }}
atualizar(); setInterval(atualizar,3000);
</script></main></body></html>"""

BOTAO_DJ = """<div class="dj"><button onclick="fetch('/saltar',{method:'POST'}).then(()=>setTimeout(atualizar,800))">⏭ Próxima faixa</button></div>"""


def criar_handler(estacao):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def e_local(self):
            return self.client_address[0] in ("127.0.0.1", "::1")

        def responder(self, codigo, tipo, corpo):
            self.send_response(codigo)
            self.send_header("Content-Type", tipo)
            self.send_header("Content-Length", str(len(corpo)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(corpo)

        def do_GET(self):
            rota = urlparse(self.path).path
            if rota == "/":
                # o botão "próxima faixa" só aparece no próprio telemóvel (localhost)
                corpo = PAGINA.format(nome=html.escape(estacao.nome), dj=BOTAO_DJ if self.e_local() else "")
                self.responder(200, "text/html; charset=utf-8", corpo.encode())
            elif rota == "/estado":
                self.responder(200, "application/json", json.dumps(estacao.estado()).encode())
            elif rota in ("/stream", "/stream.mp3"):
                self.transmitir()
            else:
                self.responder(404, "text/plain", b"Nao encontrado")

        def do_POST(self):
            if urlparse(self.path).path == "/saltar" and self.e_local():
                estacao.saltar.set()
                self.responder(200, "text/plain", b"ok")
            else:
                self.responder(403, "text/plain", b"Proibido")

        def transmitir(self):
            self.close_connection = True
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Cache-Control", "no-cache, no-store")
            self.send_header("Connection", "close")
            self.send_header("icy-name", estacao.nome)
            self.end_headers()
            o = estacao.novo_ouvinte()
            print(f"+ Ouvinte ligado: {self.client_address[0]} ({len(estacao.ouvintes)} no total)", flush=True)
            try:
                while not o.fechada:
                    o.evento.wait(5)
                    o.evento.clear()
                    while o.fila:
                        self.wfile.write(o.fila.popleft())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, TimeoutError, OSError):
                pass
            finally:
                estacao.remover_ouvinte(o)
                print(f"- Ouvinte saiu: {self.client_address[0]}", flush=True)

    return Handler


def encontrar_musicas(pastas):
    ficheiros = []
    for pasta in pastas:
        for raiz, _, nomes in os.walk(os.path.expanduser(pasta)):
            for n in sorted(nomes):
                if os.path.splitext(n)[1].lower() in EXTENSOES:
                    ficheiros.append(os.path.join(raiz, n))
    return ficheiros


def ips_locais():
    ips = set()
    for destino in ("8.8.8.8", "192.168.43.255", "10.255.255.255"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect((destino, 1))
                ips.add(s.getsockname()[0])
        except OSError:
            pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    padrao = [p for p in ("~/storage/music", "~/storage/shared/Music", "~/storage/downloads") if os.path.isdir(os.path.expanduser(p))]
    ap = argparse.ArgumentParser(description="Rádio Portátil: transmite as tuas músicas por Wi-Fi.")
    ap.add_argument("pastas", nargs="*", default=padrao or ["."], help="pasta(s) com músicas")
    ap.add_argument("--porta", type=int, default=8000)
    ap.add_argument("--kbps", type=int, default=128, help="qualidade do áudio (64-320)")
    ap.add_argument("--aleatorio", action="store_true", help="ordem aleatória")
    ap.add_argument("--nome", default="Rádio Portátil", help="nome da estação")
    args = ap.parse_args()

    ficheiros = encontrar_musicas(args.pastas)
    if not ficheiros:
        sys.exit(f"Nenhuma música encontrada em: {', '.join(args.pastas)}\n"
                 "Dica: corre 'termux-setup-storage' e indica a pasta, ex.: python radio.py ~/storage/music")

    estacao = Estacao(ficheiros, args.kbps, args.aleatorio, args.nome)
    threading.Thread(target=estacao.emitir, daemon=True).start()

    servidor = ThreadingHTTPServer(("0.0.0.0", args.porta), criar_handler(estacao))
    servidor.daemon_threads = True

    print(f"\n📻 {args.nome} no ar com {len(ficheiros)} faixas ({args.kbps} kbps)\n")
    print("Os ouvintes (mesma rede Wi-Fi ou ligados ao teu hotspot) abrem no navegador:")
    for ip in ips_locais() or ["<IP-do-telemóvel>"]:
        print(f"   http://{ip}:{args.porta}")
    print(f"\nNo próprio telemóvel (com botão de próxima faixa): http://localhost:{args.porta}")
    print(f"Link direto para apps de rádio (VLC, etc.): http://<IP>:{args.porta}/stream.mp3")
    print("Ctrl+C para desligar.\n", flush=True)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nEmissão terminada.")
        if estacao.proc:
            estacao.proc.kill()


if __name__ == "__main__":
    main()
