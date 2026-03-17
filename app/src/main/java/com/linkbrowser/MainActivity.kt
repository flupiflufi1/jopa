package com.linkbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.linkbrowser.databinding.ActivityMainBinding
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Browser mode ─────────────────────────────────────────────────────────
    private var isNormalMode = false

    // ── Link-browser state ───────────────────────────────────────────────────
    private val trailingNumberRegex = Regex("""(\d+)(?=[/?#]|$)""")
    private val allowedDomain = "ad.adtng.com"
    private var currentAdtngUrl = ""
    private var baseUrl = ""
    private var isScanning = false
    private var scanDelta = +1
    private val maxScanAttempts = 50
    private var scanAttempts = 0

    // ── Settings state ───────────────────────────────────────────────────────
    private var settingsOpen = false
    private var vpnEnabled = false

    // ── VPN servers ──────────────────────────────────────────────────────────
    data class VpnServer(val name: String, val ip: String, val port: Int,
                         val type: String, val country: String)

    private val servers = mutableListOf<VpnServer>()
    private var selectedServerIndex = 0

    private val countryFlags = mapOf(
        "US" to "🇺🇸", "USA" to "🇺🇸", "United States" to "🇺🇸",
        "GB" to "🇬🇧", "UK" to "🇬🇧", "United Kingdom" to "🇬🇧",
        "DE" to "🇩🇪", "Germany" to "🇩🇪",
        "NL" to "🇳🇱", "Netherlands" to "🇳🇱",
        "FR" to "🇫🇷", "France" to "🇫🇷",
        "CA" to "🇨🇦", "Canada" to "🇨🇦",
        "AU" to "🇦🇺", "Australia" to "🇦🇺",
        "JP" to "🇯🇵", "Japan" to "🇯🇵",
        "SG" to "🇸🇬", "Singapore" to "🇸🇬",
        "SE" to "🇸🇪", "Sweden" to "🇸🇪"
    )

    private val fallbackServers = listOf(
        VpnServer("USA",         "72.10.164.178",  11207, "socks", "US"),
        VpnServer("UK",          "51.79.52.80",    3080,  "socks", "GB"),
        VpnServer("Germany",     "185.220.101.45", 9050,  "socks", "DE"),
        VpnServer("Netherlands", "45.142.212.10",  9050,  "socks", "NL")
    )

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var serverBtns:  List<LinearLayout>
    private lateinit var serverFlags: List<TextView>
    private lateinit var serverNames: List<TextView>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverBtns  = listOf(binding.serverBtn0,  binding.serverBtn1,  binding.serverBtn2,  binding.serverBtn3)
        serverFlags = listOf(binding.serverFlag0, binding.serverFlag1, binding.serverFlag2, binding.serverFlag3)
        serverNames = listOf(binding.serverName0, binding.serverName1, binding.serverName2, binding.serverName3)

        setupWebView()
        setupButtons()
        setupSettings()
        loadVpnServers()
    }

    // ── WebView ──────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (!isNormalMode && getDomain(url) != allowedDomain) {
                        view.stopLoading()
                        if (isScanning) handler.post { skipToNext() }
                        return true
                    }
                    if (!isNormalMode) currentAdtngUrl = url
                    updateAddressBar(url)
                    updateIdBadge(url)
                    return false
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!isNormalMode && getDomain(url) != allowedDomain) {
                        view.stopLoading()
                        if (isScanning) handler.post { skipToNext() }
                        return
                    }
                    if (!isNormalMode) currentAdtngUrl = url
                    updateAddressBar(url)
                    updateIdBadge(url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!isNormalMode && getDomain(url) != allowedDomain) {
                        view.stopLoading()
                        if (isScanning) skipToNext()
                        return
                    }
                    if (!isNormalMode && isScanning) stopScanning()
                    if (!isNormalMode) currentAdtngUrl = url
                    binding.progressBar.visibility = View.GONE
                    updateAddressBar(url)
                    updateIdBadge(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    binding.progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    if (newProgress == 100) binding.swipeRefresh.isRefreshing = false
                }
            }
        }
        binding.swipeRefresh.setColorSchemeColors(0xFF7C6CFF.toInt())
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1A1A22.toInt())
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
    }

    private fun updateAddressBar(url: String) {
        if (isNormalMode) binding.searchInput.setText(url)
        else              binding.urlInput.setText(url)
    }

    // ── Buttons ──────────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnGo.setOnClickListener { navigateFromLinkInput() }
        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { navigateFromLinkInput(); true } else false
        }

        binding.btnSearch.setOnClickListener { navigateFromSearchInput() }
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) { navigateFromSearchInput(); true } else false
        }

        val gearAction = View.OnClickListener {
            val spin = AnimationUtils.loadAnimation(this, R.anim.gear_spin)
            (if (isNormalMode) binding.btnSettingsNormal else binding.btnSettingsLink).startAnimation(spin)
            handler.postDelayed({ openSettings() }, 180)
        }
        binding.btnSettingsLink.setOnClickListener(gearAction)
        binding.btnSettingsNormal.setOnClickListener(gearAction)

        binding.btnNext.setOnClickListener {
            val cur = binding.urlInput.text.toString().trim()
            incrementLastNumber(cur, +1)?.let {
                scanDelta = +1; scanAttempts = 0; isScanning = true
                if (baseUrl.isEmpty()) baseUrl = ensureHttps(cur)
                navigateTo(it)
            }
        }
        binding.btnPrev.setOnClickListener {
            val cur = binding.urlInput.text.toString().trim()
            incrementLastNumber(cur, -1)?.let {
                scanDelta = -1; scanAttempts = 0; isScanning = true
                if (baseUrl.isEmpty()) baseUrl = ensureHttps(cur)
                navigateTo(it)
            }
        }
    }

    private fun navigateFromLinkInput() {
        val url = binding.urlInput.text.toString().trim()
        if (url.isNotEmpty()) {
            stopScanning(); baseUrl = ensureHttps(url)
            navigateTo(baseUrl); hideKeyboard()
        }
    }

    private fun navigateFromSearchInput() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        val url = when {
            query.startsWith("http://") || query.startsWith("https://") -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
        navigateTo(url)
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    private fun setupSettings() {
        binding.settingsDim.setOnClickListener { closeSettings() }
        binding.btnCloseSettings.setOnClickListener { closeSettings() }
        binding.optionLinkMode.setOnClickListener   { selectMode(false) }
        binding.radioLinkMode.setOnClickListener    { selectMode(false) }
        binding.optionNormalMode.setOnClickListener { selectMode(true) }
        binding.radioNormalMode.setOnClickListener  { selectMode(true) }

        binding.vpnSwitch.setOnCheckedChangeListener { _, on ->
            vpnEnabled = on
            binding.vpnStatusText.text = if (on) "включён" else "выключен"
            binding.vpnStatusText.setTextColor(if (on) 0xFF7C6CFF.toInt() else 0xFF555560.toInt())
            if (on) {
                if (servers.isEmpty()) {
                    Toast.makeText(this, "⏳ Загрузка серверов...", Toast.LENGTH_SHORT).show()
                } else {
                    enableVpn()
                }
            } else {
                disableVpn()
            }
        }

        serverBtns.forEachIndexed { i, btn -> btn.setOnClickListener { selectServer(i) } }
    }

    private fun selectMode(normal: Boolean) {
        isNormalMode = normal
        binding.radioLinkMode.isChecked   = !normal
        binding.radioNormalMode.isChecked = normal
        binding.topBarLink.visibility     = if (normal) View.GONE   else View.VISIBLE
        binding.topBarNormal.visibility   = if (normal) View.VISIBLE else View.GONE
        binding.emptyLabel.visibility     = View.GONE
        binding.emptyGoogle.visibility    = View.GONE
        val webLoaded = binding.webView.url?.isNotEmpty() == true
        if (!webLoaded) {
            if (normal) binding.emptyGoogle.visibility = View.VISIBLE
            else        binding.emptyLabel.visibility  = View.VISIBLE
        }
        binding.btnNext.visibility    = if (normal) View.GONE else View.VISIBLE
        binding.btnPrev.visibility    = if (normal) View.GONE else View.VISIBLE
        binding.vpnSection.visibility = if (normal) View.VISIBLE else View.GONE
        if (!normal) {
            stopScanning()
            if (vpnEnabled) {
                vpnEnabled = false
                binding.vpnSwitch.isChecked = false
                disableVpn()
            }
        }
    }

    private fun openSettings() {
        binding.settingsOverlay.visibility = View.VISIBLE
        binding.settingsOverlay.alpha = 0f
        binding.settingsOverlay.animate().alpha(1f).setDuration(180).start()
        binding.settingsPanel.startAnimation(AnimationUtils.loadAnimation(this, R.anim.panel_slide_up))
        settingsOpen = true
    }

    private fun closeSettings() {
        val anim = AnimationUtils.loadAnimation(this, R.anim.panel_slide_down)
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                binding.settingsOverlay.visibility = View.GONE
            }
        })
        binding.settingsPanel.startAnimation(anim)
        binding.settingsOverlay.animate().alpha(0f).setDuration(220).start()
        settingsOpen = false
    }

    // ── VPN servers ──────────────────────────────────────────────────────────
    private fun loadVpnServers() {
        Thread {
            try {
                val url = java.net.URL(
                    "https://www.ipunblock.com/servers.json?" +
                    (Math.random() + 1).toString().substring(2, 8)
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val loaded = mutableListOf<VpnServer>()
                for (key in json.keys()) {
                    val s = json.getJSONObject(key)
                    if (s.optString("Instant") == "1") {
                        // ipunblock servers are HTTP proxies — use socks if available
                        val rawType = s.optString("Type", "http").lowercase()
                        val proxyType = if (rawType.contains("socks")) "socks" else "http"
                        loaded.add(VpnServer(key,
                            s.optString("IP"), s.optInt("Port", 8080),
                            proxyType, s.optString("Country", key)))
                    }
                }
                handler.post {
                    servers.clear()
                    servers.addAll(if (loaded.isEmpty()) fallbackServers else loaded)
                    refreshServerButtons()
                    // If user already toggled VPN while servers were loading — apply now
                    if (vpnEnabled) enableVpn()
                }
            } catch (_: Exception) {
                handler.post {
                    servers.clear(); servers.addAll(fallbackServers); refreshServerButtons()
                    if (vpnEnabled) enableVpn()
                }
            }
        }.start()
    }

    private fun refreshServerButtons() {
        serverBtns.forEachIndexed { i, btn ->
            if (i < servers.size) {
                val sv = servers[i]
                serverFlags[i].text = countryFlags[sv.country] ?: countryFlags[sv.name] ?: "🌐"
                serverNames[i].text = sv.country.take(11)
                btn.visibility = View.VISIBLE
            } else btn.visibility = View.INVISIBLE
        }
        selectServer(0)
    }

    private fun selectServer(index: Int) {
        if (index >= servers.size) return
        selectedServerIndex = index
        serverBtns.forEachIndexed { i, btn ->
            btn.setBackgroundResource(
                if (i == index) R.drawable.server_selected_bg else R.drawable.server_default_bg
            )
            serverNames[i].setTextColor(
                if (i == index) 0xFFE8E8F0.toInt() else 0xFF9D9DAA.toInt()
            )
        }
        if (vpnEnabled) {
            enableVpn()
            Toast.makeText(this, "🛡️ ${servers[index].country}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── VPN proxy ────────────────────────────────────────────────────────────
    private fun enableVpn() {
        if (servers.isEmpty()) return
        val sv = servers[selectedServerIndex]
        // Test proxy reachability before applying, on background thread
        Thread {
            // Step 1: check basic TCP reachability
            val reachable = try {
                val sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress(sv.ip, sv.port), 5000)
                sock.close()
                true
            } catch (_: Exception) { false }

            if (!reachable) {
                handler.post {
                    vpnEnabled = false
                    binding.vpnSwitch.isChecked = false
                    binding.vpnStatusText.text = "недоступен"
                    binding.vpnStatusText.setTextColor(0xFFFF5555.toInt())
                    Toast.makeText(this,
                        "❌ Сервер ${sv.country} недоступен. Попробуй другой регион.",
                        Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // Step 2: test if proxy supports HTTPS CONNECT tunnel
            val supportsTunnel = try {
                val sock = java.net.Socket(sv.ip, sv.port)
                sock.soTimeout = 5000
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                // Send HTTP CONNECT request to google.com:443
                out.write("CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n".toByteArray())
                out.flush()
                val buf = ByteArray(64)
                val n = inp.read(buf)
                sock.close()
                val response = String(buf, 0, n.coerceAtLeast(0))
                response.contains("200") // "200 Connection established"
            } catch (_: Exception) { false }

            handler.post {
                // Apply proxy — if tunnel supported use for all, else HTTP only
                setWebViewProxy(applicationContext, sv.ip, sv.port, sv.type, supportsTunnel)
                binding.vpnStatusText.text = if (supportsTunnel)
                    "включён · ${sv.country}" else "включён · ${sv.country} (HTTP)"
                binding.vpnStatusText.setTextColor(0xFF7C6CFF.toInt())
                binding.webView.reload()
                Toast.makeText(this, "🛡️ VPN: ${sv.country}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun disableVpn() {
        setWebViewProxy(applicationContext, "", 0)
        binding.vpnStatusText.text = "выключен"
        binding.vpnStatusText.setTextColor(0xFF555560.toInt())
        handler.post {
            binding.webView.reload()
        }
    }

    /**
     * Sets proxy for WebView using androidx.webkit.ProxyController.
     * ipunblock servers are plain HTTP proxies — they do NOT support CONNECT,
     * so HTTPS goes direct (no tunnel error), HTTP goes via proxy.
     * For actual HTTPS proxying the VPN server must support CONNECT method.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setWebViewProxy(context: Context, host: String, port: Int,
                                scheme: String = "http", supportsTunnel: Boolean = false) {
        // System properties — fallback for non-WebView HTTP clients
        val props = System.getProperties()
        if (host.isNotEmpty()) {
            props["http.proxyHost"]  = host; props["http.proxyPort"]  = port.toString()
            props["https.proxyHost"] = host; props["https.proxyPort"] = port.toString()
        } else {
            props.remove("http.proxyHost"); props.remove("http.proxyPort")
            props.remove("https.proxyHost"); props.remove("https.proxyPort")
        }

        // androidx.webkit.ProxyController — main method for WebView
        try {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val proxyController = androidx.webkit.ProxyController.getInstance()

            if (host.isNotEmpty()) {
                val isSocks = scheme.lowercase().startsWith("socks")
                val proxyUri = if (isSocks) "socks5://$host:$port" else "http://$host:$port"

                val builder = androidx.webkit.ProxyConfig.Builder()

                if (isSocks) {
                    // SOCKS5 handles both HTTP and HTTPS — proxy everything
                    builder.addProxyRule(proxyUri)
                } else {
                    // Plain HTTP proxy: HTTPS CONNECT tunnels fail (ERR_TUNNEL_CONNECTION_FAILED)
                    // Only proxy HTTP traffic; bypass HTTPS so it goes direct (no tunnel error)
                    builder.addProxyRule(proxyUri, androidx.webkit.ProxyConfig.MATCH_HTTP)
                    builder.addDirect()
                }

                proxyController.setProxyOverride(builder.build(), executor) {}
            } else {
                proxyController.clearProxyOverride(executor) {}
            }
            return
        } catch (_: Exception) {}

        // Broadcast fallback (older devices)
        try {
            val intent = android.content.Intent(android.net.Proxy.PROXY_CHANGE_ACTION)
            if (host.isNotEmpty()) {
                intent.putExtra("host", host)
                intent.putExtra("port", port)
                intent.putExtra("exclusionList", "")
            }
            context.applicationContext.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

        // ── Scan helpers ─────────────────────────────────────────────────────────
    private fun skipToNext() {
        if (++scanAttempts >= maxScanAttempts) { stopScanning(); showScanStatus("Не найдено за $maxScanAttempts попыток"); return }
        val next = incrementLastNumber(binding.urlInput.text.toString().trim(), scanDelta)
        if (next != null) { showScanStatus("Пропуск... ID: ${getLastNumber(next)} (попытка $scanAttempts)"); navigateTo(next) }
        else stopScanning()
    }

    private fun stopScanning()            { isScanning = false; scanAttempts = 0; binding.scanStatus.visibility = View.GONE }
    private fun showScanStatus(t: String) { binding.scanStatus.text = t; binding.scanStatus.visibility = View.VISIBLE }

    // ── Navigation ────────────────────────────────────────────────────────────
    private fun navigateTo(url: String) {
        val safe = ensureHttps(url)
        if (!isNormalMode) currentAdtngUrl = safe
        updateAddressBar(safe)
        binding.emptyLabel.visibility  = View.GONE
        binding.emptyGoogle.visibility = View.GONE
        binding.idBadge.visibility     = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.webView.loadUrl(safe)
        updateIdBadge(safe)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun getDomain(url: String) = try { URI(url).host ?: "" } catch (_: Exception) { "" }

    private fun incrementLastNumber(url: String, delta: Int): String? {
        val m = trailingNumberRegex.findAll(url).toList()
        if (m.isEmpty()) return null
        val last = m.last()
        val n = last.value.toLongOrNull() ?: return null
        val new = n + delta; if (new < 0) return null
        return url.substring(0, last.range.first) +
               new.toString().padStart(last.value.length, '0') +
               url.substring(last.range.last + 1)
    }

    private fun getLastNumber(url: String) = trailingNumberRegex.findAll(url).toList().lastOrNull()?.value ?: "?"

    private fun updateIdBadge(url: String) {
        if (isNormalMode) return
        val m = trailingNumberRegex.findAll(url).toList()
        if (m.isNotEmpty()) { binding.idBadge.text = "ID: ${m.last().value}"; binding.idBadge.visibility = View.VISIBLE }
    }

    private fun ensureHttps(url: String) =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    override fun onBackPressed() {
        when {
            settingsOpen -> closeSettings()
            isScanning   -> stopScanning()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
