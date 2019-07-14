package com.github.alexmelyon.webview_nfc

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.TextView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_fullscreen.*
import org.json.JSONArray
import org.json.JSONObject

class FullscreenActivity : AppCompatActivity() {

    val SHARED = "com.github.alexmelyon.map_nfc.shared"
    val DEFAULT_SITE = "https://vtm.su/"
    val LAST_LOADED_SITE_PREF = "LAST_LOADED_SITE_PREF"
    val KNOWN_SITES_PREF = "KNOWN_SITES_PREF"

    enum class ScanResultsReceiver { NOTHING, ALERT, JS }

    var scanResultsReceiver: ScanResultsReceiver = ScanResultsReceiver.NOTHING
    lateinit var knownSites: MutableList<String>
    lateinit var wifiManager: WifiManager
    lateinit var wifiScanReceiver: BroadcastReceiver
    private var scanResultsAlert: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen)

        knownSites = getSharedPreferences(SHARED, Context.MODE_PRIVATE).getString(KNOWN_SITES_PREF, "").split(",").toMutableList()

        initWebview()

        initWifiManager()
    }

    override fun onResume() {
        super.onResume()

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).toTypedArray(), 87)
            }
        }
    }

    fun initWifiManager() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    when(scanResultsReceiver) {
                        ScanResultsReceiver.ALERT -> showScanResultsAlert(wifiManager.scanResults)
                        ScanResultsReceiver.JS -> webviewOnReadWifi(wifiManager.scanResults)
                    }
                    scanResultsReceiver = ScanResultsReceiver.NOTHING
                }
            }
        }
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    fun showScanResultsAlert(scanResults: List<ScanResult>) {
        if (scanResultsAlert == null) {
            scanResultsAlert = AlertDialog.Builder(this@FullscreenActivity)
                    .setItems(scanResults.map { "${it.SSID} ${it.level}" }.toTypedArray(), DialogInterface.OnClickListener { dialog, which -> })
                    .create()
            scanResultsAlert?.show()
        }
    }

    fun initWebview() {
        web_view.settings.javaScriptEnabled = true
        web_view.clearSslPreferences()
        web_view.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY)
        web_view.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError) {
                Toast.makeText(this@FullscreenActivity, "${error.errorCode} ${error.description}", Toast.LENGTH_LONG).show()
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Toast.makeText(this@FullscreenActivity, "${errorCode} ${description}", Toast.LENGTH_LONG).show()
            }

            override fun onPageFinished(view: WebView?, url: String) {
                this@FullscreenActivity.title = view?.title
                updateOptionsMenu(url)
            }

            fun updateOptionsMenu(url: String) {
                getSharedPreferences(SHARED, Context.MODE_PRIVATE).edit().apply {
                    putString(LAST_LOADED_SITE_PREF, url)
                }.apply()
            }
        }
        web_view.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }
        web_view.addJavascriptInterface(WebAppInterface(this), "Android")
        val lastLoadedSite = getSharedPreferences(SHARED, Context.MODE_PRIVATE).getString(LAST_LOADED_SITE_PREF, DEFAULT_SITE)
        web_view.loadUrl(lastLoadedSite)
    }

    class WebAppInterface(val activity: FullscreenActivity) {

        @JavascriptInterface
        fun readWifi() {
            activity.scanResultsReceiver = ScanResultsReceiver.JS
            activity.startScanWifi(ScanResultsReceiver.JS)
        }
    }

    fun startScanWifi(receiver: ScanResultsReceiver) {
        scanResultsReceiver = receiver
        wifiManager.startScan()
    }

    fun webviewOnReadWifi(scanResults: List<ScanResult>) {
        val json = scanResults.map { JSONObject().apply {
            put("bssid", it.BSSID)
            put("ssid", it.SSID)
            put("level", it.level)
        }}.fold(JSONArray()) { total, next -> total.put(next) }
        .let { JSONObject().apply { put("scanResults", it) } }
        webviewRunJs("onReadWifi", json.toString())
    }

    fun webviewRunJs(method: String, args: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            web_view.evaluateJavascript("$method($args);", null)
        } else {
            web_view.loadUrl("javascript:$method($args);")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val groupId = 0
        val orderId = 0
        knownSites.forEachIndexed { index, url ->
            menu.add(groupId, index, orderId, withoutProtocol(url))
        }
        return super.onCreateOptionsMenu(menu)
    }

    fun withoutProtocol(url: String): String {
        if (url.startsWith("http://")) {
            return url.substring(7)
        } else if (url.startsWith("https://")) {
            return url.substring(8)
        } else {
            return url
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.add_site_menu -> {
                showAlertEditDialog("Enter url:", okAction = { url ->
                    var withProtocol = url
                    if (!withProtocol.startsWith("http://") || !withProtocol.startsWith("https://")) {
                        withProtocol = "https://$url"
                    }
                    web_view.loadUrl(withProtocol)
                    knownSites.add(0, withProtocol)
                    getSharedPreferences(SHARED, Context.MODE_PRIVATE).edit().apply {
                        putString(KNOWN_SITES_PREF, knownSites.joinToString(","))
                    }.apply()
                    invalidateOptionsMenu()
                })
                return true
            }
            R.id.remove_site_menu -> {
                AlertDialog.Builder(this)
                        .setItems(knownSites.map { withoutProtocol(it) }.toTypedArray(), DialogInterface.OnClickListener { dialog, which ->
                            knownSites.removeAt(which)
                            getSharedPreferences(SHARED, Context.MODE_PRIVATE).edit().apply {
                                putString(KNOWN_SITES_PREF, knownSites.joinToString(","))
                            }.apply()
                            invalidateOptionsMenu()
                        }).show()
            }
            R.id.scan_wifi_menu -> {
                startScanWifi(ScanResultsReceiver.ALERT)
            }
            else -> {
                val url = knownSites[item.itemId]
                web_view.loadUrl(url)
            }
        }
        return false
    }


    fun showAlertEditDialog(title: String, message: String = "", okAction: (String) -> Unit) {
        val edit = TextInputEditText(this)
        edit.maxLines = 1
        edit.inputType = InputType.TYPE_TEXT_VARIATION_URI
        edit.setText(message, TextView.BufferType.EDITABLE)

        val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setPositiveButton("OK", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.show()

        edit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { v ->
            if (edit.text.isBlank()) {
                edit.error = "Пожалуйста введите адрес сайта"
            } else {
                dialog.dismiss()
                okAction(edit.text.trim().toString())
            }
        }
    }

}
