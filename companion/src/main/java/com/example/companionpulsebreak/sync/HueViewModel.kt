package com.example.companionpulsebreak.sync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class HueLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val brightness: Int // 0..100
)

data class HueGroup(
    val id: String,
    val name: String,
    val type: String,             // "Room" or "Zone" (Hue API)
    val lightIds: List<String>,   // IDs of lights in this group
    val brightness: Int? = null   // optional: average/last known brightness 0..100
)

/** Simple holder for discovered bridge information. */
data class BridgeInfo(val ip: String, val name: String?)

class HueViewModel(application: Application) : AndroidViewModel(application) {
    private val _lights = MutableStateFlow<List<HueLight>>(emptyList())
    val lights: StateFlow<List<HueLight>> = _lights.asStateFlow()

    private val _groups = MutableStateFlow<List<HueGroup>>(emptyList())
    val groups: StateFlow<List<HueGroup>> = _groups.asStateFlow()

    private val context = application.applicationContext

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _bridgeIp = MutableStateFlow<String?>(null)
    val bridgeIp: StateFlow<String?> = _bridgeIp.asStateFlow()

    private val _hueUsername = MutableStateFlow<String?>(null)
    val hueUsername: StateFlow<String?> = _hueUsername.asStateFlow()

    // Now hold BridgeInfo objects (ip + friendly name when available)
    private val _discovered = MutableStateFlow<List<BridgeInfo>>(emptyList())
    val discovered: StateFlow<List<BridgeInfo>> = _discovered.asStateFlow()

    private val _pairingStatus = MutableStateFlow<String?>(null)
    val pairingStatus: StateFlow<String?> = _pairingStatus.asStateFlow()

    private val _brightness = MutableStateFlow(100)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _color = MutableStateFlow(0xFFFFFFFF.toInt())
    val color: StateFlow<Int> = _color.asStateFlow()

    init {
        // Load persisted hue settings from SharedPreferences-based HueSettingsStore
        val ip = HueSettingsStore.getBridgeIp(context)
        val user = HueSettingsStore.getHueUsername(context)
        _bridgeIp.value = ip
        _hueUsername.value = user
        updateConnectedState()
    }

    private fun updateConnectedState() {
        _isConnected.value = !_bridgeIp.value.isNullOrEmpty() && !_hueUsername.value.isNullOrEmpty()
    }

    /** Discover bridges using the official Hue discovery endpoint and enrich with friendly names. */
    fun discoverBridges() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://discovery.meethue.com/")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val arr = JSONArray(jsonText)
                val results = mutableListOf<BridgeInfo>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val ip = obj.optString("internalipaddress", null) ?: continue

                    // Check if this IP really is a Hue bridge and get its name
                    probeHueBridge(ip)?.let { bridgeInfo ->
                        results.add(bridgeInfo)
                    }
                }

                _discovered.value = results

                // OPTIONAL: auto-start pairing if we found exactly one bridge and are not connected
                if (results.size == 1 && !_isConnected.value) {
                    pairWithBridge(results.first().ip)
                }

            } catch (e: Exception) {
                Log.w("HueViewModel", "Discovery failed: ${e.message}", e)
                _discovered.value = emptyList()
            }
        }
    }

    private fun probeHueBridge(ip: String): BridgeInfo? {
        return try {
            val url = URL("http://$ip/description.xml")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }

            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val lower = xml.lowercase()

            // Heuristics to make sure it's really a Hue bridge
            val looksLikeHue =
                lower.contains("philips hue bridge") ||
                        lower.contains("<manufacturer>signify</manufacturer>") ||
                        lower.contains("<modelnumber>bsb001</modelnumber>") ||
                        lower.contains("<modelnumber>bsb002</modelnumber>")

            if (!looksLikeHue) {
                Log.d("HueViewModel", "IP $ip is not a Hue bridge")
                return null
            }

            val friendlyName = extractXmlTag(xml, "friendlyName")
            BridgeInfo(ip = ip, name = friendlyName)

        } catch (e: Exception) {
            Log.d("HueViewModel", "probeHueBridge failed for $ip: ${e.message}")
            null
        }
    }

    /** Super simple XML tag extractor for small responses like description.xml */
    private fun extractXmlTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim()
    }

    /**
     * Pair with a bridge by sending POST http://<ip>/api with body {"devicetype":"pulsebreak#companion"}
     * Returns true when pairing succeeded and username was saved. If the link button was not pressed, Hue returns a specific error.
     */
    fun pairWithBridge(ip: String, retries: Int = 10, delayMs: Long = 1500) {
        viewModelScope.launch(Dispatchers.IO) {
            _pairingStatus.value = "starting"

            for (attempt in 1..retries) {

                try {
                    val url = URL("http://$ip/api")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }

                    val body = JSONObject()
                        .put("devicetype", "pulsebreak#companion")
                        .toString()

                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    Log.d("HueViewModel", "Pair response: $responseText")

                    val arr = JSONArray(responseText)
                    if (arr.length() > 0) {
                        val resp = arr.getJSONObject(0)

                        // SUCCESS → Username returned
                        if (resp.has("success")) {
                            val username = resp.getJSONObject("success")
                                .optString("username", null)

                            if (!username.isNullOrEmpty()) {
                                HueSettingsStore.persist(context, ip, username)
                                _hueUsername.value = username
                                _bridgeIp.value = ip

                                updateConnectedState()
                                _pairingStatus.value = "paired"

                                return@launch
                            }
                        }

                        // ERROR → Check type
                        if (resp.has("error")) {
                            val err = resp.getJSONObject("error")
                            val type = err.optInt("type")
                            val desc = err.optString("description")

                            Log.w("HueViewModel", "Pair attempt $attempt error: $desc ($type)")

                            if (type == 101) {
                                // Bridge button not pressed
                                _pairingStatus.value = "link_button_not_pressed"
                            } else {
                                _pairingStatus.value = "error: $desc"
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("HueViewModel", "Pair attempt failed: ${e.message}")
                    _pairingStatus.value = "error: ${e.message}"
                }

                delay(delayMs)
            }

            // Retries exhausted → fail
            _pairingStatus.value = "failed"
        }
    }


    fun disconnect() {
        viewModelScope.launch {
            _isConnected.value = false
            _bridgeIp.value = null
            _hueUsername.value = null
            HueSettingsStore.persist(context, null, null)
        }
    }

    fun refreshHueState() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            try {
                // 1) Fetch lights
                val lightsUrl = URL("http://$ip/api/$user/lights")
                val lightsConn = (lightsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val lightsJsonText = lightsConn.inputStream.bufferedReader().use { it.readText() }
                lightsConn.disconnect()
                val lightsJson = JSONObject(lightsJsonText)

                val newLights = mutableListOf<HueLight>()
                val lightIds = lightsJson.keys()
                while (lightIds.hasNext()) {
                    val id = lightIds.next()
                    val obj = lightsJson.getJSONObject(id)
                    val name = obj.optString("name", "Light $id")
                    val state = obj.optJSONObject("state") ?: JSONObject()
                    val on = state.optBoolean("on", false)
                    val bri = state.optInt("bri", 254) // 0..254
                    val brightnessPercent = (bri * 100 / 254.0).toInt().coerceIn(0, 100)

                    newLights.add(
                        HueLight(
                            id = id,
                            name = name,
                            on = on,
                            brightness = brightnessPercent
                        )
                    )
                }

                // 2) Fetch groups (rooms + zones)
                val groupsUrl = URL("http://$ip/api/$user/groups")
                val groupsConn = (groupsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val groupsJsonText = groupsConn.inputStream.bufferedReader().use { it.readText() }
                groupsConn.disconnect()
                val groupsJson = JSONObject(groupsJsonText)

                val newGroups = mutableListOf<HueGroup>()
                val groupIds = groupsJson.keys()
                while (groupIds.hasNext()) {
                    val id = groupIds.next()
                    val obj = groupsJson.getJSONObject(id)
                    val type = obj.optString("type", "")
                    if (type != "Room" && type != "Zone") continue

                    val name = obj.optString("name", "Group $id")
                    val lightsArray = obj.optJSONArray("lights") ?: JSONArray()
                    val lightIdsList = mutableListOf<String>()
                    for (i in 0 until lightsArray.length()) {
                        lightIdsList.add(lightsArray.getString(i))
                    }

                    val memberBrightness = newLights.filter { lightIdsList.contains(it.id) }
                    val avgBrightness = if (memberBrightness.isNotEmpty()) {
                        memberBrightness.map { it.brightness }.average().toInt()
                    } else null

                    newGroups.add(
                        HueGroup(
                            id = id,
                            name = name,
                            type = type,
                            lightIds = lightIdsList,
                            brightness = avgBrightness
                        )
                    )
                }

                _lights.value = newLights
                _groups.value = newGroups
            } catch (e: Exception) {
                Log.w("HueViewModel", "refreshHueState failed: ${e.message}", e)
            }
        }
    }


    fun setBrightnessForAllLights(brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
            try {
                val lightsUrl = URL("http://$ip/api/$user/lights")
                val conn = (lightsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sb.append(line)
                        line = reader.readLine()
                    }
                }
                conn.disconnect()
                val lightsJson = JSONObject(sb.toString())
                val keys = lightsJson.keys()
                while (keys.hasNext()) {
                    val lightId = keys.next()
                    val stateUrl = URL("http://$ip/api/$user/lights/$lightId/state")
                    val putConn = (stateUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    val body = JSONObject().put("bri", bri).put("on", bri > 1).toString()
                    DataOutputStream(putConn.outputStream).use { it.writeBytes(body) }
                    putConn.inputStream.close()
                    putConn.disconnect()
                }
                _brightness.value = brightnessPercent.coerceIn(0, 100)
            } catch (e: Exception) {
                Log.w("HueViewModel", "Set brightness failed: ${e.message}", e)
            }
        }
    }

    // Set basic color by converting ARGB to XY or hue/sat could be implemented. For now, map white vs colored.
    fun setColorForAllLights(argb: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            try {
                // naive implementation: if color is close to white, set ct; otherwise set hue/sat roughly
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF

                // choose a simple hue value from 0..65535
                val hueVal = ((Math.atan2(g.toDouble(), r.toDouble()) + Math.PI) / (2 * Math.PI) * 65535).toInt()
                val sat = 200

                val lightsUrl = URL("http://$ip/api/$user/lights")
                val conn = (lightsUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        sb.append(line)
                        line = reader.readLine()
                    }
                }
                conn.disconnect()
                val lightsJson = JSONObject(sb.toString())
                val keys = lightsJson.keys()
                while (keys.hasNext()) {
                    val lightId = keys.next()
                    val stateUrl = URL("http://$ip/api/$user/lights/$lightId/state")
                    val putConn = (stateUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    val body = JSONObject().put("hue", hueVal).put("sat", sat).put("on", true).toString()
                    DataOutputStream(putConn.outputStream).use { it.writeBytes(body) }
                    putConn.inputStream.close()
                    putConn.disconnect()
                }
                _color.value = argb
            } catch (e: Exception) {
                Log.w("HueViewModel", "Set color failed: ${e.message}", e)
            }
        }
    }

    fun setGroupBrightness(groupId: String, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
            try {
                val url = URL("http://$ip/api/$user/groups/$groupId/action")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val body = JSONObject()
                    .put("bri", bri)
                    .put("on", bri > 1)
                    .toString()

                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.close()
                conn.disconnect()

                // update local state optimistically
                val updatedGroups = _groups.value.map {
                    if (it.id == groupId) it.copy(brightness = brightnessPercent.coerceIn(0, 100))
                    else it
                }
                _groups.value = updatedGroups

            } catch (e: Exception) {
                Log.w("HueViewModel", "setGroupBrightness failed: ${e.message}", e)
            }
        }
    }

    fun setLightBrightness(lightId: String, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
            try {
                val url = URL("http://$ip/api/$user/lights/$lightId/state")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val body = JSONObject()
                    .put("bri", bri)
                    .put("on", bri > 1)
                    .toString()

                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.close()
                conn.disconnect()

                // update local state
                val updatedLights = _lights.value.map {
                    if (it.id == lightId) it.copy(brightness = brightnessPercent.coerceIn(0, 100), on = bri > 1)
                    else it
                }
                _lights.value = updatedLights

            } catch (e: Exception) {
                Log.w("HueViewModel", "setLightBrightness failed: ${e.message}", e)
            }
        }
    }

    // New: toggle a single light on/off
    fun setLightOn(lightId: String, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@launch

            try {
                val url = URL("http://$ip/api/$user/lights/$lightId/state")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val body = JSONObject().put("on", on).toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.close()
                conn.disconnect()

                // update local state optimistically
                val updatedLights = _lights.value.map {
                    if (it.id == lightId) it.copy(on = on)
                    else it
                }
                _lights.value = updatedLights

            } catch (e: Exception) {
                Log.w("HueViewModel", "setLightOn failed: ${e.message}", e)
            }
        }
    }

}