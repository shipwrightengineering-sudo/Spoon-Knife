package pt.radioportatil

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/** O que o servidor precisa de saber sobre a rádio. */
interface EstadoRadio {
    val nomeEstacao: String
    val faixaAtual: Faixa?
    val segundosFaixa: Int
    val pausado: Boolean
    val pedidosAtivos: Boolean
    val capa: ByteArray?
    val versaoCapa: Int
    val playlist: Playlist
    val difusor: Difusor
    val kbps: Int
}

/** Servidor HTTP minimalista: página do ouvinte, emissão em direto e pedidos. */
class Servidor(
    private val radio: EstadoRadio,
    val porta: Int,
    private val pagina: () -> ByteArray,
) {
    private var socket: ServerSocket? = null
    @Volatile private var ativo = false

    fun iniciar() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(porta))
        socket = s
        ativo = true
        thread(name = "servidor-http", isDaemon = true) {
            while (ativo) {
                val cliente = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { atender(cliente) }
            }
        }
    }

    fun parar() {
        ativo = false
        try { socket?.close() } catch (_: Exception) {}
    }

    private class Pedido(val metodo: String, val caminho: String, val query: Map<String, String>, val cabecalhos: Map<String, String>)

    private fun lerPedido(entrada: InputStream): Pedido? {
        val bytes = ByteArrayOutputStream()
        var ultimos = 0
        while (bytes.size() < 16384) {
            val b = entrada.read()
            if (b < 0) return null
            bytes.write(b)
            ultimos = (ultimos shl 8) or b
            if (ultimos == 0x0D0A0D0A || (ultimos and 0xFFFF) == 0x0A0A) break
        }
        val linhas = bytes.toString("UTF-8").split("\r\n", "\n")
        val partes = linhas.firstOrNull()?.split(" ") ?: return null
        if (partes.size < 2) return null
        val alvo = partes[1]
        val caminho = alvo.substringBefore('?')
        val query = alvo.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val cabecalhos = linhas.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        return Pedido(partes[0].uppercase(), caminho, query, cabecalhos)
    }

    private fun atender(cliente: Socket) {
        try {
            cliente.soTimeout = 15_000
            cliente.tcpNoDelay = true
            val entrada = BufferedInputStream(cliente.getInputStream())
            val saida = cliente.getOutputStream()
            val p = lerPedido(entrada) ?: return
            val ip = cliente.inetAddress?.hostAddress ?: "?"
            when {
                p.caminho == "/" || p.caminho == "/index.html" -> responder(saida, 200, "text/html; charset=utf-8", pagina())
                p.caminho in setOf("/stream", "/stream.aac", "/live", "/aovivo") -> emitir(cliente, saida, p, ip)
                p.caminho == "/estado" -> responder(saida, 200, "application/json; charset=utf-8", estado().toByteArray())
                p.caminho == "/faixas" -> responder(saida, 200, "application/json; charset=utf-8", faixas().toByteArray())
                p.caminho == "/pedir" -> {
                    val msg = if (!radio.pedidosAtivos) "Os pedidos estão desligados nesta rádio"
                    else radio.playlist.pedir(p.query["id"]?.toLongOrNull() ?: -1, ip)
                    responder(saida, 200, "application/json; charset=utf-8",
                        JSONObject().put("mensagem", msg).toString().toByteArray())
                }
                p.caminho == "/capa" -> {
                    val c = radio.capa
                    if (c != null) responder(saida, 200, tipoImagem(c), c)
                    else responder(saida, 404, "text/plain", "sem capa".toByteArray())
                }
                p.caminho == "/radio.m3u" || p.caminho == "/playlist.m3u" -> {
                    val host = p.cabecalhos["host"] ?: "${enderecosLocais().firstOrNull() ?: "localhost"}:$porta"
                    val m3u = "#EXTM3U\n#EXTINF:-1,${radio.nomeEstacao}\nhttp://$host/stream.aac\n"
                    responder(saida, 200, "audio/x-mpegurl", m3u.toByteArray())
                }
                else -> responder(saida, 404, "text/plain", "Nao encontrado".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { cliente.close() } catch (_: Exception) {}
        }
    }

    private fun responder(saida: OutputStream, codigo: Int, tipo: String, corpo: ByteArray) {
        val estado = if (codigo == 200) "OK" else "Not Found"
        val cab = "HTTP/1.1 $codigo $estado\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\n" +
            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        saida.write(cab.toByteArray())
        saida.write(corpo)
        saida.flush()
    }

    private fun emitir(cliente: Socket, saida: OutputStream, p: Pedido, ip: String) {
        val comMetadados = p.cabecalhos["icy-metadata"] == "1"
        val intervalo = 16000
        val cab = StringBuilder("HTTP/1.0 200 OK\r\n")
            .append("Content-Type: audio/aac\r\n")
            .append("Cache-Control: no-cache, no-store\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("icy-name: ${radio.nomeEstacao.replace(Regex("[\\r\\n]"), " ")}\r\n")
            .append("icy-br: ${radio.kbps}\r\n")
        if (comMetadados) cab.append("icy-metaint: $intervalo\r\n")
        cab.append("Connection: close\r\n\r\n")
        saida.write(cab.toString().toByteArray())
        saida.flush()
        cliente.soTimeout = 0

        val ouvinte = radio.difusor.ligar(Difusor.Ouvinte.Tipo.WIFI, ip)
        val escritor = EscritorIcy(saida, if (comMetadados) intervalo else 0) { radio.faixaAtual?.nomeCompleto ?: radio.nomeEstacao }
        try {
            while (!ouvinte.fechado) {
                val trama = ouvinte.proxima(5000) ?: continue
                escritor.escrever(trama)
                // envia de uma vez o que já estiver em fila (menos chamadas ao sistema)
                while (true) escritor.escrever(ouvinte.fila.poll() ?: break)
                saida.flush()
            }
        } catch (_: Exception) {
        } finally {
            radio.difusor.desligar(ouvinte)
        }
    }

    private fun estado(): String {
        val f = radio.faixaAtual
        return JSONObject()
            .put("estacao", radio.nomeEstacao)
            .put("titulo", f?.titulo ?: "")
            .put("artista", f?.artista ?: "")
            .put("duracao", (f?.duracaoMs ?: 0) / 1000)
            .put("segundos", radio.segundosFaixa)
            .put("pausado", radio.pausado)
            .put("ouvintes", radio.difusor.numeroOuvintes)
            .put("faixas", radio.playlist.todas.size)
            .put("pedidos", radio.pedidosAtivos)
            .put("capa", if (radio.capa != null) radio.versaoCapa else 0)
            .put("fila", JSONArray(radio.playlist.fila().take(10).map { it.nomeCompleto }))
            .toString()
    }

    private fun faixas(): String {
        val arr = JSONArray()
        for (f in radio.playlist.todas) {
            arr.put(JSONObject().put("id", f.id).put("t", f.titulo).put("a", f.artista))
        }
        return arr.toString()
    }

    private fun tipoImagem(b: ByteArray) =
        if (b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"

    companion object {
        /** Endereços IPv4 deste telemóvel nas redes Wi-Fi / hotspot. */
        fun enderecosLocais(): List<String> {
            val lista = ArrayList<Pair<String, String>>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        val ip = a.hostAddress ?: continue
                        if (a.isLoopbackAddress || ':' in ip) continue
                        lista += ni.name to ip
                    }
                }
            } catch (_: Exception) {
            }
            // interfaces de hotspot / Wi-Fi primeiro, dados móveis por último
            fun prioridade(nome: String) = when {
                nome.startsWith("ap") || nome.startsWith("swlan") || nome.startsWith("wlan") -> 0
                nome.startsWith("rndis") || nome.startsWith("eth") || nome.startsWith("bt") -> 1
                else -> 2
            }
            return lista.sortedBy { prioridade(it.first) }.map { it.second }.distinct()
        }
    }
}

/** Escreve o áudio, inserindo o título da música (metadados ICY) para apps de rádio como o VLC. */
class EscritorIcy(private val saida: OutputStream, private val intervalo: Int, private val titulo: () -> String) {
    private var contador = 0
    private var ultimoTitulo: String? = null

    fun escrever(dados: ByteArray) {
        if (intervalo <= 0) {
            saida.write(dados)
            return
        }
        var desvio = 0
        while (desvio < dados.size) {
            val n = minOf(intervalo - contador, dados.size - desvio)
            saida.write(dados, desvio, n)
            desvio += n
            contador += n
            if (contador == intervalo) {
                escreverMetadados()
                contador = 0
            }
        }
    }

    private fun escreverMetadados() {
        val t = titulo()
        if (t == ultimoTitulo) {
            saida.write(0)
            return
        }
        ultimoTitulo = t
        var bytes = "StreamTitle='${t.replace("'", "’")}';".toByteArray()
        if (bytes.size > 255 * 16) bytes = bytes.copyOf(255 * 16)
        val blocos = (bytes.size + 15) / 16
        saida.write(blocos)
        saida.write(bytes)
        saida.write(ByteArray(blocos * 16 - bytes.size))
    }
}

/** Envia a emissão para um servidor Icecast, para se ouvir em qualquer parte do mundo. */
class ClienteIcecast(
    private val radio: EstadoRadio,
    private val anfitriao: String,
    private val porta: Int,
    montagem: String,
    private val senha: String,
    private val utilizador: String = "source",
) {
    private val montagem = if (montagem.startsWith("/")) montagem else "/$montagem"
    @Volatile private var ativo = false
    @Volatile var estado: String = "Desligado"
        private set
    private var ouvinte: Difusor.Ouvinte? = null
    private var socket: Socket? = null

    private val autorizacao: String
        get() = "Basic " + Base64.encodeToString("$utilizador:$senha".toByteArray(), Base64.NO_WRAP)

    fun iniciar() {
        ativo = true
        thread(name = "icecast", isDaemon = true) {
            while (ativo) {
                try {
                    ligarEEnviar()
                } catch (e: Exception) {
                    if (ativo) estado = "Sem ligação (${e.message ?: e.javaClass.simpleName}). A tentar de novo…"
                    Log.w("Radio", "icecast", e)
                } finally {
                    ouvinte?.let { radio.difusor.desligar(it) }
                    ouvinte = null
                    try { socket?.close() } catch (_: Exception) {}
                }
                if (ativo) Thread.sleep(5000)
            }
            estado = "Desligado"
        }
    }

    fun parar() {
        ativo = false
        ouvinte?.let { radio.difusor.desligar(it) }
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun ligarEEnviar() {
        estado = "A ligar a $anfitriao…"
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(anfitriao, porta), 10_000)
        s.soTimeout = 5000
        val saida = s.getOutputStream()
        val nome = radio.nomeEstacao.replace(Regex("[\\r\\n]"), " ")
        saida.write((
            "PUT $montagem HTTP/1.1\r\n" +
                "Host: $anfitriao:$porta\r\n" +
                "Authorization: $autorizacao\r\n" +
                "User-Agent: RadioPortatil/1.0\r\n" +
                "Content-Type: audio/aac\r\n" +
                "Ice-Name: $nome\r\n" +
                "Ice-Public: 0\r\n" +
                "Ice-Audio-Info: bitrate=${radio.kbps}\r\n" +
                "Expect: 100-continue\r\n\r\n"
            ).toByteArray())
        saida.flush()
        val resposta = try { lerLinha(s.getInputStream()) } catch (e: java.net.SocketTimeoutException) { "" }
        val codigo = resposta.split(" ").getOrNull(1)?.toIntOrNull()
        when {
            codigo == 401 || codigo == 403 -> { estado = "Senha recusada pelo servidor"; Thread.sleep(25_000); return }
            codigo == 409 -> { estado = "Esse ponto de montagem já está a ser usado"; Thread.sleep(25_000); return }
            codigo != null && codigo >= 400 -> { estado = "O servidor recusou ($resposta)"; Thread.sleep(25_000); return }
        }
        s.soTimeout = 0
        estado = "No ar na internet: http://$anfitriao:$porta$montagem"
        val o = radio.difusor.ligar(Difusor.Ouvinte.Tipo.INTERNET, anfitriao)
        ouvinte = o
        var tituloEnviado: String? = null
        while (ativo && !o.fechado) {
            val trama = o.proxima(5000) ?: continue
            saida.write(trama)
            val t = radio.faixaAtual?.nomeCompleto
            if (t != null && t != tituloEnviado) {
                tituloEnviado = t
                thread(isDaemon = true) { atualizarTitulo(t) }
            }
        }
    }

    private fun atualizarTitulo(titulo: String) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(anfitriao, porta), 5000)
                s.soTimeout = 5000
                val q = "mount=" + URLEncoder.encode(montagem, "UTF-8") + "&mode=updinfo&song=" + URLEncoder.encode(titulo, "UTF-8")
                s.getOutputStream().write((
                    "GET /admin/metadata?$q HTTP/1.0\r\nHost: $anfitriao:$porta\r\n" +
                        "Authorization: $autorizacao\r\nUser-Agent: RadioPortatil/1.0\r\n\r\n"
                    ).toByteArray())
                s.getInputStream().read(ByteArray(512))
            }
        } catch (_: Exception) {
        }
    }

    private fun lerLinha(entrada: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = entrada.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
