package com.chrono.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrono.app.ble.CalReading
import com.chrono.app.ble.ChronoBle
import com.chrono.app.ble.ConnState
import com.chrono.app.ble.Proto
import com.chrono.app.ble.RawResult
import android.net.Uri
import com.chrono.app.data.DistanceUnit
import com.chrono.app.data.ResultStore
import com.chrono.app.data.SessionManager
import com.chrono.app.data.TestResult
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.chrono.app.ble.HwInfo
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class Screen { CONNECT, BASELINE, SENSOR1, SENSOR2, DISTANCE, DASHBOARD }

enum class CalPhase { BARE, LOADED }

/** Stored summary of one calibration sweep (times in ns). */
data class CalEntry(
    val medianNs: Long,
    val stddevNs: Long,
    val samples: Int,
    val status: Int,
    val at: Long,
)

class ChronoViewModel(app: Application) : AndroidViewModel(app) {

    val ble = ChronoBle(app)
    private val store = ResultStore(app)
    private val prefs = app.getSharedPreferences("chrono", Application.MODE_PRIVATE)
    val session = SessionManager(app)

    var screen by mutableStateOf(Screen.CONNECT)
        private set

    val results = mutableStateListOf<TestResult>()

    /** Details applied to the next test; all editable on the result afterwards.
     *  Tool/target fields persist across sessions (they rarely change mid-range-day). */
    var pendingLabel by mutableStateOf("")
    var pendingTool by mutableStateOf(prefs.getString("pendTool", "") ?: "")
    var pendingTarget by mutableStateOf(prefs.getString("pendTarget", "") ?: "")
    var pendingTargetDistVal by mutableStateOf(prefs.getString("pendTdVal", "") ?: "")
    var pendingTargetDistUnit by mutableStateOf(prefs.getString("pendTdUnit", "in") ?: "in")

    // ------------------------------------------------- session folder & photos

    /** Asked once per app launch: keep the previous test folder or start fresh. */
    var sessionPrompt by mutableStateOf(session.lastSessionName() != null)
        private set
    val sessionName: String? get() = session.lastSessionName()

    fun chooseContinueSession() { sessionPrompt = false }
    fun chooseNewSession() { session.startNew(); sessionPrompt = false }

    /** "setup" or "after" while the photo dialog is showing. */
    var photoPrompt by mutableStateOf<String?>(null)
        private set
    var photoCount by mutableStateOf(0)
        private set

    private fun promptPhotos(kind: String) { photoPrompt = kind; photoCount = 0 }
    fun dismissPhotoPrompt() { photoPrompt = null }

    /** Create the next photo target inside the right shot folder. */
    fun newPhotoUri(): Uri? {
        val kind = photoPrompt ?: return null
        return session.newPhotoUri(kind)
    }

    fun photoSaved(ok: Boolean, uri: Uri) {
        if (ok) photoCount++
        else runCatching {
            getApplication<Application>().contentResolver.delete(uri, null, null)
        }
    }

    fun openDataFolder() = session.openFolder(getApplication())

    // The distance is kept exactly as the user typed it, in their chosen unit.
    // Converting to meters and back would turn "12 in" into 11.999… on screen.
    var distanceValue by mutableStateOf(prefs.getFloat("distanceValue", 0f).toDouble())
        private set
    var distanceUnit by mutableStateOf(
        runCatching { DistanceUnit.valueOf(prefs.getString("unit", "INCHES")!!) }
            .getOrDefault(DistanceUnit.INCHES)
    )
        private set

    /** Meters, derived only for the velocity math. */
    val distanceM: Double get() = distanceValue * distanceUnit.toMeters

    /** Non-null while the user is re-testing a sensor from the dashboard. */
    var retestSensor by mutableStateOf<Int?>(null)
        private set

    // Break-screens are consumed by every shot: these flip false on each new
    // result, and both must be re-verified before the next arm.
    var sensor1Ready by mutableStateOf(prefs.getBoolean("s1Ready", false))
        private set
    var sensor2Ready by mutableStateOf(prefs.getBoolean("s2Ready", false))
        private set

    private fun setSensorReady(sensor: Int, ready: Boolean) {
        if (sensor == 1) sensor1Ready = ready else sensor2Ready = ready
        prefs.edit().putBoolean(if (sensor == 1) "s1Ready" else "s2Ready", ready).apply()
    }

    private val setupDone: Boolean get() = prefs.getBoolean("setupDone", false)

    val isSimulation: Boolean get() = ble.isSimulation

    // --------------------------------------------------------- calibration
    // Keys: "b1"/"b2" = bare-port baseline, "l1"/"l2" = loaded (sensor attached).
    val calData = mutableStateMapOf<String, CalEntry>()
    var calRunning by mutableStateOf(false)
        private set
    private val calQueue = ArrayDeque<Pair<Int, CalPhase>>()
    private var pendingCal: Pair<Int, CalPhase>? = null
    private var calTimeoutJob: Job? = null

    init {
        results.addAll(store.load())
        ble.simDistanceM = distanceM
        for (key in listOf("b1", "b2", "l1", "l2")) {
            prefs.getString("cal_$key", null)?.split(",")?.let { p ->
                if (p.size >= 5) calData[key] = CalEntry(
                    p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toLong()
                )
            }
        }
        viewModelScope.launch { ble.cal.collect { onCalReading(it) } }
        viewModelScope.launch { ble.results.collect { onRawResult(it) } }
        // Latch sensor readiness whenever a verify test passes (wizard or retest).
        viewModelScope.launch {
            ble.status.collect { st ->
                when (st?.state) {
                    Proto.ST_VERIFY1_OK -> if (!sensor1Ready) setSensorReady(1, true)
                    Proto.ST_VERIFY2_OK -> if (!sensor2Ready) setSensorReady(2, true)
                }
            }
        }
        viewModelScope.launch {
            var prev = ConnState.DISCONNECTED
            ble.connState.collect { cs ->
                when (cs) {
                    ConnState.CONNECTED -> if (screen == Screen.CONNECT) {
                        screen = if (setupDone) Screen.DASHBOARD else Screen.BASELINE
                    }
                    ConnState.DISCONNECTED ->
                        // Leave the dashboard only when a real device session
                        // ended. A stopped scan (SCANNING -> DISCONNECTED, e.g.
                        // when entering manual mode) must not kick us back.
                        if (prev == ConnState.CONNECTED || prev == ConnState.RECONNECTING ||
                            prev == ConnState.CONNECTING
                        ) {
                            screen = Screen.CONNECT
                            retestSensor = null
                        }
                    else -> Unit
                }
                prev = cs
            }
        }
    }

    // -------------------------------------------------------------- results

    /** Shots just received and not yet reviewed — drives the results screen
     *  that appears after (re)connecting, before the after-photos prompt. */
    val newShots = mutableStateListOf<TestResult>()

    fun dismissShotReview() {
        newShots.clear()
        promptPhotos("after")
    }

    private fun onRawResult(r: RawResult) {
        // FETCH after a reconnect can re-deliver a result we already stored.
        val duplicate = results.any { it.deviceResultId == r.id && it.splitNs == r.splitNs }
        if (!duplicate) {
            val rec = TestResult(
                uid = UUID.randomUUID().toString(),
                deviceResultId = r.id,
                splitNs = r.splitNs,
                distanceM = distanceM,
                label = pendingLabel.trim(),
                epochMillis = if (r.epochSec > 0) r.epochSec * 1000L else null,
                tool = pendingTool.trim(),
                target = pendingTarget.trim(),
                targetDistValue = pendingTargetDistVal.replace(',', '.').toDoubleOrNull(),
                targetDistUnit = pendingTargetDistUnit,
            )
            results.add(0, rec)
            persist()
            prefs.edit()
                .putString("pendTool", pendingTool)
                .putString("pendTarget", pendingTarget)
                .putString("pendTdVal", pendingTargetDistVal)
                .putString("pendTdUnit", pendingTargetDistUnit)
                .apply()
            session.logShot(shotJson(rec))
            newShots.add(rec)   // review dialog first; photos prompt on dismiss
            // A real shot destroys both break-screens: force re-verify (and the
            // sensor-attach flow re-measures the fresh screen's load).
            setSensorReady(1, false)
            setSensorReady(2, false)
        }
        // Tell the device it can forget this result now that it's stored on the phone.
        ble.sendCommand(Proto.CMD_ACK, r.id)
    }

    fun updateResult(
        uid: String,
        label: String,
        tool: String,
        target: String,
        targetDistValue: Double?,
        targetDistUnit: String,
        outcome: String,
        epochMillis: Long?,
    ) {
        val idx = results.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        val r = results[idx]
        results[idx] = r.copy(
            label = label,
            tool = tool,
            target = target,
            targetDistValue = targetDistValue,
            targetDistUnit = targetDistUnit,
            outcome = outcome,
            epochMillis = epochMillis ?: r.epochMillis,
        )
        persist()
    }

    /** Log a shot with no chronograph connected — velocity typed in (or blank). */
    fun addManualEntry(
        label: String,
        tool: String,
        target: String,
        targetDistValue: Double?,
        targetDistUnit: String,
        outcome: String,
        velocity: Double?,
        velocityIsFps: Boolean,
        epochMillis: Long?,
    ) {
        val mps = velocity?.let { if (velocityIsFps) it / 3.28084 else it }
        val rec = TestResult(
            uid = UUID.randomUUID().toString(),
            deviceResultId = -1,
            splitNs = 0,
            distanceM = 0.0,
            label = label.trim(),
            epochMillis = epochMillis,
            tool = tool.trim(),
            target = target.trim(),
            targetDistValue = targetDistValue,
            targetDistUnit = targetDistUnit,
            outcome = outcome.trim(),
            manualVelocityMps = mps,
        )
        results.add(0, rec)
        persist()
        session.logShot(shotJson(rec))
        promptPhotos("after")
    }

    /** The per-shot log file written into the shot's folder. */
    private fun shotJson(r: TestResult): JSONObject = JSONObject()
        .put("uid", r.uid)
        .put("source", if (r.isManual) "manual" else "device")
        .put("label", r.label)
        .put("tool", r.tool)
        .put("target", r.target)
        .put("targetDistUnit", r.targetDistUnit)
        .put("outcome", r.outcome)
        .put("splitNs", r.splitNs)
        .put("distanceM", r.distanceM)
        .put("velocityMps", r.metersPerSecond)
        .put("velocityFps", r.feetPerSecond)
        .put("ciPercent", ciPercentFor(r))
        .put("epochMillis", r.epochMillis ?: -1L)
        .apply { r.targetDistValue?.let { put("targetDistValue", it) } }

    fun deleteResult(uid: String) {
        results.removeAll { it.uid == uid }
        persist()
    }

    private fun persist() = store.save(results.toList())

    // --------------------------------------------------- calibration engine

    private fun onCalReading(r: CalReading) {
        calTimeoutJob?.cancel()
        val slot = pendingCal
        if (slot != null && slot.first == r.channel && r.status != 2) {
            val key = (if (slot.second == CalPhase.BARE) "b" else "l") + r.channel
            val e = CalEntry(r.medianNs, r.stddevNs, r.samples, r.status, System.currentTimeMillis())
            calData[key] = e
            prefs.edit()
                .putString("cal_$key", "${e.medianNs},${e.stddevNs},${e.samples},${e.status},${e.at}")
                .apply()
            appendCalHistory(key, r)
        }
        pendingCal = null
        nextCal()
    }

    private fun enqueueCal(items: List<Pair<Int, CalPhase>>) {
        calQueue.addAll(items)
        if (!calRunning) nextCal()
    }

    private fun nextCal() {
        val next = calQueue.removeFirstOrNull()
        if (next == null) {
            calRunning = false
            pendingCal = null
            return
        }
        calRunning = true
        pendingCal = next
        ble.sendCommand(Proto.CMD_CALIBRATE, next.first)
        calTimeoutJob?.cancel()
        calTimeoutJob = viewModelScope.launch {
            delay(15_000)   // device never answered (e.g. old firmware) — move on
            pendingCal = null
            nextCal()
        }
    }

    fun startBaselineCal() = enqueueCal(listOf(1 to CalPhase.BARE, 2 to CalPhase.BARE))
    fun startLoadedCal(channel: Int) = enqueueCal(listOf(channel to CalPhase.LOADED))
    fun recalibrateChannels() = enqueueCal(listOf(1 to CalPhase.LOADED, 2 to CalPhase.LOADED))

    /** Raw engineering log, one JSON line per sweep, for later analysis. */
    private fun appendCalHistory(key: String, r: CalReading) {
        runCatching {
            val line = JSONObject()
                .put("key", key)
                .put("at", System.currentTimeMillis())
                .put("medianNs", r.medianNs)
                .put("meanNs", r.meanNs)
                .put("stddevNs", r.stddevNs)
                .put("minNs", r.minNs)
                .put("samples", r.samples)
                .put("status", r.status)
            File(getApplication<Application>().filesDir, "cal_history.jsonl")
                .appendText(line.toString() + "\n")
        }
    }

    /** Loaded-minus-bare for one channel: the cable+sensor contribution in ns. */
    fun channelLoadNs(channel: Int): Long? {
        val b = calData["b$channel"] ?: return null
        val l = calData["l$channel"] ?: return null
        return l.medianNs - b.medianNs
    }

    /** Channel-to-channel difference of the load terms — the number that matters. */
    fun channelMismatchNs(): Long? {
        val a = channelLoadNs(1) ?: return null
        val b = channelLoadNs(2) ?: return null
        return abs(a - b)
    }

    /**
     * ~95% confidence interval for one result, as a percent of the velocity.
     * Combines what the hardware reports about itself (timer tick, crystal
     * ppm, per-edge front-end jitter) with this rig's measured channel
     * mismatch (scaled from the 10k calibration stimulus down to the ~1k
     * live source impedance) and a 0.5 mm assumption on the user's
     * gate-spacing measurement. Newer hardware that reports tighter numbers
     * automatically tightens this — no app change needed.
     */
    fun ciPercentFor(r: TestResult): Double {
        val hw = ble.hwInfo.value ?: HwInfo.DEFAULT
        val t = r.splitNs.toDouble()
        if (t <= 0 || r.distanceM <= 0) return 0.0
        val tickNs = hw.tickPs / 1000.0
        val jitterNs = hw.edgeJitterNs * sqrt(2.0)             // two independent edges
        val clockNs = t * hw.clockPpm / 1_000_000.0
        val residualNs = (channelMismatchNs() ?: 200L) / 10.0  // cal->live impedance scale
        val sigmaT = sqrt(jitterNs * jitterNs + tickNs * tickNs + clockNs * clockNs) + residualNs
        val sigmaD = 0.0005                                    // 0.5 mm spacing uncertainty
        val rel = sqrt((sigmaT / t).pow(2.0) + (sigmaD / r.distanceM).pow(2.0))
        return 2 * rel * 100
    }

    // ---------------------------------------------------------- setup flow

    fun continueToSensor1() {
        screen = Screen.SENSOR1
        ble.sendCommand(Proto.CMD_VERIFY1)
    }

    fun continueToSensor2() {
        screen = Screen.SENSOR2
        ble.sendCommand(Proto.CMD_VERIFY2)
    }

    private var inWizard = false

    fun continueToDistance() {
        ble.sendCommand(Proto.CMD_CANCEL)
        inWizard = true
        screen = Screen.DISTANCE
    }

    fun saveDistance(value: Double, unit: DistanceUnit) {
        distanceValue = value
        distanceUnit = unit
        ble.simDistanceM = distanceM
        prefs.edit()
            .putFloat("distanceValue", value.toFloat())
            .putString("unit", unit.name)
            .putBoolean("setupDone", true)
            .apply()
        screen = Screen.DASHBOARD
        if (inWizard) {
            inWizard = false
            promptPhotos("setup")   // capture the rig as it stands for this test
        }
    }

    /** Distance expressed in the user's chosen unit, for display/editing. */
    fun distanceInUnit(): Double = distanceValue

    // ------------------------------------------------------------ dashboard

    /**
     * Opens the sensor-attach dialog. Deliberately does NOT start listening on
     * the input yet: the wire is first fitted (attach), then verified by a
     * capacitance measurement, and only then armed for the tap test — so
     * movement during placement can't trigger anything.
     */
    fun startRetest(sensor: Int) {
        retestSensor = sensor
    }

    /** Step 3 of the attach flow: now it's safe to listen for the test tap. */
    fun beginTapTest(sensor: Int) =
        ble.sendCommand(if (sensor == 1) Proto.CMD_VERIFY1 else Proto.CMD_VERIFY2)

    fun finishRetest(verified: Boolean) {
        retestSensor = null
        ble.sendCommand(Proto.CMD_CANCEL)
    }

    fun arm() = ble.sendCommand(Proto.CMD_ARM)
    fun disarm() = ble.sendCommand(Proto.CMD_DISARM)
    fun syncTime() = ble.syncTime()

    fun changeDistance() {
        inWizard = false
        screen = Screen.DISTANCE
    }

    /** Manual logging without any chronograph — straight to the dashboard. */
    fun enterManualMode() {
        screen = Screen.DASHBOARD
    }

    /** TopBar action: disconnect if connected, otherwise leave the dashboard. */
    fun disconnectOrExit() {
        if (ble.connState.value == ConnState.DISCONNECTED) {
            screen = Screen.CONNECT
        } else {
            ble.disconnect()
        }
    }

    /** Re-run the whole wizard, including a fresh bare-port baseline. */
    fun redoSetup() {
        screen = Screen.BASELINE
    }

    fun disconnect() = ble.disconnect()

    fun connectSimulated() = ble.connectSimulated()
    fun simulateSignalLoss() = ble.simulateSignalLoss()

    override fun onCleared() {
        ble.disconnect()
    }
}
