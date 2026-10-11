package pt.radioportatil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlin.concurrent.thread

class RadioService : Service(), EstadoRadio {

    inner class Ligacao : Binder() {
        val servico: RadioService get() = this@RadioService
    }

    private val ligacao = Ligacao()
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    override val playlist = Playlist()
    override val difusor = Difusor()
    private val microfone = Microfone()
    private var motor: Motor? = null
    private var servidor: Servidor? = null
    private var icecast: ClienteIcecast? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var sessao: MediaSession? = null
    private var bloqueioCpu: PowerManager.WakeLock? = null
    private var bloqueioWifi: WifiManager.WifiLock? = null

    @Volatile var noAr = false
        private set
    var erro: String? = null
        private set
    /** Dados da rede Wi-Fi criada pela app (nome, senha), se existir. */
    var redeWifi: Pair<String, String>? = null
        private set
    var estadoRede: String? = null
        private set

    override val nomeEstacao: String get() = prefs.getString(Prefs.NOME, null)?.ifBlank { null } ?: "Rádio Portátil"
    override val kbps: Int get() = prefs.getInt(Prefs.KBPS, 128)
    override val pedidosAtivos: Boolean get() = prefs.getBoolean(Prefs.PEDIDOS, true)
    override val faixaAtual: Faixa? get() = motor?.faixaAtual
    override val segundosFaixa: Int get() = motor?.segundosFaixa ?: 0
    override val pausado: Boolean get() = motor?.pausado ?: false
    override val capa: ByteArray? get() = motor?.capa
    override val versaoCapa: Int get() = motor?.versaoCapa ?: 0
    val porta: Int get() = servidor?.porta ?: PORTA
    val microfoneLigado: Boolean get() = microfone.ligado
    val estadoInternet: String? get() = icecast?.estado
    val erroMotor: String? get() = motor?.erro

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.de(this)
        playlist.aleatorio = prefs.getBoolean(Prefs.ALEATORIO, false)
    }

    override fun onBind(intent: Intent?): IBinder = ligacao

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_LIGAR -> ligar()
            ACAO_DESLIGAR -> desligar()
            ACAO_PROXIMA -> proxima()
            ACAO_PAUSA -> alternarPausa()
            null -> if (!noAr) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- emissão

    fun ligar() {
        if (noAr) return
        erro = null
        iniciarPrimeiroPlano(false)
        val srv = Servidor(this, PORTA) { assets.open("index.html").use { it.readBytes() } }
        try {
            srv.iniciar()
        } catch (e: Exception) {
            erro = "Não foi possível abrir a porta $PORTA: ${e.message}"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        servidor = srv
        noAr = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        bloqueioCpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioPortatil::emissao").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        bloqueioWifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioPortatil::wifi").apply { acquire() }

        criarSessaoMultimedia()

        thread(name = "carregar-musicas") {
            if (playlist.todas.isEmpty()) playlist.definir(Biblioteca.carregar(this))
            principal.post {
                if (!noAr) return@post
                val m = Motor(this, playlist, difusor, microfone, kbps) { principal.post { atualizarNotificacao() } }
                m.ouvirNoTelemovel = prefs.getBoolean(Prefs.MONITOR, false)
                motor = m
                m.start()
                if (prefs.getBoolean(Prefs.INTERNET, false)) ligarInternet()
            }
        }
    }

    fun desligar() {
        noAr = false
        motor?.ativo = false
        motor?.interrupt()
        motor = null
        microfone.parar()
        icecast?.parar()
        icecast = null
        servidor?.parar()
        servidor = null
        difusor.desligarTodos()
        pararRedeWifi()
        sessao?.release()
        sessao = null
        bloqueioCpu?.let { if (it.isHeld) it.release() }
        bloqueioWifi?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun proxima() = motor?.saltar()

    fun alternarPausa() {
        motor?.let { it.pausado = !it.pausado }
        atualizarNotificacao()
    }

    fun tocarJa(f: Faixa) {
        playlist.tocarJa(f)
        motor?.pausado = false
        motor?.saltar()
    }

    fun recarregarMusicas() {
        thread { playlist.definir(Biblioteca.carregar(this)) }
    }

    fun definirAleatorio(v: Boolean) {
        prefs.edit().putBoolean(Prefs.ALEATORIO, v).apply()
        playlist.aleatorio = v
    }

    fun definirOuvirNoTelemovel(v: Boolean) {
        prefs.edit().putBoolean(Prefs.MONITOR, v).apply()
        motor?.ouvirNoTelemovel = v
    }

    /** Liga/desliga o microfone. Tem de ser chamado com a app visível (regra do Android). */
    fun definirMicrofone(v: Boolean): Boolean {
        if (!v) {
            microfone.parar()
            if (noAr) iniciarPrimeiroPlano(false)
            return true
        }
        if (!noAr) return false
        return try {
            iniciarPrimeiroPlano(true)
            microfone.iniciar()
        } catch (e: Exception) {
            false
        }
    }

    fun ligarInternet() {
        icecast?.parar()
        val host = prefs.getString(Prefs.ICE_HOST, "").orEmpty().trim()
        if (host.isEmpty()) return
        icecast = ClienteIcecast(
            this, host,
            prefs.getString(Prefs.ICE_PORTA, "8000")?.toIntOrNull() ?: 8000,
            prefs.getString(Prefs.ICE_MONTAGEM, "/radio").orEmpty().ifBlank { "/radio" },
            prefs.getString(Prefs.ICE_SENHA, "").orEmpty(),
            prefs.getString(Prefs.ICE_UTILIZADOR, "source").orEmpty().ifBlank { "source" },
        ).also { it.iniciar() }
    }

    fun desligarInternet() {
        icecast?.parar()
        icecast = null
    }

    // ---------------------------------------------------------- rede Wi-Fi própria

    /** Cria uma rede Wi-Fi só da rádio (sem internet) a que os ouvintes se ligam. */
    fun criarRedeWifi() {
        if (hotspot != null) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        estadoRede = "A criar a rede…"
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reserva: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reserva
                    redeWifi = lerCredenciais(reserva)
                    estadoRede = null
                }

                override fun onStopped() {
                    hotspot = null
                    redeWifi = null
                    estadoRede = "A rede Wi-Fi da rádio foi desligada"
                }

                override fun onFailed(motivo: Int) {
                    hotspot = null
                    redeWifi = null
                    estadoRede = when (motivo) {
                        ERROR_INCOMPATIBLE_MODE -> "Não foi possível: desliga o hotspot normal (ou usa-o em vez desta opção)"
                        ERROR_TETHERING_DISALLOWED -> "Este telemóvel não permite criar a rede"
                        else -> "Não foi possível criar a rede (erro $motivo). Usa o hotspot normal do telemóvel."
                    }
                }
            }, principal)
        } catch (e: SecurityException) {
            estadoRede = "Falta a permissão (e, em Android antigo, a localização ligada)"
        } catch (e: Exception) {
            estadoRede = "Não foi possível criar a rede: ${e.message}"
        }
    }

    fun pararRedeWifi() {
        hotspot?.close()
        hotspot = null
        redeWifi = null
    }

    @Suppress("DEPRECATION")
    private fun lerCredenciais(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            (ssid ?: return null) to (c.passphrase ?: "")
        } else {
            val c = r.wifiConfiguration ?: return null
            c.SSID.trim('"') to (c.preSharedKey?.trim('"') ?: "")
        }
    }

    // ------------------------------------------------------------ notificação

    private fun iniciarPrimeiroPlano(comMicrofone: Boolean) {
        val n = notificacao()
        if (Build.VERSION.SDK_INT >= 29) {
            var tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (comMicrofone && Build.VERSION.SDK_INT >= 30) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICACAO, n, tipo)
        } else {
            startForeground(NOTIFICACAO, n)
        }
    }

    fun atualizarNotificacao() {
        if (!noAr) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICACAO, notificacao())
        atualizarSessao()
    }

    private fun notificacao(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Emissão", NotificationManager.IMPORTANCE_LOW))
        }
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun acao(acao: String, pedido: Int) = PendingIntent.getService(
            this, pedido, Intent(this, RadioService::class.java).setAction(acao), PendingIntent.FLAG_IMMUTABLE,
        )
        val f = faixaAtual
        val ip = Servidor.enderecosLocais().firstOrNull()
        val texto = buildString {
            append(f?.nomeCompleto ?: "A preparar…")
            if (ip != null) append(" · http://$ip:$porta")
        }
        val b = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle("📻 $nomeEstacao no ar")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(botao(if (pausado) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (pausado) "Retomar" else "Pausa", acao(ACAO_PAUSA, 1)))
            .addAction(botao(android.R.drawable.ic_media_next, "Próxima", acao(ACAO_PROXIMA, 2)))
            .addAction(botao(android.R.drawable.ic_menu_close_clear_cancel, "Desligar", acao(ACAO_DESLIGAR, 3)))
        sessao?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1)) }
        return b.build()
    }

    private fun botao(icone: Int, texto: String, acao: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, icone), texto, acao).build()

    private fun criarSessaoMultimedia() {
        val s = MediaSession(this, "RadioPortatil")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = alternarPausa()
            override fun onPause() = alternarPausa()
            override fun onSkipToNext() { proxima() }
            override fun onStop() = desligar()
        })
        s.isActive = true
        sessao = s
        atualizarSessao()
    }

    private fun atualizarSessao() {
        val s = sessao ?: return
        val f = faixaAtual
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, f?.titulo ?: nomeEstacao)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, f?.artista ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, nomeEstacao)
                .build()
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP)
                .setState(if (pausado) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    override fun onDestroy() {
        if (noAr) desligar()
        super.onDestroy()
    }

    companion object {
        const val PORTA = 8000
        const val CANAL = "emissao"
        const val NOTIFICACAO = 1
        const val ACAO_LIGAR = "pt.radioportatil.LIGAR"
        const val ACAO_DESLIGAR = "pt.radioportatil.DESLIGAR"
        const val ACAO_PROXIMA = "pt.radioportatil.PROXIMA"
        const val ACAO_PAUSA = "pt.radioportatil.PAUSA"
    }
}

object Prefs {
    const val NOME = "nome"
    const val KBPS = "kbps"
    const val ALEATORIO = "aleatorio"
    const val MONITOR = "monitor"
    const val PEDIDOS = "pedidos"
    const val INTERNET = "internet"
    const val ICE_HOST = "ice_host"
    const val ICE_PORTA = "ice_porta"
    const val ICE_MONTAGEM = "ice_montagem"
    const val ICE_UTILIZADOR = "ice_utilizador"
    const val ICE_SENHA = "ice_senha"

    fun de(ctx: Context): SharedPreferences = ctx.getSharedPreferences("radio", Context.MODE_PRIVATE)
}
