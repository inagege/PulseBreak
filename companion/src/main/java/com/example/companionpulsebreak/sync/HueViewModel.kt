package com.example.companionpulsebreak.sync

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CHANGES in this file (focused on Scenes + v2 parsing):
 *
 * 1) Scenes:
 *    - Fetch scenes via Hue API v1: GET http://<ip>/api/<user>/scenes
 *      (This avoids v2 HTTPS + hue-application-key requirements.)
 *
 * 2) v2 metadata parsing fixes:
 *    - v2 uses metadata.name as a plain string (not {name:{value:...}})
 *      So we read metadata.optString("name").
 *
 * NOTE:
 * - If you later want v2 scenes, you must use:
 *     https://<ip>/clip/v2/resource/scene
 *   and include header:
 *     hue-application-key: <v2 app key>
 *   plus handle the bridge's TLS certificate.
 */

data class HueLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val brightness: Int, // 0..100
    val supportsColor: Boolean = false,
    val supportsCt: Boolean = false,
    val ctMired: Int? = null
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

data class HueScene(
    val id: String,
    val name: String,
    val owner: String? = null
)

class HueViewModel(application: Application) : AndroidViewModel(application) {
    private val _lights = MutableStateFlow<List<HueLight>>(emptyList())
    val lights: StateFlow<List<HueLight>> = _lights.asStateFlow()

    private val _groups = MutableStateFlow<List<HueGroup>>(emptyList())
    val groups: StateFlow<List<HueGroup>> = _groups.asStateFlow()

    private val _scenes = MutableStateFlow<List<HueScene>>(emptyList())
    val scenes: StateFlow<List<HueScene>> = _scenes.asStateFlow()

    private val context = application.applicationContext

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _bridgeIp = MutableStateFlow<String?>(null)
    val bridgeIp: StateFlow<String?> = _bridgeIp.asStateFlow()

    private val _hueUsername = MutableStateFlow<String?>(null)
    val hueUsername: StateFlow<String?> = _hueUsername.asStateFlow()

    private val _discovered = MutableStateFlow<List<BridgeInfo>>(emptyList())
    val discovered: StateFlow<List<BridgeInfo>> = _discovered.asStateFlow()

    private val _pairingStatus = MutableStateFlow<String?>(null)
    val pairingStatus: StateFlow<String?> = _pairingStatus.asStateFlow()

    private val _brightness = MutableStateFlow(100)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _color = MutableStateFlow(0xFFFFFFFF.toInt())
    val color: StateFlow<Int> = _color.asStateFlow()

    private val client: OkHttpClient
    private val connectivityManager: ConnectivityManager
    private val networkCallback: ConnectivityManager.NetworkCallback
    private val isRefreshing = AtomicBoolean(false)

    init {
        // Build OkHttp client with logging for debug
        val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        client = OkHttpClient.Builder().addInterceptor(logger).build()

        val ip = HueSettingsStore.getBridgeIp(context)
        val user = HueSettingsStore.getHueUsername(context)
        _bridgeIp.value = ip
        _hueUsername.value = user
        updateConnectedState()

        // Setup network callback to detect connectivity changes and react immediately
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                // mark as disconnected when the network is lost; UI will navigate to connect screen
                _isConnected.value = false
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // re-evaluate connection state when network returns
                updateConnectedState()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                // if the available network does not have INTERNET capability, treat as lost
                if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    _isConnected.value = false
                }
            }
        }

        try {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (t: Throwable) {
            // ignore registration failure on older devices; polling still exists as fallback
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
    }

    private fun updateConnectedState() {
        val previouslyConnected = _isConnected.value
        val connected = !_bridgeIp.value.isNullOrEmpty() && !_hueUsername.value.isNullOrEmpty()
        _isConnected.value = connected

        if (connected && !previouslyConnected) {
            refreshHueState()
        }
    }

    /** Discover bridges using the official Hue discovery endpoint */
    fun discoverBridges() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonText = getJson("https://discovery.meethue.com/")
                val arr = JSONArray(jsonText)
                val results = mutableListOf<BridgeInfo>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val ip = obj.optString("internalipaddress", null) ?: continue
                    probeHueBridge(ip)?.let { results.add(it) }
                }

                _discovered.value = results

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
            val xml = getJson("http://$ip/description.xml")

            val lower = xml.lowercase()
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
     */
    fun pairWithBridge(ip: String, retries: Int = 10, delayMs: Long = 1500) {
        viewModelScope.launch(Dispatchers.IO) {
            _pairingStatus.value = "starting"

            for (attempt in 1..retries) {
                try {
                    val body = JSONObject().put("devicetype", "pulsebreak#companion").toString()
                    val responseText = postJson("http://$ip/api", body)
                    Log.d("HueViewModel", "Pair response: $responseText")
                    val arr = JSONArray(responseText)
                    if (arr.length() > 0) {
                        val resp = arr.getJSONObject(0)

                        if (resp.has("success")) {
                            val username = resp.getJSONObject("success").optString("username", null)
                            if (!username.isNullOrEmpty()) {
                                HueSettingsStore.persist(context, ip, username)
                                _hueUsername.value = username
                                _bridgeIp.value = ip
                                updateConnectedState()
                                _pairingStatus.value = "paired"
                                return@launch
                            }
                        }

                        if (resp.has("error")) {
                            val err = resp.getJSONObject("error")
                            val type = err.optInt("type")
                            val desc = err.optString("description")

                            Log.w("HueViewModel", "Pair attempt $attempt error: $desc ($type)")
                            _pairingStatus.value =
                                if (type == 101) "link_button_not_pressed"
                                else "error: $desc"
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HueViewModel", "Pair attempt failed: ${e.message}", e)
                    _pairingStatus.value = "error: ${e.message}"
                }

                delay(delayMs)
            }

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
        // Avoid concurrent refreshes
        if (!isRefreshing.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                internalRefreshHueState()
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    // Move the previous refresh logic to an internal suspending function to keep the guard clean
    private suspend fun internalRefreshHueState() {
        withContext(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@withContext

            try {
                // 1) Fetch lights — try Hue API v2 first (/clip/v2/resource/light)
                var lightsJsonObj: JSONObject? = null
                try {
                    val lightsV2 = getJsonObject("http://$ip/clip/v2/resource/light")
                    lightsJsonObj = lightsV2
                } catch (_: Exception) {
                    // fallback to v1
                    try {
                        val lightsV1Text = getJson("http://$ip/api/$user/lights")
                        lightsJsonObj = JSONObject(lightsV1Text)
                    } catch (_: Exception) {
                        lightsJsonObj = null
                    }
                }

                val newLights = mutableListOf<HueLight>()
                if (lightsJsonObj != null) {
                    if (lightsJsonObj.has("data")) {
                        // v2 format
                        val arr = lightsJsonObj.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val id = obj.optString("id")

                            // FIX: v2 metadata.name is typically a plain string
                            val name = obj.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: id

                            val on = obj.optJSONObject("on")?.optBoolean("on", false)
                                ?: obj.optJSONObject("action")?.optJSONObject("on")?.optBoolean("on", false)
                                ?: false

                            val dimming = obj.optJSONObject("dimming")
                                ?.optDouble("brightness", -1.0)
                                ?.let { if (it >= 0) it.toInt() else -1 }
                            val brightnessPercent = if (dimming != null && dimming >= 0) dimming.coerceIn(0, 100) else 100

                            val supportsColor = obj.has("color") || obj.optJSONObject("color") != null
                            val supportsCt = obj.has("color") && obj.optJSONObject("color")?.has("temperature") == true

                            val mirek = obj.optJSONObject("color")
                                ?.optJSONObject("temperature")
                                ?.optInt("mirek", -1)
                                ?.let { if (it > 0) it else null }

                            newLights.add(
                                HueLight(
                                    id = id,
                                    name = name,
                                    on = on,
                                    brightness = brightnessPercent,
                                    supportsColor = supportsColor,
                                    supportsCt = supportsCt,
                                    ctMired = mirek
                                )
                            )
                        }
                    } else {
                        // v1 format: object with keys
                        val lightIds = lightsJsonObj.keys()
                        while (lightIds.hasNext()) {
                            val id = lightIds.next()
                            val obj = lightsJsonObj.getJSONObject(id)
                            val name = obj.optString("name", "Light $id")
                            val state = obj.optJSONObject("state") ?: JSONObject()
                            val on = state.optBoolean("on", false)
                            val bri = state.optInt("bri", 254)
                            val brightnessPercent = (bri * 100 / 254.0).toInt().coerceIn(0, 100)
                            val capabilities = obj.optJSONObject("capabilities")
                            val control = capabilities?.optJSONObject("control")
                            val hasCtRange = control?.has("ct") == true
                            val hasColorGamut = control?.has("colorgamut") == true || control?.has("colorgamuttype") == true
                            val ct = state.optInt("ct", -1).let { if (it > 0) it else null }
                            val supportsCt = hasCtRange || ct != null
                            val supportsColor = hasColorGamut

                            newLights.add(
                                HueLight(
                                    id = id,
                                    name = name,
                                    on = on,
                                    brightness = brightnessPercent,
                                    supportsColor = supportsColor,
                                    supportsCt = supportsCt,
                                    ctMired = ct
                                )
                            )
                        }
                    }
                }

                // 2) Fetch groups (rooms + zones) via v2 /clip/v2/resource/room and /zone
                val newGroups = mutableListOf<HueGroup>()
                try {
                    val rooms = getJsonObject("http://$ip/clip/v2/resource/room")
                    val roomData = rooms.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until roomData.length()) {
                        val obj = roomData.getJSONObject(i)
                        val id = obj.optString("id")

                        // FIX: v2 metadata.name is typically a plain string
                        val name = obj.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: id

                        val relationship = obj.optJSONObject("relationships")
                        val lightsList = mutableListOf<String>()
                        relationship?.optJSONObject("light")?.optJSONArray("data")?.let { arr ->
                            for (j in 0 until arr.length()) lightsList.add(arr.getJSONObject(j).optString("id"))
                        }
                        newGroups.add(HueGroup(id = id, name = name, type = "Room", lightIds = lightsList))
                    }
                } catch (_: Exception) {
                    // fallback to v1 groups
                    try {
                        val groupsJson = getJsonObject("http://$ip/api/$user/groups")
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
                            val avgBrightness =
                                if (memberBrightness.isNotEmpty()) memberBrightness.map { it.brightness }.average().toInt()
                                else null
                            newGroups.add(HueGroup(id = id, name = name, type = type, lightIds = lightIdsList, brightness = avgBrightness))
                        }
                    } catch (_: Exception) {
                    }
                }

                _lights.value = newLights
                _groups.value = newGroups

                // 3) Fetch scenes — USE v1 (avoids v2 HTTPS + hue-application-key)
                try {
                    val scenesJson = getJsonObject("http://$ip/api/$user/scenes")
                    val sceneIds = scenesJson.keys()
                    val sceneList = mutableListOf<HueScene>()
                    while (sceneIds.hasNext()) {
                        val sid = sceneIds.next()
                        val sObj = scenesJson.optJSONObject(sid) ?: continue
                        val sName = sObj.optString("name", sid)
                        val owner = sObj.optString("owner", null)
                        sceneList.add(HueScene(id = sid, name = sName, owner = owner))
                    }
                    _scenes.value = sceneList
                } catch (e: Exception) {
                    Log.w("HueViewModel", "fetch scenes (v1) failed: ${e.message}", e)
                    _scenes.value = emptyList()
                }

            } catch (e: Exception) {
                Log.w("HueViewModel", "refreshHueState failed: ${e.message}", e)
                // If refresh failed due to network errors make sure UI knows we're not connected/reachable.
                // This ensures screens that depend on `isConnected` will notice and fallback to the connect flow.
                _isConnected.value = false
            }
        }
    }

    fun setBrightnessForAllLights(brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setBrightnessForAllLightsSuspend(brightnessPercent)
        }
    }

    suspend fun setBrightnessForAllLightsSuspend(brightnessPercent: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
        try {
            val lightsJson = getJsonObject("http://$ip/api/$user/lights")
            val keys = lightsJson.keys()
            while (keys.hasNext()) {
                val lightId = keys.next()
                putJson(
                    "http://$ip/api/$user/lights/$lightId/state",
                    JSONObject().put("bri", bri).put("on", bri > 1).toString()
                )
            }
            _brightness.value = brightnessPercent.coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set brightness failed: ${e.message}", e)
        }
    }

    /**
     * Set CT (mired) for lights that support CT.
     * Typical Hue range: 153 (cool) .. 500 (warm) (device-specific, but this is a safe clamp).
     */
    fun setCtForAllLights(ctMired: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setCtForAllLightsSuspend(ctMired)
        }
    }

    suspend fun setCtForAllLightsSuspend(ctMired: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val ct = ctMired.coerceIn(153, 500)
        try {
            val lightsJson = getJsonObject("http://$ip/api/$user/lights")
            val keys = lightsJson.keys()
            while (keys.hasNext()) {
                val lightId = keys.next()
                val obj = lightsJson.getJSONObject(lightId)
                if (!lightSupportsCt(obj)) continue
                putJson("http://$ip/api/$user/lights/$lightId/state", JSONObject().put("ct", ct).put("on", true).toString())
            }
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set ct failed: ${e.message}", e)
        }
    }

    fun setColorForAllLights(argb: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setColorForAllLightsSuspend(argb)
        }
    }

    suspend fun setColorForAllLightsSuspend(argb: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        try {
            val xy = argbToXy(argb)
            val lightsJson = try { getJsonObject("http://$ip/clip/v2/resource/light") } catch (_: Exception) { null }
            if (lightsJson != null && lightsJson.has("data")) {
                val data = lightsJson.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val lightObj = data.getJSONObject(i)
                    val lightId = lightObj.optString("id")
                    val supportsColorV2 = lightObj.has("color") || lightObj.optJSONObject("color") != null
                    if (!supportsColorV2) continue
                    val body = JSONObject()
                        .put("on", JSONObject().put("on", true))
                        .put("dimming", JSONObject().put("brightness", 100))
                        .put("color", JSONObject().put("xy", JSONObject().put("x", xy.first).put("y", xy.second)))
                    putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
                }
            } else {
                val lightsV1 = getJsonObject("http://$ip/api/$user/lights")
                val keys = lightsV1.keys()
                while (keys.hasNext()) {
                    val lightId = keys.next()
                    val obj = lightsV1.getJSONObject(lightId)
                    if (!lightSupportsColor(obj)) continue
                    val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("on", true)
                    putJson("http://$ip/api/$user/lights/$lightId/state", body.toString())
                }
            }
            _color.value = argb
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set color failed: ${e.message}", e)
        }
    }

    // Set color for a single light (suspend). Uses v2 endpoint when available, falls back to v1.
    fun setColorForLight(lightId: String, argb: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setColorForLightSuspend(lightId, argb)
        }
    }

    suspend fun setColorForLightSuspend(lightId: String, argb: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        try {
            val xy = argbToXy(argb)
            // Try v2 per-light update
            try {
                val body = JSONObject()
                    .put("on", JSONObject().put("on", true))
                    .put("dimming", JSONObject().put("brightness", 100))
                    .put("color", JSONObject().put("xy", JSONObject().put("x", xy.first).put("y", xy.second)))
                putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
                _color.value = argb
                return@withContext
            } catch (_: Exception) {
                // fall through to v1
            }

            // v1 per-light update
            try {
                val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("on", true)
                putJson("http://$ip/api/$user/lights/$lightId/state", body.toString())
                _color.value = argb
            } catch (e: Exception) {
                Log.w("HueViewModel", "Set color for light $lightId failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set color for light $lightId failed: ${e.message}", e)
        }
    }

    // Set color (xy) and brightness together for a single light to avoid visible flashes.
    fun setColorAndBrightnessForLight(lightId: String, argb: Int, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setColorAndBrightnessForLightSuspend(lightId, argb, brightnessPercent)
        }
    }

    suspend fun setColorAndBrightnessForLightSuspend(lightId: String, argb: Int, brightnessPercent: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            val xy = argbToXy(argb)
            // Try v2 per-light update with both dimming and color
            try {
                val body = JSONObject()
                    .put("on", JSONObject().put("on", bri > 0))
                    .put("dimming", JSONObject().put("brightness", bri))
                    .put("color", JSONObject().put("xy", JSONObject().put("x", xy.first).put("y", xy.second)))
                putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
                _color.value = argb
                _brightness.value = bri
                return@withContext
            } catch (_: Exception) {
                // fall back to v1
            }

            // v1 per-light update: send bri and xy together
            try {
                val apiBri = (bri * 254 / 100).coerceIn(1, 254)
                val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("bri", apiBri).put("on", apiBri > 1)
                putJson("http://$ip/api/$user/lights/$lightId/state", body.toString())
                _color.value = argb
                _brightness.value = bri
            } catch (e: Exception) {
                Log.w("HueViewModel", "Set color+brightness for light $lightId failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set color+brightness for light $lightId failed: ${e.message}", e)
        }
    }

    // Set ct and brightness together per-light.
    fun setCtAndBrightnessForLight(lightId: String, ctMired: Int, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setCtAndBrightnessForLightSuspend(lightId, ctMired, brightnessPercent)
        }
    }

    suspend fun setCtAndBrightnessForLightSuspend(lightId: String, ctMired: Int, brightnessPercent: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val ct = ctMired.coerceIn(153, 500)
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            // v2 per-light
            try {
                val body = JSONObject()
                    .put("on", JSONObject().put("on", bri > 0))
                    .put("dimming", JSONObject().put("brightness", bri))
                    .put("color", JSONObject().put("temperature", JSONObject().put("mirek", ct)))
                putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
                _brightness.value = bri
                return@withContext
            } catch (_: Exception) {
                // v1 fallback
            }

            try {
                val apiBri = (bri * 254 / 100).coerceIn(1, 254)
                putJson("http://$ip/api/$user/lights/$lightId/state", JSONObject().put("ct", ct).put("bri", apiBri).put("on", apiBri > 1).toString())
                _brightness.value = bri
            } catch (e: Exception) {
                Log.w("HueViewModel", "Set ct+brightness for light $lightId failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.w("HueViewModel", "Set ct+brightness for light $lightId failed: ${e.message}", e)
        }
    }

    fun setGroupBrightness(groupId: String, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setGroupBrightnessSuspend(groupId, brightnessPercent)
        }
    }

    suspend fun setGroupBrightnessSuspend(groupId: String, brightnessPercent: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
        try {
            try {
                val body = JSONObject().put("dimming", JSONObject().put("brightness", brightnessPercent))
                putJson("http://$ip/clip/v2/resource/room/$groupId", body.toString())
            } catch (_: Exception) {
                putJson(
                    "http://$ip/api/$user/groups/$groupId/action",
                    JSONObject().put("bri", bri).put("on", bri > 1).toString()
                )
            }
            _groups.value = _groups.value.map { if (it.id == groupId) it.copy(brightness = brightnessPercent.coerceIn(0, 100)) else it }
        } catch (e: Exception) {
            Log.w("HueViewModel", "setGroupBrightness failed: ${e.message}", e)
        }
    }

    fun setLightBrightness(lightId: String, brightnessPercent: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            setLightBrightnessSuspend(lightId, brightnessPercent)
        }
    }

    suspend fun setLightBrightnessSuspend(lightId: String, brightnessPercent: Int) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
        try {
            try {
                val body = JSONObject()
                    .put("dimming", JSONObject().put("brightness", brightnessPercent))
                    .put("on", JSONObject().put("on", bri > 1))
                putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
            } catch (_: Exception) {
                putJson(
                    "http://$ip/api/$user/lights/$lightId/state",
                    JSONObject().put("bri", bri).put("on", bri > 1).toString()
                )
            }
            _lights.value = _lights.value.map { if (it.id == lightId) it.copy(brightness = brightnessPercent.coerceIn(0, 100), on = bri > 1) else it }
        } catch (e: Exception) {
            Log.w("HueViewModel", "setLightBrightness failed: ${e.message}", e)
        }
    }

    fun setLightOn(lightId: String, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            setLightOnSuspend(lightId, on)
        }
    }

    suspend fun setLightOnSuspend(lightId: String, on: Boolean) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value ?: return@withContext
        try {
            try {
                val body = JSONObject().put("on", JSONObject().put("on", on))
                putJson("http://$ip/clip/v2/resource/light/$lightId", body.toString())
            } catch (_: Exception) {
                putJson("http://$ip/api/$user/lights/$lightId/state", JSONObject().put("on", on).toString())
            }
            _lights.value = _lights.value.map { if (it.id == lightId) it.copy(on = on) else it }
        } catch (e: Exception) {
            Log.w("HueViewModel", "setLightOn failed: ${e.message}", e)
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string()
            if (!resp.isSuccessful) {
                // include body for easier debugging
                throw Exception("HTTP ${resp.code}${if (!bodyText.isNullOrBlank()) ": $bodyText" else ""}")
            }
            return bodyText ?: "{}"
        }
    }

    private fun getJsonObject(url: String): JSONObject = JSONObject(getJson(url))

    private fun postJson(url: String, body: String): String {
        val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}${if (!bodyText.isNullOrBlank()) ": $bodyText" else ""}")
            }
            return bodyText ?: "[]"
        }
    }

    private fun putJson(url: String, body: String) {
        val req = Request.Builder().url(url).put(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val bodyText = resp.body?.string()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}${if (!bodyText.isNullOrBlank()) ": $bodyText" else ""}")
            }
        }
    }

    private fun lightSupportsColor(lightObj: JSONObject): Boolean {
        val control = lightObj.optJSONObject("capabilities")?.optJSONObject("control")
        val hasGamut = control?.has("colorgamut") == true || control?.has("colorgamuttype") == true
        return hasGamut
    }

    private fun lightSupportsCt(lightObj: JSONObject): Boolean {
        val control = lightObj.optJSONObject("capabilities")?.optJSONObject("control")
        val hasCtRange = control?.has("ct") == true
        val hasCtState = lightObj.optJSONObject("state")?.has("ct") == true
        return hasCtRange || hasCtState
    }

    // ARGB -> CIE XY conversion (approximate)
    private fun argbToXy(argb: Int): Pair<Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0

        fun gamma(u: Double): Double = if (u > 0.04045) Math.pow((u + 0.055) / 1.055, 2.4) else u / 12.92
        val R = gamma(r)
        val G = gamma(g)
        val B = gamma(b)

        val X = R * 0.4124 + G * 0.3576 + B * 0.1805
        val Y = R * 0.2126 + G * 0.7152 + B * 0.0722
        val Z = R * 0.0193 + G * 0.1192 + B * 0.9505

        val cx = X / (X + Y + Z)
        val cy = Y / (X + Y + Z)
        if (cx.isNaN() || cy.isNaN()) return Pair(0.0, 0.0)
        return Pair(cx, cy)
    }

    // Robust scene recall: try Hue API v2 scene action, and fall back to v1 group action if available.
    // NOTE: v2 recall here is still using http; for real v2, switch to https + hue-application-key.
    suspend fun recallSceneForGroupSuspend(sceneId: String, groupId: String? = null) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value

        // 1) Try v2 empty-body recall (may 404 on many bridges over http)
        try {
            val resp = try {
                postJson("http://$ip/clip/v2/resource/scene/$sceneId/action", "{}")
            } catch (e: Exception) {
                null
            }
            if (resp != null) {
                Log.d("HueViewModel", "v2 scene recall response: $resp")
                return@withContext
            }
        } catch (e: Exception) {
            Log.d("HueViewModel", "v2 scene recall failed: ${e.message}")
        }

        // 2) Try v1 scenes recall endpoint: POST /api/<user>/scenes/<id>/recall
        if (!user.isNullOrEmpty()) {
            try {
                try {
                    val resp = postJson("http://$ip/api/$user/scenes/$sceneId/recall", "{}")
                    Log.d("HueViewModel", "v1 scene recall response: $resp")
                    return@withContext
                } catch (e: Exception) {
                    Log.d("HueViewModel", "v1 scenes recall failed: ${e.message}")
                }

                // 3) v1 group action fallback: PUT /api/<user>/groups/<groupId>/action with {"scene":"<sceneId>"}
                val gids = if (!groupId.isNullOrEmpty()) listOf(groupId) else listOf("0")
                val payload = JSONObject().put("scene", sceneId)
                for (gid in gids) {
                    try {
                        putJson("http://$ip/api/$user/groups/$gid/action", payload.toString())
                        Log.d("HueViewModel", "v1 group scene recall for group $gid ok")
                        return@withContext
                    } catch (e: Exception) {
                        Log.d("HueViewModel", "v1 group recall for $gid failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("HueViewModel", "v1 scene recall overall failed: ${e.message}", e)
            }
        }

        // 4) Final v2 attempt with a body that includes target if available
        try {
            val finalBody =
                if (!groupId.isNullOrEmpty()) JSONObject().put("target", JSONObject().put("rid", groupId)).toString()
                else "{}"
            val resp = try { postJson("http://$ip/clip/v2/resource/scene/$sceneId/action", finalBody) } catch (e: Exception) { null }
            if (resp != null) Log.d("HueViewModel", "v2 final scene recall response: $resp")
        } catch (e: Exception) {
            Log.w("HueViewModel", "final scene recall failed: ${e.message}", e)
        }
    }
}
