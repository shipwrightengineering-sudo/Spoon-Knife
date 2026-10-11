# 📻 Rádio Portátil

Transforma o teu smartphone (testado a pensar no Xiaomi Redmi Note 13 Pro) numa
estação de rádio: as tuas músicas são emitidas **em direto**, e toda a gente
ligada ao teu Wi-Fi/hotspot ouve **a mesma música ao mesmo tempo**, só a abrir
um link no navegador. Não precisa de instalar nada do lado de quem ouve.

```
 Telemóvel (Termux + radio.py)  ──Wi-Fi / hotspot──►  telemóveis, PCs, smart TVs, colunas
            │
            └──Bluetooth──► Transmissor FM (opcional, ~10 €) ──FM──► qualquer rádio (carro, aparelhagem)
```

## 1. Instalar (uma vez)

1. Instala o **Termux** a partir do [F-Droid](https://f-droid.org/packages/com.termux/)
   (a versão da Play Store está desatualizada) e o **Termux:API** também do F-Droid.
2. Copia `radio.py` e `instalar.sh` para a pasta **Download** do telemóvel.
3. Abre o Termux e corre:
   ```bash
   termux-setup-storage            # aceita o pedido de acesso aos ficheiros
   bash ~/storage/downloads/instalar.sh
   ```
4. **Importante na Xiaomi (HyperOS/MIUI)**: Definições → Apps → Termux →
   *Poupança de bateria* → **Sem restrições**, e ativa *Início automático*.
   Senão o sistema mata a emissão quando o ecrã desliga.

## 2. Pôr a rádio no ar

1. Liga o **Hotspot** do telemóvel (ou liga todos ao mesmo router Wi-Fi).
   *O hotspot funciona mesmo sem dados móveis — os ouvintes não gastam internet.*
2. No Termux:
   ```bash
   ~/radio/ligar.sh ~/storage/music --aleatorio --nome "A Minha Rádio"
   ```
3. O ecrã mostra o endereço, por exemplo `http://192.168.43.1:8000`.
   Os ouvintes abrem-no no navegador e carregam em **▶ Ouvir**.
4. No teu próprio telemóvel abre `http://localhost:8000` — tens o botão
   **⏭ Próxima faixa** (só tu o vês).

| Opção | Para quê |
|---|---|
| `pasta …` | uma ou mais pastas com música (mp3, m4a, flac, ogg, wav…) |
| `--aleatorio` | ordem aleatória |
| `--kbps 96` | menos qualidade = funciona melhor com muitos ouvintes / sinal fraco |
| `--porta 8000` | porta do servidor |
| `--nome "…"` | nome da estação |

Apps de rádio/players (VLC, Kodi, smart TVs) podem sintonizar diretamente
`http://IP:8000/stream.mp3`.

## 3. Sintonizar em "qualquer rádio" FM

Nenhum smartphone tem emissor FM (o chip FM do telemóvel só **recebe**). Para
ouvir num rádio FM normal:

- **Transmissor FM Bluetooth** (os de isqueiro de carro, ou com bateria/USB):
  emparelha o telemóvel, escolhe uma frequência livre (ex.: 88.1 MHz) e sintoniza
  o rádio nessa frequência. Toca a música do telemóvel — ou a própria Rádio
  Portátil: abre `http://localhost:8000` e carrega em Ouvir.
- Alcance de poucos metros. Usa só aparelhos certificados de baixa potência;
  emitir com mais potência sem licença é ilegal (ANACOM em Portugal, Anatel no
  Brasil).

## Como funciona

`radio.py` (Python, sem bibliotecas externas) percorre a playlist e usa o
`ffmpeg` a velocidade real (`-re`) para converter cada faixa num fluxo MP3
contínuo. O mesmo áudio é distribuído a todos os ouvintes ligados — tal como uma
rádio. Quem se liga recebe ~3 s de áudio imediatamente para começar logo; quem
tiver ligação demasiado lenta é desligado para não atrasar os outros.
