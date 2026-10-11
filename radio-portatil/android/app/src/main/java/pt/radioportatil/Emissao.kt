package pt.radioportatil

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** Distribui as tramas AAC a todos os ouvintes (e ao servidor de internet). */
class Difusor {
    class Ouvinte(val tipo: Tipo, val ip: String) {
        enum class Tipo { WIFI, INTERNET }
        val fila = ArrayBlockingQueue<ByteArray>(900) // ~20 s
        @Volatile var fechado = false

        /** Próxima trama, ou null se não houver nada em [ms] milissegundos. */
        fun proxima(ms: Long): ByteArray? = fila.poll(ms, TimeUnit.MILLISECONDS)
    }

    private val trinco = Any()
    private val arranque = ArrayDeque<ByteArray>() // ~3 s para quem chega começar logo
    private val ouvintes = CopyOnWriteArraySet<Ouvinte>()

    val numeroOuvintes: Int get() = ouvintes.count { it.tipo == Ouvinte.Tipo.WIFI }

    fun publicar(trama: ByteArray) = synchronized(trinco) {
        arranque.addLast(trama)
        while (arranque.size > 130) arranque.removeFirst()
        for (o in ouvintes) {
            if (!o.fila.offer(trama)) {
                // ouvinte demasiado lento: desliga-o para não atrasar os outros
                o.fechado = true
                ouvintes.remove(o)
            }
        }
    }

    fun ligar(tipo: Ouvinte.Tipo, ip: String): Ouvinte = synchronized(trinco) {
        val o = Ouvinte(tipo, ip)
        for (t in arranque) o.fila.offer(t)
        ouvintes.add(o)
        o
    }

    fun desligar(o: Ouvinte) {
        o.fechado = true
        ouvintes.remove(o)
    }

    fun desligarTodos() {
        for (o in ouvintes) desligar(o)
    }
}

/** Captura do microfone para falar por cima da música. */
class Microfone {
    private val anel = ShortArray(TAXA) // 1 s mono
    private var ler = 0
    private var escrever = 0
    private var disponiveis = 0
    private val trinco = Any()
    @Volatile private var ativo = false
    private var gravador: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun iniciar(): Boolean {
        if (ativo) return true
        val min = AudioRecord.getMinBufferSize(TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val g = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, TAXA, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        } catch (e: Exception) {
            return false
        }
        if (g.state != AudioRecord.STATE_INITIALIZED) {
            g.release()
            return false
        }
        gravador = g
        ativo = true
        g.startRecording()
        Thread({
            val buf = ShortArray(1024)
            while (ativo) {
                val n = g.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(trinco) {
                    for (i in 0 until n) {
                        anel[escrever] = buf[i]
                        escrever = (escrever + 1) % anel.size
                    }
                    disponiveis += n
                    // mantém a latência baixa: descarta o que estiver acumulado a mais
                    val maximo = TAXA / 5
                    if (disponiveis > maximo) {
                        ler = (ler + disponiveis - maximo) % anel.size
                        disponiveis = maximo
                    }
                }
            }
            g.stop()
            g.release()
        }, "microfone").start()
        return true
    }

    fun parar() {
        ativo = false
        gravador = null
        synchronized(trinco) { ler = 0; escrever = 0; disponiveis = 0 }
    }

    val ligado: Boolean get() = ativo

    /** Mistura a voz no bloco estéreo, baixando a música enquanto o microfone está ligado. */
    fun misturar(bloco: ShortArray, tramas: Int, volumeMusica: Float, ganho: Float) {
        synchronized(trinco) {
            for (i in 0 until tramas) {
                var voz = 0f
                if (disponiveis > 0) {
                    voz = anel[ler] * ganho
                    ler = (ler + 1) % anel.size
                    disponiveis--
                }
                val l = i * 2
                bloco[l] = limitar(bloco[l] * volumeMusica + voz)
                bloco[l + 1] = limitar(bloco[l + 1] * volumeMusica + voz)
            }
        }
    }
}

/**
 * Coração da rádio: lê a playlist em tempo real, mistura o microfone, toca
 * (opcionalmente) no próprio telemóvel e codifica para todos os ouvintes.
 */
class Motor(
    private val ctx: Context,
    private val playlist: Playlist,
    private val difusor: Difusor,
    private val microfone: Microfone,
    private val kbps: Int,
    private val aoMudarFaixa: (Faixa?) -> Unit,
) : Thread("motor-radio") {

    @Volatile var ativo = true
    @Volatile var pausado = false
    @Volatile var ouvirNoTelemovel = false
    @Volatile private var saltarPedido = false
    @Volatile var faixaAtual: Faixa? = null
        private set
    @Volatile var capa: ByteArray? = null
        private set
    @Volatile var versaoCapa = 0
        private set
    @Volatile private var tramasFaixa = 0L
    @Volatile var erro: String? = null
        private set

    val segundosFaixa: Int get() = (tramasFaixa / TAXA).toInt()

    fun saltar() { saltarPedido = true }

    override fun run() {
        val codificador = try {
            CodificadorAac(kbps) { difusor.publicar(it) }
        } catch (e: Exception) {
            erro = "Este telemóvel não conseguiu iniciar o codificador AAC: ${e.message}"
            Log.e("Radio", "codificador", e)
            return
        }
        var monitor: AudioTrack? = null
        val bloco = ShortArray(BLOCO * CANAIS)
        val fifo = FifoPcm()
        var descodificador: DescodificadorFaixa? = null
        var falhasSeguidas = 0
        val inicio = System.nanoTime()
        var tramasEnviadas = 0L

        try {
            while (ativo) {
                // saída local (altifalante / Bluetooth / transmissor FM)
                if (ouvirNoTelemovel && monitor == null) monitor = criarMonitor()
                if (!ouvirNoTelemovel && monitor != null) {
                    monitor.stop(); monitor.release(); monitor = null
                }

                if (saltarPedido) {
                    saltarPedido = false
                    descodificador?.close()
                    descodificador = null
                    fifo.limpar()
                }

                if (pausado) {
                    bloco.fill(0)
                } else {
                    if (descodificador == null && falhasSeguidas < 20) {
                        val faixa = playlist.proxima()
                        if (faixa != null) {
                            try {
                                descodificador = DescodificadorFaixa(ctx, faixa)
                                falhasSeguidas = 0
                                mudarFaixa(faixa)
                            } catch (e: Exception) {
                                Log.w("Radio", "Não foi possível ler ${faixa.titulo}", e)
                                falhasSeguidas++
                            }
                        } else if (faixaAtual != null) {
                            mudarFaixa(null)
                        }
                    }
                    val d = descodificador
                    if (d != null) {
                        try {
                            d.preencher(fifo, BLOCO)
                        } catch (e: Exception) {
                            Log.w("Radio", "Erro a descodificar", e)
                            d.close()
                            descodificador = null
                        }
                    }
                    val n = fifo.ler(bloco, BLOCO)
                    if (n < BLOCO) bloco.fill(0, n * CANAIS, bloco.size)
                    tramasFaixa += n
                    if (d != null && d.terminado && fifo.tramas == 0) {
                        d.close()
                        descodificador = null
                    }
                }
                if (falhasSeguidas >= 20 && tramasEnviadas % (TAXA * 10L) < BLOCO) falhasSeguidas = 0

                if (microfone.ligado) microfone.misturar(bloco, BLOCO, 0.3f, 1.6f)

                monitor?.write(bloco, 0, bloco.size)
                codificador.codificar(bloco, bloco.size, tramasEnviadas * 1_000_000L / TAXA)
                tramasEnviadas += BLOCO

                // ritmo de tempo real (no máximo 250 ms adiantado)
                val alvo = inicio + tramasEnviadas * 1_000_000_000L / TAXA
                val adiantado = alvo - System.nanoTime() - 250_000_000L
                if (adiantado > 0) sleep(adiantado / 1_000_000, (adiantado % 1_000_000).toInt())
            }
        } catch (_: InterruptedException) {
        } finally {
            descodificador?.close()
            monitor?.release()
            codificador.close()
        }
    }

    private fun mudarFaixa(faixa: Faixa?) {
        faixaAtual = faixa
        tramasFaixa = 0
        capa = faixa?.let { lerCapa(it) }
        versaoCapa++
        aoMudarFaixa(faixa)
    }

    private fun lerCapa(faixa: Faixa): ByteArray? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, faixa.uri)
            r.embeddedPicture
        } finally {
            r.release()
        }
    } catch (e: Exception) {
        null
    }

    private fun criarMonitor(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(TAXA, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TAXA)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, BLOCO * CANAIS * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        return t
    }
}
