package pt.radioportatil

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private var servico: RadioService? = null
    private val principal = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs.de(this) }

    private lateinit var botaoNoAr: Button
    private lateinit var textoFaixa: TextView
    private lateinit var textoInfo: TextView
    private lateinit var imagemCapa: ImageView
    private lateinit var botaoPausa: Button
    private lateinit var painelControlo: LinearLayout
    private lateinit var textoEnderecos: TextView
    private lateinit var imagemQrLink: ImageView
    private lateinit var textoRede: TextView
    private lateinit var imagemQrRede: ImageView
    private lateinit var interruptorMic: Switch
    private lateinit var textoInternet: TextView
    private lateinit var interruptorInternet: Switch

    private var versaoCapaMostrada = -1
    private var linkMostrado: String? = null
    private var redeMostrada: Pair<String, String>? = null
    private var acaoAposPermissao: (() -> Unit)? = null
    private var ligadoAoServico = false
    private var aAtualizar = false

    private val conexao = object : ServiceConnection {
        override fun onServiceConnected(nome: ComponentName?, b: IBinder?) {
            servico = (b as RadioService.Ligacao).servico
            atualizar()
        }

        override fun onServiceDisconnected(nome: ComponentName?) {
            servico = null
        }
    }

    private val ciclo = object : Runnable {
        override fun run() {
            atualizar()
            principal.postDelayed(this, 1000)
        }
    }

    // ============================================================ ecrã

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = vertical().apply { setPadding(dp(18), dp(24), dp(18), dp(40)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(FUNDO)
            isFillViewport = true
            addView(raiz)
        })

        raiz.addView(texto("📻 Rádio Portátil", 26f, negrito = true))
        raiz.addView(texto("Transmite as tuas músicas para quem estiver ligado ao teu Wi-Fi", 14f, cor = CINZA).margem(baixo = 16))

        raiz.addView(rotulo("Nome da estação"))
        raiz.addView(campo(Prefs.NOME, "Rádio Portátil"))

        botaoNoAr = botao("▶  PÔR NO AR", VERMELHO, grande = true) { alternarEmissao() }
        raiz.addView(botaoNoAr.margem(cima = 14, baixo = 6))

        // ------------------------------------------------ painel "no ar"
        painelControlo = cartao().apply { visibility = View.GONE }
        imagemCapa = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        painelControlo.addView(imagemCapa)
        textoFaixa = texto("", 18f, negrito = true).apply { gravity = Gravity.CENTER }
        textoInfo = texto("", 14f, cor = CINZA).apply { gravity = Gravity.CENTER }
        painelControlo.addView(textoFaixa.margem(cima = 10))
        painelControlo.addView(textoInfo.margem(cima = 4, baixo = 10))
        val linha = horizontal()
        botaoPausa = botao("⏸ Pausa", CINZENTO) { servico?.alternarPausa(); atualizar() }
        linha.addView(botaoPausa, peso())
        linha.addView(botao("⏭ Próxima", CINZENTO) { servico?.proxima() }, peso(esquerda = 8))
        painelControlo.addView(linha)
        painelControlo.addView(botao("🎵 Escolher música", CINZENTO) { escolherMusica() }.margem(cima = 8))
        raiz.addView(painelControlo.margem(cima = 10))

        // ------------------------------------------------ opções
        raiz.addView(titulo("Opções"))
        raiz.addView(interruptor("🔀 Ordem aleatória", Prefs.ALEATORIO, false) { servico?.definirAleatorio(it) })
        raiz.addView(interruptor("🔊 Tocar também neste telemóvel", Prefs.MONITOR, false) { servico?.definirOuvirNoTelemovel(it) })
        raiz.addView(texto("Liga isto para ouvir no altifalante, numa coluna Bluetooth ou num transmissor FM Bluetooth (para sintonizar em qualquer rádio).", 13f, cor = CINZA))
        interruptorMic = Switch(this).apply {
            text = "🎙️ Microfone ao vivo (falar por cima da música)"
            setTextColor(BRANCO)
            textSize = 16f
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, ligado -> if (!aAtualizar) alternarMicrofone(ligado) }
        }
        raiz.addView(interruptorMic)
        raiz.addView(texto("A música baixa enquanto falas. Usa auscultadores para evitar apitos.", 13f, cor = CINZA))
        raiz.addView(interruptor("🙋 Ouvintes podem pedir músicas", Prefs.PEDIDOS, true) {})

        raiz.addView(rotulo("Qualidade do som").margem(cima = 12))
        val qualidades = listOf(64, 96, 128, 160, 192, 256)
        raiz.addView(Spinner(this).apply {
            adapter = adaptador(qualidades.map { "$it kbps" + if (it == 128) " (recomendado)" else "" })
            setSelection(qualidades.indexOf(prefs.getInt(Prefs.KBPS, 128)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt(Prefs.KBPS, qualidades[pos]).apply()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        raiz.addView(texto("Aplica-se da próxima vez que puseres no ar. Com muitos ouvintes, usa menos.", 13f, cor = CINZA))

        // ------------------------------------------------ ouvintes
        raiz.addView(titulo("Como os ouvintes se ligam"))
        raiz.addView(texto("1. Ficam na mesma rede que tu: o teu hotspot, um router Wi-Fi, ou a rede da rádio (abaixo).\n2. Abrem no navegador o endereço ou leem o código QR:", 14f))
        textoEnderecos = texto("", 18f, negrito = true, cor = VERMELHO_CLARO).apply { setTextIsSelectable(true) }
        raiz.addView(textoEnderecos.margem(cima = 8))
        imagemQrLink = qr()
        raiz.addView(imagemQrLink)

        raiz.addView(botao("📶 Criar rede Wi-Fi da rádio", CINZENTO) { criarRede() }.margem(cima = 12))
        raiz.addView(botao("⚙️ Abrir definições do hotspot", CINZENTO) { abrirHotspot() }.margem(cima = 8))
        textoRede = texto("", 14f).margem(cima = 8)
        raiz.addView(textoRede)
        imagemQrRede = qr()
        raiz.addView(imagemQrRede)

        // ------------------------------------------------ internet
        raiz.addView(titulo("Emitir para a internet (opcional)"))
        raiz.addView(texto("Para te ouvirem em qualquer lado, precisas de um servidor Icecast (há gratuitos e pagos). Introduz os dados que o servidor te deu:", 14f, cor = CINZA))
        raiz.addView(rotulo("Servidor (ex.: meuservidor.com)").margem(cima = 8))
        raiz.addView(campo(Prefs.ICE_HOST, ""))
        val l2 = horizontal()
        l2.addView(vertical().apply { addView(rotulo("Porta")); addView(campo(Prefs.ICE_PORTA, "8000", numerico = true)) }, peso())
        l2.addView(vertical().apply { addView(rotulo("Ponto de montagem")); addView(campo(Prefs.ICE_MONTAGEM, "/radio")) }, peso(esquerda = 8))
        raiz.addView(l2)
        val l3 = horizontal()
        l3.addView(vertical().apply { addView(rotulo("Utilizador")); addView(campo(Prefs.ICE_UTILIZADOR, "source")) }, peso())
        l3.addView(vertical().apply { addView(rotulo("Senha")); addView(campo(Prefs.ICE_SENHA, "", senha = true)) }, peso(esquerda = 8))
        raiz.addView(l3)
        interruptorInternet = interruptor("🌍 Emitir para a internet", Prefs.INTERNET, false) { ligado ->
            if (ligado) servico?.takeIf { it.noAr }?.ligarInternet() else servico?.desligarInternet()
        }
        raiz.addView(interruptorInternet)
        textoInternet = texto("", 14f, cor = CINZA)
        raiz.addView(textoInternet)

        // ------------------------------------------------ bateria
        raiz.addView(titulo("Para não ser desligada"))
        raiz.addView(texto("Na Xiaomi (HyperOS/MIUI), permite que a app funcione em segundo plano: Poupança de bateria → Sem restrições, e ativa o Início automático.", 14f, cor = CINZA))
        raiz.addView(botao("🔋 Permitir funcionar em segundo plano", CINZENTO) { pedirSemOtimizacao() }.margem(cima = 8))
        raiz.addView(botao("📱 Definições da app", CINZENTO) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.margem(cima = 8))

        raiz.addView(titulo("Sobre o FM"))
        raiz.addView(texto("Os telemóveis não têm emissor FM (o chip FM só recebe). Para sintonizar num rádio FM, emparelha um transmissor FM Bluetooth, liga \"Tocar também neste telemóvel\" e sintoniza o rádio na frequência do transmissor. Usa só aparelhos certificados.", 14f, cor = CINZA))

        if (Build.VERSION.SDK_INT >= 33) pedirPermissoes(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {}
    }

    override fun onStart() {
        super.onStart()
        ligadoAoServico = bindService(Intent(this, RadioService::class.java), conexao, Context.BIND_AUTO_CREATE)
        principal.post(ciclo)
    }

    override fun onStop() {
        principal.removeCallbacks(ciclo)
        if (ligadoAoServico) unbindService(conexao)
        ligadoAoServico = false
        servico = null
        super.onStop()
    }

    // ============================================================ ações

    private fun alternarEmissao() {
        val s = servico
        if (s != null && s.noAr) {
            startService(Intent(this, RadioService::class.java).setAction(RadioService.ACAO_DESLIGAR))
            aAtualizar = true
            interruptorMic.isChecked = false
            aAtualizar = false
            principal.postDelayed({ atualizar() }, 300)
            return
        }
        pedirPermissoes(arrayOf(permissaoMusica())) { concedidas ->
            if (!concedidas) {
                Toast.makeText(this, "Sem acesso às músicas não há nada para tocar", Toast.LENGTH_LONG).show()
                return@pedirPermissoes
            }
            servico?.recarregarMusicas()
            startForegroundService(Intent(this, RadioService::class.java).setAction(RadioService.ACAO_LIGAR))
            principal.postDelayed({ atualizar() }, 500)
        }
    }

    private fun alternarMicrofone(ligar: Boolean) {
        val s = servico
        if (!ligar) {
            s?.definirMicrofone(false)
            return
        }
        if (s == null || !s.noAr) {
            Toast.makeText(this, "Põe a rádio no ar primeiro", Toast.LENGTH_SHORT).show()
            interruptorMic.isChecked = false
            return
        }
        pedirPermissoes(arrayOf(Manifest.permission.RECORD_AUDIO)) { ok ->
            if (!ok || !s.definirMicrofone(true)) {
                Toast.makeText(this, "Não foi possível usar o microfone", Toast.LENGTH_LONG).show()
                interruptorMic.isChecked = false
            }
        }
    }

    private fun escolherMusica() {
        val s = servico ?: return
        val todas = s.playlist.todas
        if (todas.isEmpty()) {
            Toast.makeText(this, "Ainda não há músicas carregadas", Toast.LENGTH_SHORT).show()
            return
        }
        var filtradas = todas
        val adapt = adaptador(filtradas.map { it.nomeCompleto }, escuro = false)
        val pesquisa = EditText(this).apply { hint = "Pesquisar…"; isSingleLine = true }
        val lista = ListView(this).apply { adapter = adapt }
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(pesquisa)
            addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Tocar agora (${todas.size} músicas)")
            .setView(caixa)
            .setNegativeButton("Fechar", null)
            .create()
        pesquisa.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                val q = e.toString().trim().lowercase()
                filtradas = if (q.isEmpty()) todas else todas.filter { q in it.nomeCompleto.lowercase() }
                adapt.clear()
                adapt.addAll(filtradas.map { it.nomeCompleto })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        lista.setOnItemClickListener { _, _, pos, _ ->
            s.tocarJa(filtradas[pos])
            dialogo.dismiss()
        }
        dialogo.show()
    }

    private fun criarRede() {
        val s = servico ?: return
        if (!s.noAr) {
            Toast.makeText(this, "Põe a rádio no ar primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        pedirPermissoes(perms) { ok ->
            if (ok) s.criarRedeWifi()
            else Toast.makeText(this, "Sem essa permissão não é possível criar a rede", Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirHotspot() {
        val tentativas = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in tentativas) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun pedirSemOtimizacao() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Já está permitido ✔", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ============================================================ atualização do ecrã

    private fun atualizar() {
        val s = servico
        val noAr = s?.noAr == true
        botaoNoAr.text = if (noAr) "■  DESLIGAR EMISSÃO" else "▶  PÔR NO AR"
        (botaoNoAr.background as? GradientDrawable)?.setColor(if (noAr) CINZENTO else VERMELHO)
        painelControlo.visibility = if (noAr) View.VISIBLE else View.GONE
        s?.erro?.let { textoInfo.text = it }

        if (noAr) {
            val f = s.faixaAtual
            val erroMotor = s.erroMotor
            textoFaixa.text = when {
                erroMotor != null -> erroMotor
                f != null -> f.titulo
                s.playlist.todas.isEmpty() -> "Sem músicas encontradas no telemóvel"
                else -> "A preparar…"
            }
            val ouvintes = s.difusor.numeroOuvintes
            val fila = s.playlist.fila()
            textoInfo.text = buildString {
                if (f != null && f.artista.isNotBlank()) append(f.artista).append("\n")
                if (f != null) append(tempo(s.segundosFaixa)).append(" / ").append(tempo((f.duracaoMs / 1000).toInt())).append("  ·  ")
                append(if (ouvintes == 1) "1 ouvinte" else "$ouvintes ouvintes")
                if (s.pausado) append("  ·  EM PAUSA")
                if (fila.isNotEmpty()) append("\nA seguir: ").append(fila.take(3).joinToString(", ") { it.titulo })
            }
            botaoPausa.text = if (s.pausado) "▶ Retomar" else "⏸ Pausa"
            aAtualizar = true
            interruptorMic.isChecked = s.microfoneLigado
            aAtualizar = false

            if (s.versaoCapa != versaoCapaMostrada) {
                versaoCapaMostrada = s.versaoCapa
                val c = s.capa
                val bmp = c?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                imagemCapa.setImageBitmap(bmp)
                imagemCapa.visibility = if (bmp != null) View.VISIBLE else View.GONE
            }
        }

        // endereços
        val ips = Servidor.enderecosLocais()
        val porta = s?.porta ?: RadioService.PORTA
        textoEnderecos.text = when {
            !noAr -> "(põe a rádio no ar para ver o endereço)"
            ips.isEmpty() -> "Liga o Wi-Fi ou o hotspot para os ouvintes se poderem ligar"
            else -> ips.joinToString("\n") { "http://$it:$porta" }
        }
        val link = if (noAr) ips.firstOrNull()?.let { "http://$it:$porta" } else null
        if (link != linkMostrado) {
            linkMostrado = link
            mostrarQr(imagemQrLink, link)
        }

        // rede própria
        val rede = s?.redeWifi
        textoRede.text = when {
            rede != null -> "Rede da rádio ativa:\nNome: ${rede.first}\nSenha: ${rede.second}\n\nOs ouvintes leem este código para se ligarem à rede:"
            s?.estadoRede != null -> s.estadoRede
            else -> "Cria uma rede Wi-Fi só para a rádio (sem gastar dados). Em alternativa, liga o hotspot normal."
        }
        if (rede != redeMostrada) {
            redeMostrada = rede
            mostrarQr(imagemQrRede, rede?.let { "WIFI:T:WPA;S:${escaparWifi(it.first)};P:${escaparWifi(it.second)};;" })
        }

        textoInternet.text = s?.estadoInternet ?: ""
    }

    private fun mostrarQr(iv: ImageView, conteudo: String?) {
        if (conteudo == null) {
            iv.visibility = View.GONE
            return
        }
        iv.setImageBitmap(gerarQr(conteudo, dp(220)))
        iv.visibility = View.VISIBLE
    }

    // ============================================================ permissões

    private fun permissaoMusica() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun pedirPermissoes(perms: Array<String>, depois: (Boolean) -> Unit) {
        val faltam = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltam.isEmpty()) {
            depois(true)
            return
        }
        acaoAposPermissao = { depois(perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) }
        requestPermissions(faltam.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val acao = acaoAposPermissao
        acaoAposPermissao = null
        // o serviço pode ainda não estar ligado: espera um instante
        principal.postDelayed({ acao?.invoke() }, 150)
    }

    // ============================================================ peças do ecrã

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun peso(esquerda: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(esquerda) }

    private fun <T : View> T.margem(cima: Int = 0, baixo: Int = 0): T {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(cima); bottomMargin = dp(baixo) }
        return this
    }

    private fun texto(t: String, tamanho: Float, negrito: Boolean = false, cor: Int = BRANCO) = TextView(this).apply {
        text = t
        textSize = tamanho
        setTextColor(cor)
        if (negrito) setTypeface(typeface, Typeface.BOLD)
    }

    private fun titulo(t: String) = texto(t, 19f, negrito = true).margem(cima = 28, baixo = 6)
    private fun rotulo(t: String) = texto(t, 13f, cor = CINZA)

    private fun fundo(cor: Int, raio: Int) = GradientDrawable().apply {
        setColor(cor)
        cornerRadius = dp(raio).toFloat()
    }

    private fun botao(t: String, cor: Int, grande: Boolean = false, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(BRANCO)
        textSize = if (grande) 20f else 15f
        background = fundo(cor, 28)
        stateListAnimator = null
        setPadding(dp(16), dp(if (grande) 18 else 10), dp(16), dp(if (grande) 18 else 10))
        setOnClickListener { acao() }
    }

    private fun cartao() = vertical().apply {
        background = fundo(CARTAO, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun campo(chave: String, padrao: String, numerico: Boolean = false, senha: Boolean = false) = EditText(this).apply {
        setText(prefs.getString(chave, padrao))
        setTextColor(BRANCO)
        setHintTextColor(CINZA)
        isSingleLine = true
        inputType = when {
            senha -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numerico -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { prefs.edit().putString(chave, e.toString()).apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun interruptor(t: String, chave: String, padrao: Boolean, aoMudar: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        setTextColor(BRANCO)
        textSize = 16f
        setPadding(0, dp(10), 0, dp(4))
        isChecked = prefs.getBoolean(chave, padrao)
        setOnCheckedChangeListener { _, ligado ->
            prefs.edit().putBoolean(chave, ligado).apply()
            aoMudar(ligado)
        }
    }

    private fun qr() = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        }
        visibility = View.GONE
    }

    private fun adaptador(itens: List<String>, escuro: Boolean = true): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList(itens)) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                if (escuro) v.setTextColor(BRANCO)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent)
        }

    companion object {
        val FUNDO = Color.parseColor("#121214")
        val CARTAO = Color.parseColor("#1E1E22")
        val VERMELHO = Color.parseColor("#E5484D")
        val VERMELHO_CLARO = Color.parseColor("#FF8A8E")
        val CINZENTO = Color.parseColor("#2E2E34")
        val CINZA = Color.parseColor("#A0A0A8")
        val BRANCO = Color.WHITE

        fun tempo(s: Int) = "%d:%02d".format(s / 60, s % 60)

        fun escaparWifi(s: String) = s.replace(Regex("([\\\\;,:\"])"), "\\\\$1")

        fun gerarQr(conteudo: String, tamanho: Int): Bitmap {
            val m = QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, tamanho, tamanho, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(tamanho * tamanho)
            for (y in 0 until tamanho) for (x in 0 until tamanho) {
                px[y * tamanho + x] = if (m[x, y]) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(px, tamanho, tamanho, Bitmap.Config.ARGB_8888)
        }
    }
}
