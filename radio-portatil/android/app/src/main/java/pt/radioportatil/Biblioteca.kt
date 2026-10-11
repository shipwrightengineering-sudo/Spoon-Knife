package pt.radioportatil

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Faixa(
    val id: Long,
    val uri: Uri,
    val titulo: String,
    val artista: String,
    val duracaoMs: Long,
) {
    val nomeCompleto: String get() = if (artista.isBlank()) titulo else "$artista — $titulo"
}

object Biblioteca {
    /** Todas as músicas do telemóvel (MediaStore), por ordem alfabética. */
    fun carregar(ctx: Context): List<Faixa> {
        val colecao = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val campos = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val faixas = ArrayList<Faixa>()
        try {
            ctx.contentResolver.query(
                colecao, campos,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitulo = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtista = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iDuracao = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val artista = c.getString(iArtista).orEmpty().let { if (it == "<unknown>") "" else it }
                    faixas += Faixa(
                        id = id,
                        uri = ContentUris.withAppendedId(colecao, id),
                        titulo = c.getString(iTitulo).orEmpty().ifBlank { "Sem título" },
                        artista = artista,
                        duracaoMs = c.getLong(iDuracao),
                    )
                }
            }
        } catch (e: SecurityException) {
            // sem permissão de leitura: lista vazia
        }
        return faixas
    }
}

/** Ordem de emissão: pedidos dos ouvintes primeiro, depois a playlist (normal ou aleatória). */
class Playlist {
    data class Pedido(val faixa: Faixa, val ip: String)

    private var faixas: List<Faixa> = emptyList()
    private var ordem: MutableList<Int> = mutableListOf()
    private var posicao = 0
    private val aSeguir = ArrayDeque<Pedido>()

    var aleatorio = false
        @Synchronized set(valor) {
            if (field != valor) {
                field = valor
                reordenar()
            }
        }

    @get:Synchronized
    val todas: List<Faixa> get() = faixas

    @Synchronized
    fun definir(lista: List<Faixa>) {
        faixas = lista
        reordenar()
    }

    private fun reordenar() {
        ordem = faixas.indices.toMutableList()
        if (aleatorio) ordem.shuffle()
        posicao = 0
    }

    @Synchronized
    fun proxima(): Faixa? {
        aSeguir.removeFirstOrNull()?.let { return it.faixa }
        if (faixas.isEmpty()) return null
        if (posicao >= ordem.size) reordenar()
        return faixas[ordem[posicao++]]
    }

    /** Coloca a faixa no topo da fila (o DJ escolheu-a). */
    @Synchronized
    fun tocarJa(faixa: Faixa) {
        aSeguir.addFirst(Pedido(faixa, "dj"))
    }

    /** Pedido de um ouvinte. Devolve uma mensagem para mostrar na página. */
    @Synchronized
    fun pedir(id: Long, ip: String): String {
        val faixa = faixas.firstOrNull { it.id == id } ?: return "Música não encontrada"
        if (aSeguir.any { it.faixa.id == id }) return "Essa música já está na fila 👍"
        if (aSeguir.count { it.ip == ip } >= 3) return "Já tens 3 pedidos na fila — espera um pouco"
        if (aSeguir.size >= 30) return "A fila de pedidos está cheia"
        aSeguir.addLast(Pedido(faixa, ip))
        return "Pedido aceite! Posição ${aSeguir.size} na fila 🎶"
    }

    @Synchronized
    fun fila(): List<Faixa> = aSeguir.map { it.faixa }
}
