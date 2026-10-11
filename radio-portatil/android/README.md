# 📻 Rádio Portátil — app Android

Transforma o telemóvel numa estação de rádio. Quem estiver na mesma rede
Wi-Fi (ou ligado ao teu hotspot) abre um link no navegador e ouve a mesma
música ao mesmo tempo. Não precisa de instalar nada.

## Instalar

1. No telemóvel, abre a página **Releases** do repositório no GitHub e
   descarrega `RadioPortatil.apk` (versão "Rádio Portátil").
2. Abre o ficheiro. O Android pede para permitir "instalar apps
   desconhecidas" a partir do navegador ou dos Ficheiros: aceita.
3. Abre a **Rádio Portátil** e carrega em **▶ PÔR NO AR**.

Cada nova versão instala-se por cima da anterior (a assinatura é sempre a
mesma).

## O que faz

| Função | Como |
|---|---|
| 📶 Emissão por Wi-Fi | Todos os aparelhos na mesma rede abrem `http://IP-do-telemóvel:8000` |
| 📡 Rede Wi-Fi própria | "Criar rede Wi-Fi da rádio": a app cria a rede e mostra um QR para os ouvintes entrarem, sem gastar dados |
| 🔳 Códigos QR | Um para entrar na rede, outro para abrir a rádio |
| 🎵 Toda a tua música | Lê a biblioteca do telemóvel (mp3, m4a, flac, ogg, wav…) |
| 🔀 Aleatório, ⏸ pausa, ⏭ próxima, 🎵 escolher música | No ecrã, na notificação e no ecrã de bloqueio |
| 🎙️ Microfone ao vivo | Falas por cima da música e a música baixa sozinha |
| 🙋 Pedidos dos ouvintes | Os ouvintes pesquisam e pedem músicas na página. Máximo 3 por pessoa |
| 🖼️ Capa e título | A página mostra a capa, o título, o tempo e quantos estão a ouvir |
| 🔊 Tocar no próprio telemóvel | Altifalante, coluna Bluetooth ou **transmissor FM Bluetooth**, para sintonizar em qualquer rádio FM |
| 📻 Apps de rádio | VLC, Kodi e smart TVs: `http://IP:8000/stream.aac` ou `/radio.m3u`, com o título da música |
| 🌍 Internet | Envia para um servidor Icecast e pode ser ouvida em qualquer parte do mundo |
| 🔋 Segundo plano | Continua a emitir com o ecrã desligado |

## Na Xiaomi (HyperOS/MIUI)

Para o sistema não desligar a rádio, usa os botões na secção
"Para não ser desligada" da app:
- Poupança de bateria → **Sem restrições**
- **Início automático** ligado

## Como funciona

- O `Motor` descodifica cada música com o `MediaCodec` do Android e converte
  tudo para 44,1 kHz estéreo. Se o microfone estiver ligado, mistura a voz.
  Depois codifica em AAC, ao ritmo real, como uma rádio.
- O `Difusor` envia as mesmas tramas a todos os ouvintes. Quem chega recebe
  3 s de áudio de uma vez, para começar logo. Um ouvinte demasiado lento é
  desligado para não atrasar os outros.
- O `Servidor` é um servidor HTTP mínimo. Serve a página do ouvinte
  (`assets/index.html`), a emissão (com metadados ICY), o estado em JSON e
  os pedidos.

## Compilar

O APK é compilado automaticamente pelo GitHub Actions
(`.github/workflows/radio-portatil-apk.yml`) a cada alteração. Para compilar
localmente, com o Android SDK instalado: `./gradlew assembleRelease`.
