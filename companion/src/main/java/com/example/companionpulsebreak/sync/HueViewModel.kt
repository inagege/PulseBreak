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
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import android.graphics.Color as AndroidColor
import java.util.concurrent.atomic.AtomicBoolean
import android.annotation.SuppressLint
import kotlin.math.pow

/**
 * HueViewModel: Hue v1-only implementation notes
 *
 * - This file uses Hue API v1 (/api/<user>/...) exclusively for reads and writes.
 *   Many Hue bridges reject v2 write calls over plain HTTP or require a v2
 *   application key + HTTPS; using v1 increases compatibility across devices.
 */

data class HueLight(
    val id: String,
    val name: String,
    val on: Boolean,
    val brightness: Int, // 0..100
    val supportsColor: Boolean = false,
    val supportsCt: Boolean = false,
    val ctMired: Int? = null,
    val colorGamut: List<Pair<Double, Double>>? = null // optional triangle of xy points
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
    val owner: String? = null,
    val previewArgb: Int = 0 // 0 == no preview
)

@SuppressLint("MissingPermission") // registerNetworkCallback requires ACCESS_NETWORK_STATE; callers must add permission in manifest
class HueViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // Default gamma value used when mapping Hue bri to perceived luminance.
        private const val DEFAULT_PREVIEW_GAMMA = 2.2

        // Enable to log scene preview computation details (useful for local tuning only)
        private const val PREVIEW_DEBUG = false
    }

    // Runtime-configurable preview gamma (changing this and refreshing will update computed scene previews)
    private val _previewGamma = MutableStateFlow(DEFAULT_PREVIEW_GAMMA)
    val previewGamma: StateFlow<Double> = _previewGamma.asStateFlow()

    fun getPreviewGammaValue(): Double = _previewGamma.value

    // Internal storage for raw scene JSON objects so previews can be recomputed without refetching from the bridge.
    private val scenesRaw: MutableMap<String, JSONObject> = mutableMapOf()

    // Recompute previewArgb for all cached scenes using current lights state and preview gamma.
    fun recomputeScenePreviews() {
        try {
            val updated = mutableListOf<HueScene>()
            for ((sid, sObj) in scenesRaw) {
                var preview = 0
                try {
                    val lightstates = sObj.optJSONObject("lightstates")
                    if (lightstates != null) {
                        var rLinearSum = 0.0
                        var gLinearSum = 0.0
                        var bLinearSum = 0.0
                        var collectedCount = 0.0
                        val lsKeys = lightstates.keys()
                        while (lsKeys.hasNext()) {
                            val lid = lsKeys.next()
                            val lObj = lightstates.optJSONObject(lid) ?: continue
                            val on = lObj.optBoolean("on", false)
                            if (!on) continue

                            var argb: Int? = null
                            val lightGamut = try { _lights.value.find { it.id == lid }?.colorGamut } catch (_: Exception) { null }

                            val xyArr = lObj.optJSONArray("xy")
                            if (xyArr != null && xyArr.length() >= 2) {
                                val x = xyArr.optDouble(0, -1.0)
                                val y = xyArr.optDouble(1, -1.0)
                                val bri = lObj.optInt("bri", 254)
                                if (x >= 0.0 && y > 0.0) {
                                    argb = try { xyToArgb(x, y, bri, lightGamut) } catch (_: Exception) { null }
                                }
                            }

                            if (argb == null && lObj.has("hue")) {
                                val hueVal = lObj.optInt("hue", -1)
                                val satVal = lObj.optInt("sat", 254)
                                val bri = lObj.optInt("bri", 254).coerceIn(1, 254)
                                if (hueVal >= 0) {
                                    val hueDeg = (hueVal.toFloat() * 360f / 65535f) % 360f
                                    val satf = (satVal.toFloat() / 254f).coerceIn(0f, 1f)
                                    val valf = (bri.toFloat() / 254f).coerceIn(0.01f, 1f)
                                    try {
                                        val tmpArgb = AndroidColor.HSVToColor(floatArrayOf(hueDeg, satf, valf))
                                        val (tx, ty) = argbToXy(tmpArgb)
                                        argb = try { xyToArgb(tx, ty, bri, lightGamut) } catch (_: Exception) { null }
                                    } catch (_: Exception) { argb = null }
                                }
                            }

                            if (argb == null) {
                                val ct = lObj.optInt("ct", -1)
                                if (ct > 0) {
                                    val rgb = ctToRgb(ct)
                                    if (rgb != null) argb = 0xFF000000.toInt() or rgb
                                }
                            }

                            if (argb == null) argb = 0xFFFFFFFF.toInt()

                            val rr = ((argb shr 16) and 0xFF) / 255.0
                            val gg = ((argb shr 8) and 0xFF) / 255.0
                            val bb = (argb and 0xFF) / 255.0
                            fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

                            val targetBri = lObj.optInt("bri", 254).coerceIn(1, 254)
                            val weight = (targetBri / 254.0).pow(_previewGamma.value)

                            rLinearSum += srgbToLinear(rr) * weight
                            gLinearSum += srgbToLinear(gg) * weight
                            bLinearSum += srgbToLinear(bb) * weight
                            collectedCount += weight
                        }

                        if (collectedCount > 0) {
                            val inv = 1.0 / collectedCount
                            val rLinAvg = rLinearSum * inv
                            val gLinAvg = gLinearSum * inv
                            val bLinAvg = bLinearSum * inv
                            fun linearToSrgbByte(l: Double): Int {
                                val v = if (l <= 0.0031308) 12.92 * l else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
                                return ((v * 255.0).coerceIn(0.0, 255.0)).toInt()
                            }
                            val rOut = linearToSrgbByte(rLinAvg)
                            val gOut = linearToSrgbByte(gLinAvg)
                            val bOut = linearToSrgbByte(bLinAvg)
                            preview = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                        }
                    }
                } catch (_: Exception) {}

                val sName = sObj.optString("name", sid)
                val ownerRaw = sObj.optString("owner", "")
                val owner = ownerRaw.takeIf { it.isNotBlank() }
                updated.add(HueScene(id = sid, name = sName, owner = owner, previewArgb = preview))
            }

            // update _scenes on the main thread (fast, in memory)
            _scenes.value = updated
        } catch (e: Exception) {
            Log.w("HueViewModel", "recomputeScenePreviews failed: ${e.message}")
        }
    }

    private val _lights = MutableStateFlow<List<HueLight>>(emptyList())
    val lights: StateFlow<List<HueLight>> = _lights.asStateFlow()

    private val _groups = MutableStateFlow<List<HueGroup>>(emptyList())
    val groups: StateFlow<List<HueGroup>> = _groups.asStateFlow()

    private val _scenes = MutableStateFlow<List<HueScene>>(emptyList())
    val scenes: StateFlow<List<HueScene>> = _scenes.asStateFlow()

    // avoid holding an extra context field to prevent lint/static leaks; use getApplication() when needed

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _bridgeIp = MutableStateFlow<String?>(null)
    val bridgeIp: StateFlow<String?> = _bridgeIp.asStateFlow()

    private val _hueUsername = MutableStateFlow<String?>(null)
    @Suppress("unused")
    val hueUsername: StateFlow<String?> = _hueUsername.asStateFlow()

    private val _discovered = MutableStateFlow<List<BridgeInfo>>(emptyList())
    val discovered: StateFlow<List<BridgeInfo>> = _discovered.asStateFlow()

    private val _pairingStatus = MutableStateFlow<String?>(null)
    val pairingStatus: StateFlow<String?> = _pairingStatus.asStateFlow()

    private val _brightness = MutableStateFlow(100)
    @Suppress("unused")
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
        val dispatcher = Dispatcher().apply {
            // allow higher concurrency for faster parallel updates to the bridge; tuned conservatively
            maxRequests = 64
            maxRequestsPerHost = 64
        }
        client = OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor(logger).build()

        val appCtx = getApplication<Application>().applicationContext
        val ip = HueSettingsStore.getBridgeIp(appCtx)
        val user = HueSettingsStore.getHueUsername(appCtx)

        // Observe gamma changes and recompute previews immediately when it changes
        viewModelScope.launch {
            previewGamma.collect { _ ->
                try { recomputeScenePreviews() } catch (_: Exception) {}
            }
        }
        _bridgeIp.value = ip
        _hueUsername.value = user
        try {
            val displayUser = user?.let { if (it.length > 6) it.take(3) + "..." + it.takeLast(3) else it }
            Log.d("HueViewModel", "init: bridgeIp=$ip username=${displayUser ?: "<none>"}")
        } catch (_: Exception) {}
        updateConnectedState()

        // Setup network callback to detect connectivity changes and react immediately
        connectivityManager = appCtx.getSystemService(ConnectivityManager::class.java)
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
                    val ip = obj.optString("internalipaddress", "").takeIf { it.isNotBlank() } ?: continue
                    val info = probeHueBridge(ip)
                    if (info != null) results.add(info)
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

    private suspend fun probeHueBridge(ip: String): BridgeInfo? {
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
                    val responseText = postJson("http://$ip/api", body) ?: "[]"
                    Log.d("HueViewModel", "Pair response: $responseText")
                    val arr = JSONArray(responseText)
                    if (arr.length() > 0) {
                        val resp = arr.getJSONObject(0)

                        if (resp.has("success")) {
                            val username = resp.getJSONObject("success").optString("username", "")
                            if (username.isNotEmpty()) {
                                HueSettingsStore.persist(getApplication<Application>().applicationContext, ip, username)
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
            HueSettingsStore.persist(getApplication<Application>().applicationContext, null, null)
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

    /**
     * Suspend until Hue state is refreshed and lights/groups are available or timeout elapses.
     * This is a helper used by callers that need fresh light/group info before taking action.
     */
    suspend fun refreshHueStateAndWait(timeoutMs: Long = 6000L, pollMs: Long = 200L) {
        // Trigger a refresh (no-op if a refresh is already running)
        refreshHueState()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                // if bridge info present and at least one light or group discovered, consider ready
                if (!(_bridgeIp.value.isNullOrEmpty() || _hueUsername.value.isNullOrEmpty())) {
                    if (_lights.value.isNotEmpty() || _groups.value.isNotEmpty()) return
                }
            } catch (_: Exception) {}
            try { delay(pollMs) } catch (_: Exception) { break }
        }
        // timeout — return anyway; callers should handle empty lists
    }

    // Move the previous refresh logic to an internal suspending function to keep the guard clean
    private suspend fun internalRefreshHueState() {
        withContext(Dispatchers.IO) {
            val ip = _bridgeIp.value
            val user = _hueUsername.value
            if (ip.isNullOrEmpty() || user.isNullOrEmpty()) return@withContext

            try {
                // 1) Fetch lights — use v1 API only (/api/<user>/lights)
                var lightsJsonObj: JSONObject? = null
                try {
                    val lightsV1Text = getJson("http://$ip/api/$user/lights")
                    lightsJsonObj = JSONObject(lightsV1Text)
                } catch (e: Exception) {
                    Log.w("HueViewModel", "Failed to fetch lights via v1 API: ${e.message}")
                    lightsJsonObj = null
                }

                // Parse v1 lights response (object keyed by id)
                val newLights = mutableListOf<HueLight>()
                if (lightsJsonObj != null) {
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

                // 2) Fetch groups (rooms + zones) via v1 API (/api/<user>/groups)
                val newGroups = mutableListOf<HueGroup>()
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
                        val avgBrightness = if (memberBrightness.isNotEmpty()) memberBrightness.map { it.brightness }.average().toInt() else null
                        newGroups.add(HueGroup(id = id, name = name, type = type, lightIds = lightIdsList, brightness = avgBrightness))
                    }
                } catch (e: Exception) {
                    Log.w("HueViewModel", "Failed to fetch groups via v1 API: ${e.message}")
                }

                _lights.value = newLights
                _groups.value = newGroups
                // Recompute scene previews after lights/groups changed so UI reflects new conversion/gamut
                try { recomputeScenePreviews() } catch (_: Exception) {}

                // 3) Fetch scenes — USE v1
                try {
                    val scenesJson = getJsonObject("http://$ip/api/$user/scenes")
                    val sceneIds = scenesJson.keys()
                    val sceneList = mutableListOf<HueScene>()
                    while (sceneIds.hasNext()) {
                        val sid = sceneIds.next()
                        val sObj = scenesJson.optJSONObject(sid) ?: continue
                        val sName = sObj.optString("name", sid)
                        val ownerRaw = sObj.optString("owner", "")
                        val owner = ownerRaw.takeIf { it.isNotBlank() }

                        // Try to extract a preview color from the scene's lightstates (v1 scenes include per-light targets)
                        var preview = 0
                        try {
                            val lightstates = sObj.optJSONObject("lightstates")
                            if (lightstates != null) {
                                // collect linear RGB vectors for all on lightstates and average them (more perceptually correct)
                                var rLinearSum = 0.0
                                var gLinearSum = 0.0
                                var bLinearSum = 0.0
                                var collectedCount = 0.0
                                val lsKeys = lightstates.keys()
                                while (lsKeys.hasNext()) {
                                    val lid = lsKeys.next()
                                    val lObj = lightstates.optJSONObject(lid) ?: continue
                                    val on = lObj.optBoolean("on", false)
                                    if (!on) continue

                                    var argb: Int? = null

                                    // try to obtain the light's gamut if available (do it once per light)
                                    val lightGamut = try { _lights.value.find { it.id == lid }?.colorGamut } catch (_: Exception) { null }

                                    // 1) XY color (array [x, y])
                                    val xyArr = lObj.optJSONArray("xy")
                                    if (xyArr != null && xyArr.length() >= 2) {
                                        val x = xyArr.optDouble(0, -1.0)
                                        val y = xyArr.optDouble(1, -1.0)
                                        val bri = lObj.optInt("bri", 254)
                                        if (x >= 0.0 && y > 0.0) {
                                            argb = try { xyToArgb(x, y, bri, lightGamut) } catch (_: Exception) { null }
                                        }
                                    }

                                    // 2) Hue & Sat (legacy fields) -> convert HSV -> sRGB -> xy -> apply gamut and brightness mapping
                                    if (argb == null && lObj.has("hue")) {
                                        val hueVal = lObj.optInt("hue", -1)
                                        val satVal = lObj.optInt("sat", 254)
                                        val bri = lObj.optInt("bri", 254).coerceIn(1, 254)
                                        if (hueVal >= 0) {
                                            val hueDeg = (hueVal.toFloat() * 360f / 65535f) % 360f
                                            val satf = (satVal.toFloat() / 254f).coerceIn(0f, 1f)
                                            val valf = (bri.toFloat() / 254f).coerceIn(0.01f, 1f)
                                            try {
                                                val tmpArgb = AndroidColor.HSVToColor(floatArrayOf(hueDeg, satf, valf))
                                                val (tx, ty) = argbToXy(tmpArgb)
                                                argb = try { xyToArgb(tx, ty, bri, lightGamut) } catch (_: Exception) { null }
                                            } catch (_: Exception) {
                                                argb = null
                                            }
                                        }
                                    }

                                    // 3) Color temperature (ct)
                                    if (argb == null) {
                                        val ct = lObj.optInt("ct", -1)
                                        if (ct > 0) {
                                            val rgb = ctToRgb(ct)
                                            if (rgb != null) argb = 0xFF000000.toInt() or rgb
                                        }
                                    }

                                    // Fallback to white if nothing found
                                    if (argb == null) argb = 0xFFFFFFFF.toInt()

                                    // convert sRGB bytes to linear (0..1)
                                    val rr = ((argb shr 16) and 0xFF) / 255.0
                                    val gg = ((argb shr 8) and 0xFF) / 255.0
                                    val bb = (argb and 0xFF) / 255.0

                                    fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

                                    // weight by the light's target brightness so bright lights dominate the preview
                                    val targetBri = lObj.optInt("bri", 254).coerceIn(1, 254)
                                    val weight = (targetBri / 254.0).pow(_previewGamma.value) // same gamma mapping

                                    rLinearSum += srgbToLinear(rr) * weight
                                    gLinearSum += srgbToLinear(gg) * weight
                                    bLinearSum += srgbToLinear(bb) * weight
                                    collectedCount += weight
                                }

                                if (collectedCount > 0) {
                                    val inv = 1.0 / collectedCount
                                    val rLinAvg = rLinearSum * inv
                                    val gLinAvg = gLinearSum * inv
                                    val bLinAvg = bLinearSum * inv

                                    if (PREVIEW_DEBUG) {
                                        Log.d("HueViewModel", "scenePreview: sid=$sid name=$sName rLinAvg=$rLinAvg gLinAvg=$gLinAvg bLinAvg=$bLinAvg count=$collectedCount")
                                    }

                                    // convert linear back to sRGB
                                    fun linearToSrgbByte(l: Double): Int {
                                        val v = if (l <= 0.0031308) 12.92 * l else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
                                        return ( (v * 255.0).coerceIn(0.0, 255.0) ).toInt()
                                    }

                                    val rOut = linearToSrgbByte(rLinAvg)
                                    val gOut = linearToSrgbByte(gLinAvg)
                                    val bOut = linearToSrgbByte(bLinAvg)
                                    preview = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
                                }
                            }
                        } catch (_: Exception) {
                        }

                        sceneList.add(HueScene(id = sid, name = sName, owner = owner, previewArgb = preview))
                        // cache raw scene JSON for future recompute
                        try { scenesRaw[sid] = sObj } catch (_: Exception) {}
                    }

                    // Remove duplicates (same scene name) — keep the one with the most complete data (lights + groups)
                    val unique = mutableMapOf<String, HueScene>()
                    for (scene in sceneList) {
                        val existing = unique[scene.name]
                        if (existing == null || scene.id != existing.id) {
                            unique[scene.name] = scene
                        }
                    }
                    _scenes.value = unique.values.toList()
                    // Also recompute from cached raw scenes so preview reflects current gamma & lights immediately
                    try { recomputeScenePreviews() } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.w("HueViewModel", "fetch scenes (v1) failed: ${e.message}", e)
                    _scenes.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e("HueViewModel", "refreshHueState failed: ${e.message}", e)
            }
        }
    }

    // --- Network helpers (suspend) ---
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private suspend fun getJson(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw Exception("empty response")
            body
        }
    }

    private suspend fun getJsonObject(url: String): JSONObject = withContext(Dispatchers.IO) {
        val txt = getJson(url)
        JSONObject(txt)
    }

    private suspend fun postJson(url: String, body: String): String? = withContext(Dispatchers.IO) {
        val rb = body.toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url(url).post(rb).build()
        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message}: $respBody")
            respBody
        }
    }

    private suspend fun putJson(url: String, body: String): String? = withContext(Dispatchers.IO) {
        val rb = body.toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url(url).put(rb).build()
        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} ${resp.message}: $respBody")
            respBody
        }
    }

    // --- Color helpers ---
    // Convert ARGB int to CIE xy pair (Double, Double)
    private fun argbToXy(argb: Int): Pair<Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0

        fun toLinear(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

        val lr = toLinear(r)
        val lg = toLinear(g)
        val lb = toLinear(b)

        // sRGB -> XYZ (D65)
        val X = lr * 0.4124564 + lg * 0.3575761 + lb * 0.1804375
        val Y = lr * 0.2126729 + lg * 0.7151522 + lb * 0.0721750
        val Z = lr * 0.0193339 + lg * 0.1191920 + lb * 0.9503041

        val sum = X + Y + Z
        if (sum == 0.0) return Pair(0.0, 0.0)
        return Pair(X / sum, Y / sum)
    }

    // Convert ct (mired) to approximate RGB integer (0xRRGGBB). Returns null for invalid input.
    private fun ctToRgb(mired: Int): Int? {
        if (mired <= 0) return null
        val kelvin = 1_000_000.0 / mired.toDouble()
        val temp = kelvin / 100.0

        val r = when {
            temp <= 66 -> 255
            else -> {
                var rr = 329.698727446 * Math.pow(temp - 60.0, -0.1332047592)
                rr = rr.coerceIn(0.0, 255.0)
                rr.toInt()
            }
        }

        val g = when {
            temp <= 66 -> {
                var gg = 99.4708025861 * Math.log(temp) - 161.1195681661
                gg = gg.coerceIn(0.0, 255.0)
                gg.toInt()
            }
            else -> {
                var gg = 288.1221695283 * Math.pow(temp - 60.0, -0.0755148492)
                gg = gg.coerceIn(0.0, 255.0)
                gg.toInt()
            }
        }

        val b = when {
            temp >= 66 -> 255
            temp <= 19 -> 0
            else -> {
                var bb = 138.5177312231 * Math.log(temp - 10.0) - 305.0447927307
                bb = bb.coerceIn(0.0, 255.0)
                bb.toInt()
            }
        }

        return (r shl 16) or (g shl 8) or b
    }

    // Convert CIE xy (and brightness) to ARGB approximate color. If gamut is provided, clamp source xy into gamut.
    private fun xyToArgb(x: Double, y: Double, bri: Int?, gamut: List<Pair<Double, Double>>? = null): Int {
        try {
            val (xx, yy) = if (gamut != null && gamut.size >= 3) clampToGamut(x, y, gamut) else Pair(x, y)
            if (yy == 0.0) return 0xFFFFFFFF.toInt()

            // Map Hue 'bri' (1..254) to a perceptual luminance Y using a simple gamma curve.
            // This makes previews visually closer to how bulbs appear at different bri values.
            val bf = (bri ?: 254).coerceIn(1, 254) / 254.0
            // gamma exponent chosen empirically; 2.2 approximates sRGB perceptual response
            val Y = bf.pow(_previewGamma.value)
            val X = Y * (xx / yy)
            val Z = Y * ((1.0 - xx - yy) / yy)

            // XYZ to linear RGB
            var r = X * 3.2406 + Y * -1.5372 + Z * -0.4986
            var g = X * -0.9689 + Y * 1.8758 + Z * 0.0415
            var b = X * 0.0557 + Y * -0.2040 + Z * 1.0570

            // Clip negatives
            r = r.coerceAtLeast(0.0)
            g = g.coerceAtLeast(0.0)
            b = b.coerceAtLeast(0.0)

            // Convert linear RGB to sRGB
            fun linToSrgb(c: Double): Int {
                val v = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
                return ((v * 255.0).coerceIn(0.0, 255.0)).toInt()
            }

            val ri = linToSrgb(r)
            val gi = linToSrgb(g)
            val bi = linToSrgb(b)

            return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        } catch (_: Exception) {
            return 0xFFFFFFFF.toInt()
        }
    }

    // Helper: check if point is inside triangle using barycentric technique
    private fun pointInTriangle(px: Double, py: Double, a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Boolean {
        val (x1, y1) = a
        val (x2, y2) = b
        val (x3, y3) = c
        val denom = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
        if (denom == 0.0) return false
        val l1 = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / denom
        val l2 = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / denom
        val l3 = 1.0 - l1 - l2
        return l1 >= 0.0 && l2 >= 0.0 && l3 >= 0.0
    }

    // Helper: closest point on segment ab to point p
    private fun closestPointOnSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val c1 = vx * wx + vy * wy
        val c2 = vx * vx + vy * vy
        val t = if (c2 == 0.0) 0.0 else (c1 / c2).coerceIn(0.0, 1.0)
        return Pair(ax + t * vx, ay + t * vy)
    }

    // Clamp xy into the gamut triangle by projecting to the nearest point on triangle if outside
    private fun clampToGamut(x: Double, y: Double, gamut: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (gamut.size < 3) return Pair(x, y)
        val a = gamut[0]
        val b = gamut[1]
        val c = gamut[2]
        if (pointInTriangle(x, y, a, b, c)) return Pair(x, y)

        // compute closest point among edges
        val candidates = mutableListOf<Pair<Double, Double>>()
        candidates.add(closestPointOnSegment(x, y, a.first, a.second, b.first, b.second))
        candidates.add(closestPointOnSegment(x, y, b.first, b.second, c.first, c.second))
        candidates.add(closestPointOnSegment(x, y, c.first, c.second, a.first, a.second))
        // also consider vertices
        candidates.add(a); candidates.add(b); candidates.add(c)

        var best = candidates[0]
        var bestDist = Double.MAX_VALUE
        for (p in candidates) {
            val dx = p.first - x
            val dy = p.second - y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = p
            }
        }
        return best
    }

    // --- Per-light suspend helpers (used by automation screens) ---
    suspend fun setLightOnSuspend(lightId: String, on: Boolean, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        try {
            // Use v1 API for writes; if username is missing, log and return (we don't use v2)
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set light on: no v1 username available")
                return@withContext
            }
            val body = JSONObject().put("on", on)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/lights/$lightId/state"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setLightOnSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setLightBrightnessSuspend(lightId: String, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        val bri = (brightnessPercent.coerceIn(0, 100) * 254 / 100).coerceIn(1, 254)
        try {
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set brightness: no v1 username available")
                return@withContext
            }
            val body = JSONObject().put("bri", bri).put("on", bri > 1)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/lights/$lightId/state"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setLightBrightnessSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setColorForLightSuspend(lightId: String, argb: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        try {
            val xy = argbToXy(argb)
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set color: no v1 username available")
                return@withContext
            }
            val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("on", true)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/lights/$lightId/state"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setColorForLightSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setColorAndBrightnessForLightSuspend(lightId: String, argb: Int, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            val xy = argbToXy(argb)
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set color+brightness: no v1 username available")
                return@withContext
            }
            val bri254 = (bri * 254 / 100).coerceIn(1, 254)
            val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("bri", bri254).put("on", bri > 1)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/lights/$lightId/state"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setColorAndBrightnessForLightSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setCtAndBrightnessForLightSuspend(lightId: String, ctMired: Int, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
         val ip = _bridgeIp.value ?: return@withContext
         val user = _hueUsername.value
         val ct = ctMired.coerceIn(153, 500)
         val bri = brightnessPercent.coerceIn(0, 100)
         try {
             // Prefer v1 when we have a username
             if (!user.isNullOrEmpty()) {
                 val bri254 = (bri * 254 / 100).coerceIn(1, 254)
                 val body = JSONObject().put("ct", ct).put("bri", bri254).put("on", true)
                 if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
                 val url = "http://$ip/api/$user/lights/$lightId/state"
                 Log.i("HueViewModel", "PUT $url body=$body")
                 putJson(url, body.toString())
             } else {
                 Log.w("HueViewModel", "Cannot set CT+brightness: no v1 username available")
             }
         } catch (e: Exception) {
             Log.w("HueViewModel", "setCtAndBrightnessForLightSuspend failed: ${e.message}", e)
         }
     }

    // Fetch the raw 'state' object for a light (suspend). Returns null on error.
    suspend fun fetchLightRawState(lightId: String): JSONObject? = withContext(Dispatchers.IO) {
         val ip = _bridgeIp.value ?: return@withContext null
         val user = _hueUsername.value ?: return@withContext null
         try {
             val txt = getJson("http://$ip/api/$user/lights/$lightId")
             val obj = JSONObject(txt)
             return@withContext obj.optJSONObject("state")
         } catch (e: Exception) {
             Log.w("HueViewModel", "fetchLightRawState failed for $lightId: ${e.message}")
             return@withContext null
         }
     }

    // Fetch raw 'state' objects for all lights in a single request. Returns a map id -> state JSONObject (or null) on error.
    suspend fun fetchAllLightsRawStates(): Map<String, JSONObject?> = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext emptyMap()
        val user = _hueUsername.value ?: return@withContext emptyMap()
        try {
            val txt = getJson("http://$ip/api/$user/lights")
            val obj = JSONObject(txt)
            val map = mutableMapOf<String, JSONObject?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val lightObj = obj.optJSONObject(id)
                val state = lightObj?.optJSONObject("state")
                map[id] = state
            }
            return@withContext map
        } catch (e: Exception) {
            Log.w("HueViewModel", "fetchAllLightsRawStates failed: ${e.message}")
            return@withContext emptyMap()
        }
    }

    // Restore a light's state from a raw 'state' JSON object previously captured from the bridge.
    // This attempts to restore color (xy/hue/ct) and brightness, and finally the on/off flag.
    // If stateObj is null, the light will be turned off.
    suspend fun restoreLightStateFromRaw(lightId: String, stateObj: JSONObject?) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        try {
            if (stateObj == null) {
                // Unknown original state: turn off to be safe
                setLightOnSuspend(lightId, false)
                return@withContext
            }

            val wasOn = stateObj.optBoolean("on", false)
            val briRaw = stateObj.optInt("bri", 254).coerceIn(1, 254)
            val briPercent = (briRaw * 100 / 254.0).toInt().coerceIn(0, 100)

            if (!wasOn) {
                setLightOnSuspend(lightId, false)
                return@withContext
            }

            // Try xy first
            val xyArr = stateObj.optJSONArray("xy")
            if (xyArr != null && xyArr.length() >= 2) {
                val x = xyArr.optDouble(0, -1.0)
                val y = xyArr.optDouble(1, -1.0)
                if (x >= 0.0 && y >= 0.0) {
                    val lightGamut = try { _lights.value.find { it.id == lightId }?.colorGamut } catch (_: Exception) { null }
                    try {
                        val argb = xyToArgb(x, y, briRaw, lightGamut)
                        // If the light supports color, set color+brightness; otherwise fallback to brightness.
                        if (_lights.value.find { it.id == lightId }?.supportsColor == true) {
                            setColorAndBrightnessForLightSuspend(lightId, argb, briPercent)
                            return@withContext
                        }
                    } catch (_: Exception) {}
                }
            }

            // Try color temperature
            val ct = stateObj.optInt("ct", -1)
            if (ct > 0 && _lights.value.find { it.id == lightId }?.supportsCt == true) {
                setCtAndBrightnessForLightSuspend(lightId, ct, briPercent)
                return@withContext
            }

            // Try hue/sat
            if (stateObj.has("hue")) {
                val hueVal = stateObj.optInt("hue", -1)
                val satVal = stateObj.optInt("sat", 254)
                if (hueVal >= 0) {
                    val hueDeg = (hueVal.toFloat() * 360f / 65535f) % 360f
                    val satf = (satVal.toFloat() / 254f).coerceIn(0f, 1f)
                    val valf = (briRaw.toFloat() / 254f).coerceIn(0.01f, 1f)
                    try {
                        val tmpArgb = AndroidColor.HSVToColor(floatArrayOf(hueDeg, satf, valf))
                        if (_lights.value.find { it.id == lightId }?.supportsColor == true) {
                            setColorAndBrightnessForLightSuspend(lightId, tmpArgb, briPercent)
                            return@withContext
                        }
                    } catch (_: Exception) {}
                }
            }

            // Fallback: restore brightness and ensure light is on
            setLightBrightnessSuspend(lightId, briPercent)
            // small delay sometimes helps the bridge apply brightness before on
            delay(10)
            setLightOnSuspend(lightId, true)
        } catch (e: Exception) {
            Log.w("HueViewModel", "restoreLightStateFromRaw failed for $lightId: ${e.message}", e)
        }
    }

    // Public helper to compute xy from ARGB (useful for group actions without duplicating logic)
    fun computeXyFromArgb(argb: Int): Pair<Double, Double> = argbToXy(argb)

    // Group-level setters: send a single PUT to /groups/<id>/action to set color/brightness for all members.
    suspend fun setGroupColorAndBrightnessSuspend(groupId: String, argb: Int, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set group color+brightness: no v1 username available")
                return@withContext
            }
            val xy = argbToXy(argb)
            val bri254 = (bri * 254 / 100).coerceIn(1, 254)
            val body = JSONObject().put("xy", JSONArray().put(xy.first).put(xy.second)).put("bri", bri254).put("on", bri > 1)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/groups/$groupId/action"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setGroupColorAndBrightnessSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setGroupCtAndBrightnessForGroupSuspend(groupId: String, ctMired: Int, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        val ct = ctMired.coerceIn(153, 500)
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set group CT+brightness: no v1 username available")
                return@withContext
            }
            val bri254 = (bri * 254 / 100).coerceIn(1, 254)
            val body = JSONObject().put("ct", ct).put("bri", bri254).put("on", true)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/groups/$groupId/action"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setGroupCtAndBrightnessForGroupSuspend failed: ${e.message}", e)
        }
    }

    suspend fun setGroupBrightnessForGroupSuspend(groupId: String, brightnessPercent: Int, immediate: Boolean = false) = withContext(Dispatchers.IO) {
        val ip = _bridgeIp.value ?: return@withContext
        val user = _hueUsername.value
        val bri = brightnessPercent.coerceIn(0, 100)
        try {
            if (user.isNullOrEmpty()) {
                Log.w("HueViewModel", "Cannot set group brightness: no v1 username available")
                return@withContext
            }
            val bri254 = (bri * 254 / 100).coerceIn(1, 254)
            val body = JSONObject().put("bri", bri254).put("on", bri > 1)
            if (immediate) try { body.put("transitiontime", 0) } catch (_: Exception) {}
            val url = "http://$ip/api/$user/groups/$groupId/action"
            Log.i("HueViewModel", "PUT $url body=$body")
            putJson(url, body.toString())
        } catch (e: Exception) {
            Log.w("HueViewModel", "setGroupBrightnessForGroupSuspend failed: ${e.message}", e)
        }
    }

 // end of class
 }
