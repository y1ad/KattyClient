package com.kattyclient.proxy

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object ProxyManager {
    private const val TAG = "KattyClient"
    const val LISTEN_PORT = 19132

    var targetHost = "play.nethergames.org"
    var targetPort = 19132
    var isRunning = false

    // Player state
    var playerX = 0f; var playerY = 0f; var playerZ = 0f
    var playerYaw = 0f; var playerPitch = 0f
    var playerHealth = 20f

    // Nearest entity (para aim assist)
    var nearestEntityX = 0f; var nearestEntityY = 0f; var nearestEntityZ = 0f
    var nearestEntityDist = Float.MAX_VALUE
    var nearestEntityId = 0L

    private var clientSocket: DatagramSocket? = null
    private var serverSocket: DatagramSocket? = null
    private var clientAddr: java.net.SocketAddress? = null
    private var job: Job? = null

    // Aim assist config
    var aimAssistRange = 4.5f
    var aimAssistStrength = 0.3f  // 0.0 - 1.0 (suave, igual que Minecraft nativo)
    var aimAssistFOV = 60f        // grados de FOV dentro de los cuales asiste

    val modules = mutableMapOf(
        "AimAssist"   to false,
        "AutoSprint"  to true,
        "AutoEat"     to false,
        "Freecam"     to false,
        "Zoom"        to false,
        "Fly"         to false,
        "Speed"       to false,
        "NoFall"      to true,
        "Fullbright"  to false,
        "ESP"         to false,
        "AntiKB"      to false,
        "Reach"       to false,
        "FastBreak"   to false,
        "AutoTool"    to false,
        "TargetHUD"   to true,
    )

    fun start(host: String, port: Int) {
        if (isRunning) return
        targetHost = host; targetPort = port; isRunning = true
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val ip = InetAddress.getByName(targetHost)
                clientSocket = DatagramSocket(LISTEN_PORT)
                serverSocket = DatagramSocket().also { it.connect(ip, targetPort) }
                Log.d(TAG, "Proxy → $targetHost:$targetPort")
                launch { relayC2S() }
                launch { relayS2C() }
            } catch (e: Exception) {
                Log.e(TAG, "start: ${e.message}"); isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false; job?.cancel()
        clientSocket?.close(); serverSocket?.close()
        clientSocket = null; serverSocket = null
    }

    fun toggle(mod: String) { modules[mod] = !(modules[mod] ?: false) }

    private suspend fun relayC2S() {
        val buf = ByteArray(65535)
        while (isRunning) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                clientSocket!!.receive(pkt)
                clientAddr = pkt.socketAddress
                val data = processC2S(pkt.data.copyOf(pkt.length))
                serverSocket!!.send(DatagramPacket(data, data.size))
            } catch (e: Exception) { if (isRunning) delay(1) }
        }
    }

    private suspend fun relayS2C() {
        val buf = ByteArray(65535)
        while (isRunning) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                serverSocket!!.receive(pkt)
                val data = processS2C(pkt.data.copyOf(pkt.length))
                clientAddr?.let { clientSocket!!.send(DatagramPacket(data, data.size, it)) }
            } catch (e: Exception) { if (isRunning) delay(1) }
        }
    }

    private fun processC2S(data: ByteArray): ByteArray {
        for (i in data.indices) {
            // MovePlayer packet (0x13)
            if (data[i] == 0x13.toByte() && i + 28 < data.size) {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                try {
                    val x = bb.getFloat(i + 5)
                    val y = bb.getFloat(i + 9)
                    val z = bb.getFloat(i + 13)
                    val yaw = bb.getFloat(i + 17)
                    val pitch = bb.getFloat(i + 21)
                    playerX = x; playerY = y; playerZ = z
                    playerYaw = yaw; playerPitch = pitch

                    val d = data.toMutableList()

                    // NoFall
                    if (modules["NoFall"] == true && i + 24 < data.size) {
                        data[i + 24] = 1
                    }

                    // Aim Assist — suavizar rotación hacia entidad más cercana
                    if (modules["AimAssist"] == true && nearestEntityDist < aimAssistRange) {
                        val (newYaw, newPitch) = calcAimAssist(x, y, z, yaw, pitch)
                        bb.putFloat(i + 17, newYaw)
                        bb.putFloat(i + 21, newPitch)
                    }

                    return bb.array()
                } catch (_: Exception) {}
                break
            }
        }
        return data
    }

    private fun processS2C(data: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in data.indices) {
            // MovePlayer — capturar posición del propio player
            if (data[i] == 0x13.toByte() && i + 16 < data.size) {
                try {
                    playerX = bb.getFloat(i + 5)
                    playerY = bb.getFloat(i + 9)
                    playerZ = bb.getFloat(i + 13)
                } catch (_: Exception) {}
            }

            // AddEntity / AddPlayer (0x0D, 0x0C) — trackear entidades para aim assist
            if ((data[i] == 0x0D.toByte() || data[i] == 0x0C.toByte()) && i + 20 < data.size) {
                try {
                    val ex = bb.getFloat(i + 9)
                    val ey = bb.getFloat(i + 13)
                    val ez = bb.getFloat(i + 17)
                    val dist = sqrt(
                        (ex - playerX).pow(2) +
                        (ey - playerY).pow(2) +
                        (ez - playerZ).pow(2)
                    )
                    if (dist < nearestEntityDist && dist < aimAssistRange) {
                        nearestEntityDist = dist
                        nearestEntityX = ex; nearestEntityY = ey; nearestEntityZ = ez
                    }
                } catch (_: Exception) {}
            }
        }
        // Reset nearest cada tick
        nearestEntityDist = Float.MAX_VALUE
        return data
    }

    private fun calcAimAssist(
        px: Float, py: Float, pz: Float,
        currentYaw: Float, currentPitch: Float
    ): Pair<Float, Float> {
        val dx = nearestEntityX - px
        val dy = (nearestEntityY + 1.6f) - (py + 1.62f)  // apuntar a torso
        val dz = nearestEntityZ - pz

        val targetYaw = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()
        val dist2d = sqrt(dx * dx + dz * dz)
        val targetPitch = Math.toDegrees(-atan2(dy.toDouble(), dist2d.toDouble())).toFloat()

        // Interpolar suavemente (igual que aim assist nativo de Minecraft)
        val newYaw = lerp(currentYaw, targetYaw, aimAssistStrength)
        val newPitch = lerp(currentPitch, targetPitch, aimAssistStrength)

        return Pair(newYaw, newPitch)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        var diff = b - a
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return a + diff * t
    }
}
