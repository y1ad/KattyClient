package com.kattyclient

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.kattyclient.proxy.ProxyManager
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hud: View
    private lateinit var menu: View
    private var menuVisible = false
    private val handler = Handler(Looper.getMainLooper())

    // Colores blanco y negro
    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val GRAY  = 0xFF888888.toInt()
    private val LGRAY = 0xFFCCCCCC.toInt()
    private val DGRAY = 0xFF222222.toInt()
    private val GREEN = 0xFF00FF00.toInt()
    private val RED   = 0xFFFF3333.toInt()

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildHUD(); buildMenu(); startUpdater()
    }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

    private fun buildHUD() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt())
            setPadding(10, 6, 10, 6)
        }

        val title = TextView(this).apply {
            text = "✦ KattyClient"
            setTextColor(WHITE); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        val coords = TextView(this).apply {
            text = "---"; setTextColor(LGRAY); textSize = 8f
            id = android.R.id.text1
        }
        val target = TextView(this).apply {
            text = ""; setTextColor(GREEN); textSize = 8f
            id = android.R.id.text2
        }
        val menuBtn = Button(this).apply {
            text = "☰ Menu"; textSize = 9f
            setBackgroundColor(DGRAY); setTextColor(WHITE)
            setPadding(12, 2, 12, 2)
        }
        menuBtn.setOnClickListener {
            menuVisible = !menuVisible
            menu.visibility = if (menuVisible) View.VISIBLE else View.GONE
        }

        root.addView(title); root.addView(coords)
        root.addView(target); root.addView(menuBtn)
        hud = root

        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 8; y = 100 }

        var dx = 0; var dy = 0
        hud.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = p.x - ev.rawX.toInt(); dy = p.y - ev.rawY.toInt(); true }
                MotionEvent.ACTION_MOVE -> { p.x = ev.rawX.toInt()+dx; p.y = ev.rawY.toInt()+dy; wm.updateViewLayout(hud, p); true }
                else -> false
            }
        }
        wm.addView(hud, p)
    }

    private fun buildMenu() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2000000.toInt())
            setPadding(18, 18, 18, 18)
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleTv = TextView(this).apply {
            text = "✦ KattyClient v1.0"
            setTextColor(WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"; setTextColor(GRAY); textSize = 16f; setPadding(8,0,0,0)
        }
        closeBtn.setOnClickListener { menuVisible = false; menu.visibility = View.GONE }
        header.addView(titleTv); header.addView(closeBtn)
        root.addView(header)
        root.addView(divider())

        // Server config
        root.addView(sectionLabel("CONEXIÓN"))
        val hostInput = editInput(ProxyManager.targetHost)
        val portInput = editInput(ProxyManager.targetPort.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(label("Host:")); root.addView(hostInput)
        root.addView(label("Puerto:")); root.addView(portInput)

        val connBtn = Button(this).apply {
            text = if (ProxyManager.isRunning) "⏹ Desconectar" else "▶ Conectar"
            setBackgroundColor(if (ProxyManager.isRunning) RED else GREEN)
            setTextColor(BLACK); typeface = Typeface.DEFAULT_BOLD
        }
        connBtn.setOnClickListener {
            if (ProxyManager.isRunning) {
                ProxyManager.stop()
                connBtn.text = "▶ Conectar"; connBtn.setBackgroundColor(GREEN)
            } else {
                val h = hostInput.text.toString().ifBlank { "play.nethergames.org" }
                val port = portInput.text.toString().toIntOrNull() ?: 19132
                ProxyManager.start(h, port)
                connBtn.text = "⏹ Desconectar"; connBtn.setBackgroundColor(RED)
            }
        }
        root.addView(connBtn)
        root.addView(label("→ Minecraft: IP 127.0.0.1 : 19132").apply { setTextColor(GRAY); textSize = 9f })
        root.addView(divider())

        // Aim Assist config
        root.addView(sectionLabel("AIM ASSIST"))
        root.addView(label("Rango (bloques):"))
        val rangeSeek = SeekBar(this).apply {
            max = 80; progress = (ProxyManager.aimAssistRange * 10).toInt()
        }
        val rangeVal = label("${ProxyManager.aimAssistRange}")
        rangeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                ProxyManager.aimAssistRange = p / 10f
                rangeVal.text = "${ProxyManager.aimAssistRange}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(rangeSeek); root.addView(rangeVal)

        root.addView(label("Intensidad:"))
        val strengthSeek = SeekBar(this).apply {
            max = 10; progress = (ProxyManager.aimAssistStrength * 10).toInt()
        }
        val strengthVal = label("${ProxyManager.aimAssistStrength}")
        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                ProxyManager.aimAssistStrength = p / 10f
                strengthVal.text = "${ProxyManager.aimAssistStrength}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(strengthSeek); root.addView(strengthVal)
        root.addView(divider())

        // Módulos
        root.addView(sectionLabel("MÓDULOS"))
        ProxyManager.modules.forEach { (name, on) ->
            root.addView(moduleRow(name, on))
        }

        scroll.addView(root); menu = scroll

        val p = overlayParams(350, 560, true).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.CENTER
        }
        menu.visibility = View.GONE
        wm.addView(menu, p)
    }

    private fun moduleRow(name: String, initialOn: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val lbl = TextView(this).apply {
            text = name
            setTextColor(if (initialOn) WHITE else GRAY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = TextView(this).apply {
            text = if (initialOn) "●" else "○"
            setTextColor(if (initialOn) WHITE else DGRAY)
            textSize = 14f
        }
        row.setOnClickListener {
            ProxyManager.toggle(name)
            val on = ProxyManager.modules[name] ?: false
            lbl.setTextColor(if (on) WHITE else GRAY)
            dot.text = if (on) "●" else "○"
            dot.setTextColor(if (on) WHITE else DGRAY)
        }
        row.addView(lbl); row.addView(dot)
        return row
    }

    private fun startUpdater() {
        val coords = hud.findViewById<TextView>(android.R.id.text1)
        val targetTv = hud.findViewById<TextView>(android.R.id.text2)
        handler.post(object : Runnable {
            override fun run() {
                val p = ProxyManager
                coords?.text = if (p.isRunning)
                    "XYZ ${p.playerX.roundToInt()} ${p.playerY.roundToInt()} ${p.playerZ.roundToInt()}\n" +
                    "HP ${p.playerHealth.roundToInt()} | ${p.modules.count { it.value }} ON"
                else "Desconectado"
                if (p.modules["TargetHUD"] == true && p.nearestEntityDist < p.aimAssistRange) {
                    targetTv?.text = "⊕ Target ${String.format("%.1f", p.nearestEntityDist)}m"
                } else {
                    targetTv?.text = ""
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // Helpers UI
    private fun divider() = View(this).apply {
        setBackgroundColor(DGRAY)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 10, 0, 10) }
    }
    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text; setTextColor(WHITE); textSize = 11f
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, 4, 0, 4)
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text; setTextColor(LGRAY); textSize = 10f
    }
    private fun editInput(hint: String) = EditText(this).apply {
        setText(hint); setTextColor(WHITE)
        setBackgroundColor(DGRAY); textSize = 11f; setPadding(8, 4, 8, 4)
    }

    override fun onDestroy() {
        super.onDestroy()
        ProxyManager.stop()
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(hud); wm.removeView(menu) } catch (_: Exception) {}
    }
}
