package pt.radioportatil

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteOrder

/** Todo o áudio interno da rádio: 44,1 kHz, estéreo, 16 bits. */
const val TAXA = 44100
const val CANAIS = 2
/** Amostras por canal num bloco (= uma trama AAC). */
const val BLOCO = 1024

/** Fila FIFO de amostras estéreo intercaladas. */
class FifoPcm {
    private var dados = ShortArray(BLOCO * CANAIS * 16)
    private var inicio = 0
    private var fim = 0

    val tramas: Int get() = (fim - inicio) / CANAIS

    fun escrever(l: Short, r: Short) {
        if (fim + 2 > dados.size) {
            val n = fim - inicio
            if (n + 2 > dados.size / 2) dados = dados.copyOf(dados.size * 2)
            System.arraycopy(dados, inicio, dados, 0, n)
            inicio = 0
            fim = n
        }
        dados[fim++] = l
        dados[fim++] = r
    }

    /** Copia até [tramasPedidas] tramas para [destino]; devolve quantas copiou. */
    fun ler(destino: ShortArray, tramasPedidas: Int): Int {
        val n = minOf(tramasPedidas, tramas)
        System.arraycopy(dados, inicio, destino, 0, n * CANAIS)
        inicio += n * CANAIS
        if (inicio == fim) { inicio = 0; fim = 0 }
        return n
    }

    fun limpar() { inicio = 0; fim = 0 }
}

/** Conversão de taxa de amostragem por interpolação linear (ex.: 48 kHz → 44,1 kHz). */
class Reamostrador(taxaEntrada: Int) {
    private val passo = taxaEntrada.toDouble() / TAXA
    private val direto = taxaEntrada == TAXA
    private var pos = 0.0
    private var antL = 0f
    private var antR = 0f

    fun processar(l: FloatArray, r: FloatArray, n: Int, saida: FifoPcm) {
        if (n == 0) return
        if (direto) {
            for (i in 0 until n) saida.escrever(limitar(l[i]), limitar(r[i]))
            return
        }
        while (pos < n - 1) {
            val i = Math.floor(pos).toInt()
            val f = (pos - i).toFloat()
            val aL = if (i < 0) antL else l[i]
            val aR = if (i < 0) antR else r[i]
            saida.escrever(limitar(aL + (l[i + 1] - aL) * f), limitar(aR + (r[i + 1] - aR) * f))
            pos += passo
        }
        pos -= n
        antL = l[n - 1]
        antR = r[n - 1]
    }
}

fun limitar(v: Float): Short = when {
    v > 32767f -> 32767
    v < -32768f -> -32768
    else -> v.toInt().toShort()
}

/** Descodifica um ficheiro de música (mp3, aac, flac, ogg, wav…) para PCM 44,1 kHz estéreo. */
class DescodificadorFaixa(ctx: Context, faixa: Faixa) : Closeable {
    private val extrator = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var taxa: Int
    private var canais: Int
    private var flutuante = false
    private var reamostrador: Reamostrador
    private var bufL = FloatArray(4096)
    private var bufR = FloatArray(4096)
    private var entradaTerminada = false
    var terminado = false
        private set

    init {
        extrator.setDataSource(ctx, faixa.uri, null)
        var formato: MediaFormat? = null
        for (i in 0 until extrator.trackCount) {
            val f = extrator.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extrator.selectTrack(i)
                formato = f
                break
            }
        }
        if (formato == null) {
            extrator.release()
            throw IllegalArgumentException("Sem áudio")
        }
        taxa = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        canais = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        reamostrador = Reamostrador(taxa)
        codec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(formato, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extrator.release()
            throw e
        }
    }

    /** Descodifica até a fila ter pelo menos [minimo] tramas ou a faixa acabar. */
    fun preencher(fifo: FifoPcm, minimo: Int) {
        while (fifo.tramas < minimo && !terminado) {
            if (!entradaTerminada) {
                val iEntrada = codec.dequeueInputBuffer(10_000)
                if (iEntrada >= 0) {
                    val buf = codec.getInputBuffer(iEntrada)!!
                    val tamanho = extrator.readSampleData(buf, 0)
                    if (tamanho < 0) {
                        codec.queueInputBuffer(iEntrada, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        entradaTerminada = true
                    } else {
                        codec.queueInputBuffer(iEntrada, 0, tamanho, extrator.sampleTime, 0)
                        extrator.advance()
                    }
                }
            }
            val iSaida = codec.dequeueOutputBuffer(info, 10_000)
            when {
                iSaida >= 0 -> {
                    if (info.size > 0) converter(iSaida, fifo)
                    codec.releaseOutputBuffer(iSaida, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) terminado = true
                }
                iSaida == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    val novaTaxa = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    canais = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    flutuante = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (novaTaxa != taxa) {
                        taxa = novaTaxa
                        reamostrador = Reamostrador(taxa)
                    }
                }
            }
        }
    }

    private fun converter(indice: Int, fifo: FifoPcm) {
        val buf = codec.getOutputBuffer(indice)!!
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val ch = canais.coerceAtLeast(1)
        if (flutuante) {
            val fb = buf.asFloatBuffer()
            val n = fb.remaining() / ch
            garantir(n)
            for (i in 0 until n) {
                val l = fb.get(i * ch) * 32767f
                bufL[i] = l
                bufR[i] = if (ch >= 2) fb.get(i * ch + 1) * 32767f else l
            }
            reamostrador.processar(bufL, bufR, n, fifo)
        } else {
            val sb = buf.asShortBuffer()
            val n = sb.remaining() / ch
            garantir(n)
            for (i in 0 until n) {
                val l = sb.get(i * ch).toFloat()
                bufL[i] = l
                bufR[i] = if (ch >= 2) sb.get(i * ch + 1).toFloat() else l
            }
            reamostrador.processar(bufL, bufR, n, fifo)
        }
    }

    private fun garantir(n: Int) {
        if (bufL.size < n) {
            bufL = FloatArray(n)
            bufR = FloatArray(n)
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        extrator.release()
    }
}

/** Codifica PCM em AAC-LC e entrega tramas ADTS prontas a transmitir. */
class CodificadorAac(kbps: Int, private val aoProduzir: (ByteArray) -> Unit) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()

    init {
        val formato = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TAXA, CANAIS)
        formato.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        formato.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
        formato.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCO * CANAIS * 2 * 4)
        codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun codificar(pcm: ShortArray, amostras: Int, tempoUs: Long) {
        var desvio = 0
        while (desvio < amostras) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) {
                drenar()
                continue
            }
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            val n = minOf(buf.remaining() / 2, amostras - desvio)
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, desvio, n)
            codec.queueInputBuffer(i, 0, n * 2, tempoUs, 0)
            desvio += n
        }
        drenar()
    }

    private fun drenar() {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 0)
            if (i < 0) {
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return
                continue // mudança de formato: ignorar
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buf = codec.getOutputBuffer(i)!!
                buf.position(info.offset)
                val trama = ByteArray(info.size + 7)
                cabecalhoAdts(trama, trama.size)
                buf.get(trama, 7, info.size)
                aoProduzir(trama)
            }
            codec.releaseOutputBuffer(i, false)
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    companion object {
        /** Cabeçalho ADTS de 7 bytes: AAC-LC, 44,1 kHz, 2 canais. */
        fun cabecalhoAdts(b: ByteArray, comprimento: Int) {
            val perfil = 2 // AAC LC
            val freq = 4 // 44100 Hz
            val canais = 2
            b[0] = 0xFF.toByte()
            b[1] = 0xF1.toByte()
            b[2] = (((perfil - 1) shl 6) + (freq shl 2) + (canais shr 2)).toByte()
            b[3] = (((canais and 3) shl 6) + (comprimento shr 11)).toByte()
            b[4] = ((comprimento and 0x7FF) shr 3).toByte()
            b[5] = (((comprimento and 7) shl 5) + 0x1F).toByte()
            b[6] = 0xFC.toByte()
        }
    }
}
