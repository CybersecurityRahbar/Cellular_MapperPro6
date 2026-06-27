// ================================================================
// FILE: MainActivity.kt - CELLULAR MAPPER PRO (Pure Kotlin Android)
// FINAL BUILD-FIXED VERSION (جميع الأخطاء مُصححة)
// ================================================================

package com.example.cellularmapper

// الأساسيات
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
// المتقدمة (لاحقاً في الملف لكن الاستيرادات مطلوبة من البداية)
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

// ================================================================
// DATA CLASSES
// ================================================================
data class CellularCellInfo(
    val mcc: Int?,
    val mnc: Int?,
    val lac: Int?,
    val cid: Long?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Int?,
    val rsrp: Int,
    val rsrq: Int,
    val rssnr: Int,
    val cqi: Int,
    val ta: Int,
    val dbm: Int,
    val radio: String,
    val timestamp: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val speed: Float? = null,
    val heading: Float? = null,
    val accuracy: Float? = null
) {
    val enodeb: Long? get() = if (radio == "LTE" && cid != null) cid?.shr(8) else null
    val sector: Int? get() = if (radio == "LTE" && cid != null) (cid?.and(0xFF))?.toInt() else null
    val gnodb: Long? get() = if (radio == "5G" && cid != null) cid?.shr(10) else null
    val nrSector: Int? get() = if (radio == "5G" && cid != null) (cid?.and(0x3FF))?.toInt() else null
    val operatorName: String? get() = OperatorMapper.getOperator(mcc, mnc)
}

data class Tower(val lat: Double, val lon: Double, val cells: List<CellularCellInfo>)
data class LogEntry(val time: Long, val message: String, val level: Int)

// ================================================================
// OPERATOR MAPPER
// ================================================================
object OperatorMapper {
    private val operators = mapOf(
        "42101" to "Yemen Mobile (GSM)",
        "42102" to "SabaFon",
        "42103" to "Yemen Mobile (CDMA)",
        "42104" to "Yemen Mobile (LTE)",
        "42105" to "MTN Yemen",
        "42106" to "Yemen Mobile (5G)",
        "310410" to "AT&T",
        "310260" to "T-Mobile",
        "311480" to "Verizon",
        "20801" to "Orange FR",
        "20810" to "SFR",
        "25001" to "MTS RU",
        "25002" to "MegaFon",
        "25020" to "Tele2 RU",
        "50212" to "Maxis MY",
        "50216" to "Digi MY",
        "51001" to "Telkomsel ID",
        "51010" to "XL Axiata",
        "51501" to "Globe PH",
        "51502" to "Smart PH",
        "46000" to "China Mobile",
        "46001" to "China Unicom",
        "46003" to "China Telecom",
        "42402" to "Etisalat",
        "42403" to "Du",
        "42001" to "STC",
        "42003" to "Zain",
        "42004" to "Mobily",
        "60201" to "Orange EG",
        "60202" to "Vodafone EG",
        "60203" to "Etisalat EG",
        "28601" to "Turk Telekom",
        "28602" to "Turkcell",
        "28603" to "Vodafone TR",
        "25501" to "Vodafone UA",
        "25502" to "Kyivstar",
        "25503" to "lifecell",
        "23410" to "O2 UK",
        "23415" to "Vodafone UK",
        "23420" to "Three UK",
        "23430" to "EE"
    )
    fun getOperator(mcc: Int?, mnc: Int?): String? {
        if (mcc == null || mnc == null) return null
        return operators["${mcc.toString().padStart(3, '0')}${mnc.toString().padStart(2, '0')}"]
    }
}

// ================================================================
// DATABASE HELPER (SQLite legacy)
// ================================================================
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "cellular_mapper.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cells (id INTEGER PRIMARY KEY AUTOINCREMENT, mcc INTEGER, mnc INTEGER, lac INTEGER, cid INTEGER, tac INTEGER, pci INTEGER, earfcn INTEGER, radio TEXT, lat REAL, lon REAL, rsrp INTEGER, rsrq INTEGER, rssnr INTEGER, cqi INTEGER, ta INTEGER, dbm INTEGER, enodeb INTEGER, sector INTEGER, gnodb INTEGER, nr_sector INTEGER, operator_name TEXT, first_seen INTEGER, last_seen INTEGER, seen_count INTEGER DEFAULT 1, avg_rsrp REAL, avg_rsrq REAL, avg_signal REAL, speed REAL, heading REAL, timestamp INTEGER, UNIQUE(mcc, mnc, lac, cid))")
        db.execSQL("CREATE INDEX idx_cell ON cells(mcc, mnc, lac, cid)")
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, project_name TEXT, start_time INTEGER, end_time INTEGER, total_events INTEGER, unique_cells INTEGER, distance_km REAL, max_speed REAL, avg_speed REAL, handover_count INTEGER, pingpong_count INTEGER)")
        db.execSQL("CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, lat REAL, lon REAL, speed REAL, heading REAL, accuracy REAL, timestamp INTEGER)")
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, level INTEGER, message TEXT, timestamp INTEGER)")
        db.execSQL("CREATE TABLE exports (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, format TEXT, file_path TEXT, timestamp INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
}

// ================================================================
// UTILITY FUNCTIONS
// ================================================================
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)))
}

fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2))
    val x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) -
            Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon)
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
}

// ================================================================
// MAIN ACTIVITY
// ================================================================
class MainActivity : AppCompatActivity(), LocationListener {
    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvMcc: TextView
    private lateinit var tvMnc: TextView
    private lateinit var tvLac: TextView
    private lateinit var tvCid: TextView
    private lateinit var tvDbm: TextView
    private lateinit var tvRadio: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvAvgSignal: TextView
    private lateinit var tvBestSignal: TextView
    private lateinit var tvWorstSignal: TextView
    private lateinit var tvEvents: TextView
    private lateinit var tvUnique: TextView
    private lateinit var tvChanges: TextView
    private lateinit var tvHandovers: TextView
    private lateinit var tvPingPong: TextView
    private lateinit var mapView: MapView
    private lateinit var signalGraph: SignalGraphView
    private lateinit var logRecycler: RecyclerView
    private lateinit var historyRecycler: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var btnFetch: Button
    private lateinit var btnClear: Button
    private lateinit var btnExportCSV: Button
    private lateinit var btnExportKML: Button
    private lateinit var btnImport: Button
    private lateinit var btnProjects: Button

    // Services
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var dbHelper: DatabaseHelper
    private var db: SQLiteDatabase? = null

    // State
    private var isScanning = false
    private var totalEvents = 0
    private val uniqueCells = mutableSetOf<String>()
    private var cellChanges = 0
    private var movementEvents = 0
    private var avgSignal = 0
    private var bestSignal = -999
    private var worstSignal = 0
    private var currentSpeed = 0.0
    private var avgSpeed = 0.0
    private var maxSpeed = 0.0
    private var currentHeading = 0.0
    private var totalDistance = 0.0
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0
    private var handoverCount = 0
    private var pingPongCount = 0

    private val eventHistory = mutableListOf<CellularCellInfo>()
    private val signalHistory = mutableListOf<SignalSample>()
    private val trackPoints = mutableListOf<GeoPoint>()
    private val mapMarkers = mutableListOf<Marker>()
    private val logEntries = mutableListOf<LogEntry>()
    private val logAdapter = LogAdapter(logEntries)

    private var currentSessionId: Long? = null
    private var lastCell: CellularCellInfo? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    data class SignalSample(val time: Long, val rsrp: Int, val rsrq: Int, val rssnr: Int, val dbm: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvMcc = findViewById(R.id.tvMcc)
        tvMnc = findViewById(R.id.tvMnc)
        tvLac = findViewById(R.id.tvLac)
        tvCid = findViewById(R.id.tvCid)
        tvDbm = findViewById(R.id.tvDbm)
        tvRadio = findViewById(R.id.tvRadio)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvHeading = findViewById(R.id.tvHeading)
        tvDistance = findViewById(R.id.tvDistance)
        tvAvgSignal = findViewById(R.id.tvAvgSignal)
        tvBestSignal = findViewById(R.id.tvBestSignal)
        tvWorstSignal = findViewById(R.id.tvWorstSignal)
        tvEvents = findViewById(R.id.tvEvents)
        tvUnique = findViewById(R.id.tvUnique)
        tvChanges = findViewById(R.id.tvChanges)
        tvHandovers = findViewById(R.id.tvHandovers)
        tvPingPong = findViewById(R.id.tvPingPong)
        mapView = findViewById(R.id.mapView)
        signalGraph = findViewById(R.id.signalGraph)
        logRecycler = findViewById(R.id.logRecycler)
        historyRecycler = findViewById(R.id.historyRecycler)
        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)
        btnFetch = findViewById(R.id.btnFetch)
        btnClear = findViewById(R.id.btnClear)
        btnExportCSV = findViewById(R.id.btnExportCSV)
        btnExportKML = findViewById(R.id.btnExportKML)
        btnImport = findViewById(R.id.btnImport)
        btnProjects = findViewById(R.id.btnProjects)

        Configuration.getInstance().load(this, androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setBuiltInZoomControls(true)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(11.0)
        mapView.controller.setCenter(GeoPoint(15.3694, 44.1910))

        logRecycler.layoutManager = LinearLayoutManager(this)
        logRecycler.adapter = logAdapter
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = HistoryAdapter(eventHistory)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)
        db = dbHelper.writableDatabase

        checkPermissions()
        startSession()

        btnScan.setOnClickListener { startScanning() }
        btnStop.setOnClickListener { stopScanning() }
        btnFetch.setOnClickListener { fetchOnce() }
        btnClear.setOnClickListener { clearAll() }
        btnExportCSV.setOnClickListener { exportCSV() }
        btnExportKML.setOnClickListener { exportKML() }
        btnImport.setOnClickListener { importOpenCellID() }
        btnProjects.setOnClickListener { showProjectsDialog() }

        startScanning()
        requestLocationUpdates()
        addLog("Application initialized", 3)
    }

    override fun onDestroy() {
        stopScanning()
        locationManager.removeUpdates(this)
        db?.close()
        super.onDestroy()
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_PHONE_STATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScanning()
            requestLocationUpdates()
        }
    }

    private fun startSession() {
        val values = ContentValues().apply {
            put("project_name", "Default")
            put("start_time", System.currentTimeMillis())
            put("end_time", 0)
            put("total_events", 0)
            put("unique_cells", 0)
            put("distance_km", 0.0)
            put("max_speed", 0.0)
            put("avg_speed", 0.0)
            put("handover_count", 0)
            put("pingpong_count", 0)
        }
        currentSessionId = db?.insert("sessions", null, values)
        addLog("Session started: $currentSessionId", 3)
    }

    private fun startScanning() {
        if (isScanning) return
        isScanning = true
        addLog("Scanning started", 3)
        tvStatus.text = "Status: Scanning..."
        btnScan.isEnabled = false
        btnStop.isEnabled = true

        scanRunnable = object : Runnable {
            override fun run() {
                if (!isScanning) return
                fetchCellData()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(scanRunnable!!)
    }

    private fun stopScanning() {
        isScanning = false
        btnScan.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "Status: Stopped"
        addLog("Scanning stopped", 2)
        scanRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun fetchCellData() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            addLog("Location permission denied", 2)
            return
        }
        try {
            val cellInfoList = telephonyManager.allCellInfo
            if (cellInfoList.isNullOrEmpty()) {
                addLog("No cells found", 2)
                return
            }
            val active = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull() ?: return

            val cell = extractCellInfo(active)
            val neighbors = cellInfoList.filter { !it.isRegistered && it != active }.map { extractCellInfo(it) }

            val enriched = cell.copy(
                lat = lastLocation?.latitude,
                lon = lastLocation?.longitude,
                speed = lastLocation?.speed,
                heading = lastLocation?.bearing,
                accuracy = lastLocation?.accuracy
            )

            totalEvents++
            uniqueCells.add("${enriched.mcc}-${enriched.mnc}-${enriched.lac}-${enriched.cid}")

            if (lastCell != null) {
                val oldKey = "${lastCell!!.mcc}-${lastCell!!.mnc}-${lastCell!!.lac}-${lastCell!!.cid}"
                val newKey = "${enriched.mcc}-${enriched.mnc}-${enriched.lac}-${enriched.cid}"
                if (oldKey != newKey) {
                    cellChanges++
                    handoverCount++
                    if (eventHistory.size >= 2) {
                        val prev = eventHistory[eventHistory.size - 2]
                        if (prev.mcc == enriched.mcc && prev.mnc == enriched.mnc &&
                            prev.lac == enriched.lac && prev.cid == enriched.cid) pingPongCount++
                    }
                }
            }
            lastCell = enriched

            val lat = enriched.lat; val lon = enriched.lon
            if (lat != null && lon != null && lastLocation != null) {
                val dist = haversineKm(lastLocation!!.latitude, lastLocation!!.longitude, lat, lon)
                totalDistance += dist
                enriched.speed?.let { sp ->
                    val kmh = sp * 3.6
                    currentSpeed = kmh
                    if (kmh > maxSpeed) maxSpeed = kmh
                    avgSpeed = (avgSpeed * 0.9 + kmh * 0.1)
                }
            }
            lastLocation = Location("gps").apply { latitude = lat ?: 0.0; longitude = lon ?: 0.0 }

            if (enriched.dbm != -999) {
                val dbm = enriched.dbm
                if (dbm > bestSignal) bestSignal = dbm
                if (dbm < worstSignal || worstSignal == 0) worstSignal = dbm
                avgSignal = ((avgSignal * (totalEvents - 1) + dbm) / totalEvents)
            }

            signalHistory.add(SignalSample(System.currentTimeMillis(), enriched.rsrp, enriched.rsrq, enriched.rssnr, enriched.dbm))
            if (signalHistory.size > 200) signalHistory.removeAt(0)

            saveCell(enriched)
            for (n in neighbors) { saveNeighbor(enriched, n) }

            runOnUiThread {
                updateTelemetry(enriched, neighbors)
                updateStats()
                updateMap(enriched)
                updateSignalGraph()
                eventHistory.add(0, enriched)
                if (eventHistory.size > 100) eventHistory.removeAt(eventHistory.size - 1)
                historyRecycler.adapter?.notifyDataSetChanged()
                tvStatus.text = "Status: Connected (${enriched.radio})"
            }

            val alerts = detectSuspicious(enriched, neighbors)
            if (alerts.isNotEmpty()) {
                for (alert in alerts) addLog("⚠️ $alert", 2)
            }

            enriched.operatorName?.let { addLog("Operator: $it", 3) }

        } catch (e: Exception) {
            addLog("Error: ${e.message}", 2)
        }
    }

    private fun fetchOnce() {
        addLog("Manual fetch triggered", 3)
        fetchCellData()
    }

    // ======== الدالة المُصححة بشكل كامل ========
    private fun extractCellInfo(info: android.telephony.CellInfo): CellularCellInfo {
        val signal = info.cellSignalStrength
        var mcc: Int? = null; var mnc: Int? = null; var lac: Int? = null; var cid: Long? = null
        var tac: Int? = null; var pci: Int? = null; var earfcn: Int? = null; var radio = "UNKNOWN"

        when (info) {
            is android.telephony.CellInfoLte -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityLte
                if (id != null) {
                    mcc = id.mcc; mnc = id.mnc; lac = id.tac; cid = id.ci?.toLong()
                    tac = id.tac; pci = id.pci; earfcn = id.earfcn
                }
                radio = "LTE"
            }
            is android.telephony.CellInfoWcdma -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityWcdma
                if (id != null) {
                    mcc = id.mcc; mnc = id.mnc; lac = id.lac; cid = id.cid?.toLong()
                }
                radio = "WCDMA"
            }
            is android.telephony.CellInfoGsm -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityGsm
                if (id != null) {
                    mcc = id.mcc; mnc = id.mnc; lac = id.lac; cid = id.cid?.toLong()
                }
                radio = "GSM"
            }
            is android.telephony.CellInfoNr -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityNr
                if (id != null) {
                    mcc = id.mcc; mnc = id.mnc; lac = id.tac; cid = id.nci
                    tac = id.tac; pci = id.pci; earfcn = id.nrarfcn
                }
                radio = "5G"
            }
            is android.telephony.CellInfoCdma -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityCdma
                if (id != null) {
                    mcc = id.mcc; mnc = id.mnc; lac = -1
                    cid = id.basestationId?.toLong() ?: -1
                }
                radio = "CDMA"
            }
        }

        val dbm = signal?.dbm ?: -999
        var rsrp = -999; var rsrq = -999; var rssnr = -999; var cqi = -1; var ta = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signal != null) {
            rsrp = signal.rsrp; rsrq = signal.rsrq; rssnr = signal.rssnr
            cqi = signal.cqi; ta = signal.timingAdvance
        }

        if (mcc == 2147483647) mcc = null
        if (mnc == 2147483647) mnc = null
        if (lac == 2147483647) lac = null
        if (cid == 2147483647L) cid = null

        return CellularCellInfo(mcc, mnc, lac, cid, tac, pci, earfcn, rsrp, rsrq, rssnr, cqi, ta, dbm, radio, System.currentTimeMillis())
    }

    private fun saveCell(cell: CellularCellInfo) {
        val values = ContentValues().apply {
            put("mcc", cell.mcc); put("mnc", cell.mnc); put("lac", cell.lac); put("cid", cell.cid)
            put("tac", cell.tac); put("pci", cell.pci); put("earfcn", cell.earfcn); put("radio", cell.radio)
            put("lat", cell.lat); put("lon", cell.lon); put("rsrp", cell.rsrp); put("rsrq", cell.rsrq)
            put("rssnr", cell.rssnr); put("cqi", cell.cqi); put("ta", cell.ta); put("dbm", cell.dbm)
            put("enodeb", cell.enodeb); put("sector", cell.sector); put("gnodb", cell.gnodb)
            put("nr_sector", cell.nrSector); put("operator_name", cell.operatorName)
            put("timestamp", cell.timestamp); put("last_seen", cell.timestamp)
        }
        db?.insertWithOnConflict("cells", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun saveNeighbor(serving: CellularCellInfo, neighbor: CellularCellInfo) {}

    private fun detectSuspicious(cell: CellularCellInfo, neighbors: List<CellularCellInfo>): List<String> {
        val alerts = mutableListOf<String>()
        if (cell.mcc != null && cell.mnc != null && OperatorMapper.getOperator(cell.mcc, cell.mnc) == null)
            alerts.add("Unknown operator: MCC=${cell.mcc} MNC=${cell.mnc}")
        return alerts
    }

    private fun updateTelemetry(cell: CellularCellInfo, neighbors: List<CellularCellInfo>) {
        tvMcc.text = "MCC: ${cell.mcc ?: "?"}"; tvMnc.text = "MNC: ${cell.mnc ?: "?"}"
        tvLac.text = "LAC: ${cell.lac ?: "?"}"; tvCid.text = "CID: ${cell.cid ?: "?"}"
        tvDbm.text = "dBm: ${if (cell.dbm != -999) cell.dbm else "?"}"
        tvRadio.text = "Radio: ${cell.radio}"; tvSpeed.text = "Speed: ${"%.1f".format(currentSpeed)} km/h"
        tvHeading.text = "Heading: ${"%.0f".format(currentHeading)}°"
        tvDistance.text = "Distance: ${"%.2f".format(totalDistance)} km"
    }

    private fun updateStats() {
        tvAvgSignal.text = "Avg: $avgSignal dBm"; tvBestSignal.text = "Best: $bestSignal dBm"
        tvWorstSignal.text = "Worst: $worstSignal dBm"; tvEvents.text = "Events: $totalEvents"
        tvUnique.text = "Unique: ${uniqueCells.size}"; tvChanges.text = "Changes: $cellChanges"
        tvHandovers.text = "Handovers: $handoverCount"; tvPingPong.text = "PingPong: $pingPongCount"
    }

    private fun updateMap(cell: CellularCellInfo) {
        if (cell.lat != null && cell.lon != null) {
            val point = GeoPoint(cell.lat!!, cell.lon!!)
            trackPoints.add(point)
            if (trackPoints.size > 500) trackPoints.removeAt(0)
            val marker = Marker(mapView).apply { position = point; title = "${cell.radio} ${cell.cid}" }
            mapView.overlays.add(marker)
            mapMarkers.add(marker)
            if (mapMarkers.size > 500) mapView.overlays.remove(mapMarkers.removeAt(0))
            if (trackPoints.size > 1) {
                val polyline = Polyline()
                polyline.setPoints(ArrayList(trackPoints))
                polyline.color = Color.parseColor("#00FFB2")
                polyline.width = 4f
                mapView.overlays.add(polyline)
            }
            mapView.controller.animateTo(point)
            mapView.invalidate()
        }
    }

    private fun updateSignalGraph() { signalGraph.setData(signalHistory); signalGraph.invalidate() }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 5f, this)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location; lastLocationTime = System.currentTimeMillis()
        if (location.hasBearing()) currentHeading = location.bearing.toDouble()
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    private fun exportCSV() {
        try {
            val cursor = db?.query("cells", null, null, null, null, null, "timestamp DESC")
            val file = File(getExternalFilesDir(null) ?: cacheDir, "export_${System.currentTimeMillis()}.csv")
            file.writeText("MCC,MNC,LAC,CID,Radio,Lat,Lon,dBm,Timestamp\n")
            cursor?.use {
                while (it.moveToNext()) {
                    val mcc = it.getInt(it.getColumnIndexOrThrow("mcc"))
                    val mnc = it.getInt(it.getColumnIndexOrThrow("mnc"))
                    val lac = it.getInt(it.getColumnIndexOrThrow("lac"))
                    val cid = it.getLong(it.getColumnIndexOrThrow("cid"))
                    val radio = it.getString(it.getColumnIndexOrThrow("radio"))
                    val lat = it.getDouble(it.getColumnIndexOrThrow("lat"))
                    val lon = it.getDouble(it.getColumnIndexOrThrow("lon"))
                    val dbm = it.getInt(it.getColumnIndexOrThrow("dbm"))
                    val ts = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                    file.appendText("$mcc,$mnc,$lac,$cid,$radio,$lat,$lon,$dbm,$ts\n")
                }
            }
            addLog("CSV exported: ${file.absolutePath}", 3)
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { addLog("CSV export failed: ${e.message}", 2) }
    }

    private fun exportKML() {
        try {
            val cursor = db?.query("cells", arrayOf("lat", "lon", "radio", "cid"), "lat IS NOT NULL AND lon IS NOT NULL", null, null, null, null)
            val file = File(getExternalFilesDir(null) ?: cacheDir, "export_${System.currentTimeMillis()}.kml")
            val sb = StringBuilder()
            sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
            cursor?.use {
                while (it.moveToNext()) {
                    val lat = it.getDouble(0); val lon = it.getDouble(1); val radio = it.getString(2); val cid = it.getLong(3)
                    sb.appendLine("<Placemark><name>$radio $cid</name><Point><coordinates>$lon,$lat,0</coordinates></Point></Placemark>")
                }
            }
            sb.appendLine("</Document></kml>")
            file.writeText(sb.toString())
            addLog("KML exported: ${file.absolutePath}", 3)
            Toast.makeText(this, "KML exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { addLog("KML export failed: ${e.message}", 2) }
    }

    private fun importOpenCellID() { addLog("Import feature: select OpenCellID CSV file", 2) }

    private fun showProjectsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Projects")
        val projects = db?.rawQuery("SELECT id, project_name, start_time FROM sessions ORDER BY start_time DESC", null)
        val names = mutableListOf<String>()
        val ids = mutableListOf<Long>()
        projects?.use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
                names.add("${it.getString(1)} (${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it.getLong(2)))})")
            }
        }
        builder.setItems(names.toTypedArray()) { _, which ->
            currentSessionId = ids[which]
            addLog("Switched to project: ${names[which]}", 3)
            clearAll()
        }
        builder.setPositiveButton("New") { _, _ ->
            val input = EditText(this)
            input.hint = "Project name"
            AlertDialog.Builder(this)
                .setTitle("New Project")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString()
                    if (name.isNotEmpty()) { startSession(); addLog("Project created: $name", 3) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun clearAll() {
        eventHistory.clear(); signalHistory.clear(); trackPoints.clear(); mapView.overlays.clear()
        uniqueCells.clear()
        totalEvents = 0; cellChanges = 0; movementEvents = 0; avgSignal = 0
        bestSignal = -999; worstSignal = 0; currentSpeed = 0.0; avgSpeed = 0.0; maxSpeed = 0.0
        currentHeading = 0.0; totalDistance = 0.0; handoverCount = 0; pingPongCount = 0
        lastCell = null; lastLocation = null
        updateStats(); updateSignalGraph(); historyRecycler.adapter?.notifyDataSetChanged()
        mapView.invalidate(); addLog("All data cleared", 3)
    }

    private fun addLog(message: String, level: Int) {
        logEntries.add(0, LogEntry(System.currentTimeMillis(), message, level))
        if (logEntries.size > 500) logEntries.removeAt(logEntries.size - 1)
        logAdapter.notifyDataSetChanged()
        val values = ContentValues().apply {
            put("session_id", currentSessionId); put("level", level); put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        db?.insert("logs", null, values)
    }

    inner class LogAdapter(private val logs: List<LogEntry>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply { textSize = 10f; setPadding(4, 2, 4, 2) }
            return ViewHolder(tv)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = logs[position]
            val color = when (entry.level) { 0 -> Color.WHITE; 1 -> Color.YELLOW; 2 -> Color.RED; 3 -> Color.GREEN; else -> Color.GRAY }
            holder.textView.text = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.time))}: ${entry.message}"
            holder.textView.setTextColor(color)
        }
        override fun getItemCount() = logs.size
    }

    inner class HistoryAdapter(private val history: List<CellularCellInfo>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply { textSize = 9f; setPadding(4, 2, 4, 2) }
            return ViewHolder(tv)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cell = history[position]
            holder.textView.text = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(cell.timestamp))} | ${cell.radio} | CID:${cell.cid} | ${cell.dbm}dBm"
        }
        override fun getItemCount() = history.size
    }

    inner class SignalGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
        private var data: List<SignalSample> = emptyList()
        private val paintRsrp = Paint().apply { color = Color.parseColor("#00FFB2"); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val paintRsrq = Paint().apply { color = Color.parseColor("#22D3EE"); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val paintDbm = Paint().apply { color = Color.parseColor("#FF3B6B"); strokeWidth = 2f; style = Paint.Style.STROKE }

        fun setData(data: List<SignalSample>) { this.data = data; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (data.size < 2) return
            val w = width.toFloat(); val h = height.toFloat(); val pad = 20f
            val gw = w - 2 * pad; val gh = h - 2 * pad

            var minVal = 0f; var maxVal = -200f
            data.forEach { d ->
                listOf(d.rsrp, d.rsrq, d.dbm).forEach { if (it > -999) { if (it < minVal) minVal = it.toFloat(); if (it > maxVal) maxVal = it.toFloat() } }
            }
            if (maxVal - minVal < 10) { minVal -= 10; maxVal += 10 }
            val range = maxVal - minVal

            fun toX(i: Int) = pad + i.toFloat() / (data.size - 1) * gw
            fun toY(v: Int) = pad + (1 - (v - minVal) / range) * gh

            for (i in 0 until data.size - 1) {
                val a = data[i]; val b = data[i + 1]
                if (a.rsrp > -999 && b.rsrp > -999) canvas.drawLine(toX(i), toY(a.rsrp), toX(i+1), toY(b.rsrp), paintRsrp)
                if (a.rsrq > -999 && b.rsrq > -999) canvas.drawLine(toX(i), toY(a.rsrq), toX(i+1), toY(b.rsrq), paintRsrq)
                if (a.dbm > -999 && b.dbm > -999) canvas.drawLine(toX(i), toY(a.dbm), toX(i+1), toY(b.dbm), paintDbm)
            }

            val legend = listOf("RSRP" to "#00FFB2", "RSRQ" to "#22D3EE", "dBm" to "#FF3B6B")
            legend.forEachIndexed { idx, (label, color) ->
                val lx = pad + idx * 50f; val ly = h - 4f
                canvas.drawLine(lx, ly - 6, lx + 20, ly - 6, Paint().apply { this.color = Color.parseColor(color); strokeWidth = 2f })
                canvas.drawText(label, lx + 22, ly, Paint().apply { this.color = Color.parseColor(color); textSize = 8f })
            }
        }
    }
}
// ======== END OF PART 1 (FINAL) ========
// ======== END OF PART 1 (FINAL) ========

// ================================================================
//   CELLULAR MAPPER PRO — ADVANCED EXTENSION (Phases 1..21)
//   PART 2/3 — يضاف بعد نهاية class MainActivity مباشرة
//   جميع الاستيرادات الضرورية موجودة في الجزء الأول (Part 1)
// ================================================================

// ================================================================
// PHASE 1 — PROFESSIONAL DATABASE (Room)
// ================================================================
@Entity(
    tableName = "towers",
    indices = [
        Index(value = ["mcc", "mnc", "tac", "cid"], unique = true),
        Index(value = ["pci"]),
        Index(value = ["earfcn"]),
        Index(value = ["lat", "lon"])
    ]
)
data class TowerEntity(
    @PrimaryKey val towerId: String,
    val mcc: Int?, val mnc: Int?, val tac: Int?, val cid: Long?,
    val pci: Int?, val earfcn: Int?, val band: String?,
    val lat: Double?, val lon: Double?,
    val estimatedAccuracy: Double,
    val firstSeen: Long, val lastSeen: Long, val seenCount: Int,
    val avgRsrp: Double, val avgRsrq: Double, val avgRssnr: Double,
    val avgTimingAdvance: Double, val avgDistance: Double,
    val confidenceScore: Double,
    val sectorCount: Int
)

@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["towerId"]),
        Index(value = ["timestamp"]),
        Index(value = ["cid"]),
        Index(value = ["pci"]),
        Index(value = ["sessionId"])
    ]
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val towerId: String?,
    val lat: Double?, val lon: Double?,
    val heading: Float?, val speed: Float?,
    val altitude: Double?, val accuracy: Float?,
    val cid: Long?, val pci: Int?, val earfcn: Int?,
    val rsrp: Int, val rsrq: Int, val rssnr: Int,
    val timingAdvance: Int,
    val timestamp: Long,
    val qualityScore: Double = 1.0
)

@Entity(
    tableName = "sectors",
    indices = [Index(value = ["towerId"]), Index(value = ["pci"])]
)
data class SectorEntity(
    @PrimaryKey val sectorId: String,
    val towerId: String,
    val pci: Int?,
    val azimuth: Double,
    val beamWidth: Double,
    val estimatedRadius: Double,
    val averageSignal: Double,
    val samples: Int
)

@Entity(tableName = "survey_sessions")
data class SurveySessionEntity(
    @PrimaryKey val sessionId: String,
    val start: Long, val end: Long?,
    val distance: Double,
    val averageSpeed: Double,
    val collectedTowers: Int,
    val collectedSamples: Int
)

@Dao
interface TowerDao {
    @Query("SELECT * FROM towers")
    fun observeAll(): Flow<List<TowerEntity>>

    @Query("SELECT * FROM towers WHERE towerId = :id LIMIT 1")
    suspend fun findById(id: String): TowerEntity?

    @Query("SELECT * FROM towers WHERE mcc = :mcc AND mnc = :mnc AND tac = :tac AND cid = :cid LIMIT 1")
    suspend fun findByKey(mcc: Int, mnc: Int, tac: Int, cid: Long): TowerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TowerEntity)

    @Query("SELECT * FROM towers WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun inBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<TowerEntity>

    @Query("SELECT * FROM towers WHERE cid = :cid OR pci = :pci OR earfcn = :earfcn")
    suspend fun search(cid: Long?, pci: Int?, earfcn: Int?): List<TowerEntity>
}

@Dao
interface MeasurementDao {
    @Insert suspend fun insert(m: MeasurementEntity): Long
    @Insert suspend fun insertAll(ms: List<MeasurementEntity>)

    @Query("SELECT * FROM measurements WHERE towerId = :towerId ORDER BY timestamp ASC")
    suspend fun forTower(towerId: String): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE sessionId = :sid ORDER BY timestamp ASC")
    suspend fun forSession(sid: String): List<MeasurementEntity>

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<MeasurementEntity>

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int
}

@Dao
interface SectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SectorEntity)
    @Query("SELECT * FROM sectors WHERE towerId = :tid")
    suspend fun forTower(tid: String): List<SectorEntity>
    @Query("SELECT * FROM sectors")
    fun observeAll(): Flow<List<SectorEntity>>
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SurveySessionEntity)
    @Update suspend fun update(s: SurveySessionEntity)
    @Query("SELECT * FROM survey_sessions ORDER BY start DESC")
    suspend fun all(): List<SurveySessionEntity>
    @Query("SELECT * FROM survey_sessions WHERE sessionId = :id")
    suspend fun byId(id: String): SurveySessionEntity?
}

@Database(
    entities = [TowerEntity::class, MeasurementEntity::class, SectorEntity::class, SurveySessionEntity::class],
    version = 1, exportSchema = false
)
abstract class CellularDatabase : RoomDatabase() {
    abstract fun towers(): TowerDao
    abstract fun measurements(): MeasurementDao
    abstract fun sectors(): SectorDao
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var INSTANCE: CellularDatabase? = null
        fun get(ctx: Context): CellularDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                CellularDatabase::class.java,
                "cellular_pro.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

// ================================================================
// PHASE 2 — FINGERPRINTING ENGINE
// ================================================================
data class TowerFingerprint(
    val cid: Long?, val pci: Int?, val earfcn: Int?, val band: String?,
    val avgRsrp: Double, val avgRsrq: Double, val avgRssnr: Double,
    val gpsCentroidLat: Double?, val gpsCentroidLon: Double?,
    val taDistribution: Map<Int, Int>
) {
    fun similarity(other: TowerFingerprint): Double {
        var score = 0.0; var weight = 0.0
        if (cid != null && cid == other.cid) { score += 50; weight += 50.0 }
        if (pci != null && pci == other.pci) { score += 20; weight += 20.0 }
        if (earfcn != null && earfcn == other.earfcn) { score += 15; weight += 15.0 }
        weight += 15.0
        val rsrpDelta = abs(avgRsrp - other.avgRsrp)
        score += (15.0 * (1.0 - (rsrpDelta / 40.0)).coerceIn(0.0, 1.0))
        return if (weight == 0.0) 0.0 else (score / weight) * 100.0
    }
}

object FingerprintEngine {
    fun build(measurements: List<MeasurementEntity>): TowerFingerprint {
        val rsrp = measurements.map { it.rsrp.toDouble() }
        val rsrq = measurements.map { it.rsrq.toDouble() }
        val snr  = measurements.map { it.rssnr.toDouble() }
        val taDist = measurements.groupingBy { it.timingAdvance }.eachCount()
        val centroid = measurements.mapNotNull {
            if (it.lat != null && it.lon != null) it.lat to it.lon else null
        }
        val cLat = centroid.map { it.first }.averageOrNull()
        val cLon = centroid.map { it.second }.averageOrNull()
        val first = measurements.firstOrNull()
        return TowerFingerprint(
            cid = first?.cid, pci = first?.pci, earfcn = first?.earfcn,
            band = first?.earfcn?.let { BandAnalyzer.lteBand(it)?.name },
            avgRsrp = rsrp.averageOrZero(),
            avgRsrq = rsrq.averageOrZero(),
            avgRssnr = snr.averageOrZero(),
            gpsCentroidLat = cLat, gpsCentroidLon = cLon,
            taDistribution = taDist
        )
    }
    fun recognize(target: TowerFingerprint, library: List<TowerFingerprint>, threshold: Double = 75.0)
        : TowerFingerprint? = library.maxByOrNull { it.similarity(target) }
            ?.takeIf { it.similarity(target) >= threshold }
}

// ================================================================
// PHASE 3 — TOWER POSITION ESTIMATION
// ================================================================
data class EstimatedPosition(val lat: Double, val lon: Double, val confidence: Double, val radius: Double)

object PathLoss {
    fun distanceMeters(rsrp: Int, txPowerDbm: Double = 46.0,
                       pathLossExp: Double = 3.5, refDist: Double = 1.0): Double {
        val loss = txPowerDbm - rsrp
        return refDist * 10.0.pow((loss - 32.45) / (10.0 * pathLossExp))
    }
}

object TowerEstimator {
    fun estimate(measurements: List<MeasurementEntity>): EstimatedPosition? {
        val pts = measurements.filter { it.lat != null && it.lon != null }
        if (pts.size < 3) return weightedCentroid(pts)
        val cleaned = OutlierRemover.removeRsrpOutliers(pts)
        val wls = weightedLeastSquares(cleaned) ?: weightedCentroid(cleaned) ?: return null
        val kf = KalmanFilter2D().apply { reset(wls.lat, wls.lon) }
        cleaned.forEach { kf.update(it.lat!!, it.lon!!, weight(it)) }
        val filtered = kf.state()
        val conf = ConfidenceEngine.scoreEstimation(cleaned)
        val radius = estimateRadius(cleaned, filtered.first, filtered.second)
        return EstimatedPosition(filtered.first, filtered.second, conf, radius)
    }

    private fun weight(m: MeasurementEntity): Double {
        val signalW = ((m.rsrp + 140.0) / 100.0).coerceIn(0.05, 1.0)
        val accW = if (m.accuracy != null && m.accuracy > 0) (20.0 / m.accuracy).coerceIn(0.1, 1.0) else 0.5
        return signalW * accW * m.qualityScore
    }

    fun weightedCentroid(pts: List<MeasurementEntity>): EstimatedPosition? {
        if (pts.isEmpty()) return null
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        pts.forEach {
            val w = weight(it); sw += w
            sx += (it.lat ?: 0.0) * w
            sy += (it.lon ?: 0.0) * w
        }
        if (sw == 0.0) return null
        return EstimatedPosition(sx / sw, sy / sw, 40.0, 200.0)
    }

    private fun weightedLeastSquares(pts: List<MeasurementEntity>): EstimatedPosition? {
        val c = weightedCentroid(pts) ?: return null
        var lat = c.lat; var lon = c.lon
        repeat(8) {
            var num0 = 0.0; var num1 = 0.0; var den = 0.0
            pts.forEach {
                val w = weight(it)
                val dLat = (it.lat ?: lat) - lat
                val dLon = (it.lon ?: lon) - lon
                num0 += w * dLat; num1 += w * dLon; den += w
            }
            if (den == 0.0) return@repeat
            lat += num0 / den * 0.5
            lon += num1 / den * 0.5
        }
        return EstimatedPosition(lat, lon, 60.0, 150.0)
    }

    private fun estimateRadius(pts: List<MeasurementEntity>, lat: Double, lon: Double): Double {
        val d = pts.mapNotNull {
            if (it.lat != null && it.lon != null) Geo.haversine(lat, lon, it.lat, it.lon) else null
        }
        return d.maxOrNull() ?: 100.0
    }
}

class KalmanFilter2D(
    private var processNoise: Double = 1e-5,
    private var measurementNoise: Double = 1e-2
) {
    private var lat = 0.0; private var lon = 0.0
    private var pLat = 1.0; private var pLon = 1.0
    fun reset(lat: Double, lon: Double) { this.lat = lat; this.lon = lon; pLat = 1.0; pLon = 1.0 }
    fun update(zLat: Double, zLon: Double, weight: Double = 1.0) {
        pLat += processNoise; pLon += processNoise
        val rn = measurementNoise / weight.coerceAtLeast(0.05)
        val kLat = pLat / (pLat + rn); val kLon = pLon / (pLon + rn)
        lat += kLat * (zLat - lat); lon += kLon * (zLon - lon)
        pLat *= (1 - kLat);          pLon *= (1 - kLon)
    }
    fun state(): Pair<Double, Double> = lat to lon
}

object OutlierRemover {
    fun removeRsrpOutliers(pts: List<MeasurementEntity>, k: Double = 2.0): List<MeasurementEntity> {
        if (pts.size < 4) return pts
        val rsrp = pts.map { it.rsrp.toDouble() }
        val mean = rsrp.average()
        val sd = sqrt(rsrp.sumOf { (it - mean).pow(2) } / rsrp.size)
        return pts.filter { abs(it.rsrp - mean) <= k * sd }
    }
}

// ================================================================
// PHASE 4 — SECTOR DETECTION
// ================================================================
object SectorDetector {
    fun detect(towerId: String, towerLat: Double, towerLon: Double,
               measurements: List<MeasurementEntity>): List<SectorEntity> {
        val byPci = measurements.filter { it.pci != null && it.lat != null && it.lon != null }
            .groupBy { it.pci!! }
        return byPci.map { (pci, ms) ->
            val bearings = ms.map { Geo.bearing(towerLat, towerLon, it.lat!!, it.lon!!) }
            val azimuth = circularMean(bearings)
            val width = circularSpread(bearings).coerceAtMost(120.0)
            val radius = ms.mapNotNull {
                Geo.haversine(towerLat, towerLon, it.lat!!, it.lon!!)
            }.maxOrNull() ?: 0.0
            SectorEntity(
                sectorId = UUID.randomUUID().toString(),
                towerId = towerId, pci = pci,
                azimuth = azimuth, beamWidth = width,
                estimatedRadius = radius,
                averageSignal = ms.map { it.rsrp.toDouble() }.average(),
                samples = ms.size
            )
        }
    }

    private fun circularMean(angles: List<Double>): Double {
        var sx = 0.0; var sy = 0.0
        angles.forEach { sx += cos(Math.toRadians(it)); sy += sin(Math.toRadians(it)) }
        return (Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0
    }
    private fun circularSpread(angles: List<Double>): Double {
        if (angles.size < 2) return 60.0
        val mean = circularMean(angles)
        val deltas = angles.map { a ->
            val d = abs(a - mean) % 360.0; min(d, 360.0 - d)
        }
        return deltas.average() * 2.0
    }
}

// ================================================================
// PHASE 5 — COVERAGE MAPPING
// ================================================================
data class CoveragePoint(val lat: Double, val lon: Double, val signal: Double)

object CoverageMapper {
    fun buildHeatmap(measurements: List<MeasurementEntity>): List<CoveragePoint> =
        measurements.mapNotNull {
            if (it.lat != null && it.lon != null) CoveragePoint(it.lat, it.lon, it.rsrp.toDouble()) else null
        }

    fun interpolateGrid(points: List<CoveragePoint>, cellMeters: Double = 50.0): List<CoveragePoint> {
        if (points.isEmpty()) return emptyList()
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val latStep = cellMeters / 111_000.0
        val lonStep = cellMeters / (111_000.0 * cos(Math.toRadians((minLat + maxLat) / 2)))
        val grid = mutableListOf<CoveragePoint>()
        var lat = minLat
        while (lat <= maxLat) {
            var lon = minLon
            while (lon <= maxLon) {
                var num = 0.0; var den = 0.0
                points.forEach {
                    val d = Geo.haversine(lat, lon, it.lat, it.lon) + 1.0
                    val w = 1.0 / (d * d)
                    num += w * it.signal; den += w
                }
                grid += CoveragePoint(lat, lon, num / den)
                lon += lonStep
            }
            lat += latStep
        }
        return grid
    }

    fun deadZones(grid: List<CoveragePoint>, threshold: Double = -110.0): List<CoveragePoint> =
        grid.filter { it.signal < threshold }
}

// ================================================================
// PHASE 6 — BAND ANALYSIS
// ================================================================
data class LteBand(val name: String, val number: Int, val downlinkMHzStart: Double,
                   val earfcnStart: Int, val earfcnEnd: Int)
data class NrBand(val name: String, val number: Int, val freqStart: Double,
                  val nrarfcnStart: Int, val nrarfcnEnd: Int)

object BandAnalyzer {
    private val lteBands = listOf(
        LteBand("B1",  1,  2110.0, 0,     599),
        LteBand("B2",  2,  1930.0, 600,   1199),
        LteBand("B3",  3,  1805.0, 1200,  1949),
        LteBand("B4",  4,  2110.0, 1950,  2399),
        LteBand("B5",  5,   869.0, 2400,  2649),
        LteBand("B7",  7,  2620.0, 2750,  3449),
        LteBand("B8",  8,   925.0, 3450,  3799),
        LteBand("B20",20,  791.0,  6150,  6449),
        LteBand("B28",28,  758.0,  9210,  9659),
        LteBand("B38",38, 2570.0, 37750, 38249),
        LteBand("B40",40, 2300.0, 38650, 39649),
        LteBand("B41",41, 2496.0, 39650, 41589)
    )
    private val nrBands = listOf(
        NrBand("n1",   1, 2110.0, 422000, 434000),
        NrBand("n3",   3, 1805.0, 361000, 376000),
        NrBand("n7",   7, 2620.0, 524000, 538000),
        NrBand("n28", 28,  758.0, 151600, 160600),
        NrBand("n41", 41, 2496.0, 499200, 537999),
        NrBand("n77", 77, 3300.0, 620000, 680000),
        NrBand("n78", 78, 3300.0, 620000, 653333),
        NrBand("n79", 79, 4400.0, 693334, 733333)
    )

    fun lteBand(earfcn: Int): LteBand? = lteBands.firstOrNull { earfcn in it.earfcnStart..it.earfcnEnd }
    fun nrBand(nrarfcn: Int): NrBand? = nrBands.firstOrNull { nrarfcn in it.nrarfcnStart..it.nrarfcnEnd }

    fun lteFrequencyMHz(earfcn: Int): Double? = lteBand(earfcn)?.let {
        it.downlinkMHzStart + 0.1 * (earfcn - it.earfcnStart)
    }
    fun nrFrequencyMHz(nrarfcn: Int): Double? = nrBand(nrarfcn)?.let {
        it.freqStart + 0.015 * (nrarfcn - it.nrarfcnStart)
    }
}

// ================================================================
// PHASE 7 — NEIGHBOR ANALYSIS
// ================================================================
data class NeighborReport(
    val anchorTower: String,
    val neighborCount: Int,
    val stability: Double,
    val rotation: Double,
    val history: List<String>,
    val ranking: List<Pair<String, Double>>
)

object NeighborAnalyzer {
    fun analyze(anchor: String, samples: List<Pair<String, Long>>): NeighborReport {
        val unique = samples.map { it.first }.distinct()
        val history = samples.sortedBy { it.second }.map { it.first }
        val stability = if (history.isNotEmpty())
            history.groupingBy { it }.eachCount().values.max().toDouble() / history.size else 0.0
        val rotation = if (history.size < 2) 0.0 else
            history.zipWithNext().count { it.first != it.second }.toDouble() / (history.size - 1)
        val ranking = samples.groupingBy { it.first }.eachCount()
            .map { it.key to it.value.toDouble() }.sortedByDescending { it.second }
        return NeighborReport(anchor, unique.size, stability, rotation, history, ranking)
    }
}

// ================================================================
// PHASE 8 — HANDOVER ANALYSIS
// ================================================================
data class HandoverEvent(
    val fromTower: String, val toTower: String,
    val transitionMs: Long, val signalBefore: Int, val signalAfter: Int,
    val reason: String, val estimatedCause: String
)

object HandoverAnalyzer {
    fun extract(ordered: List<MeasurementEntity>): List<HandoverEvent> {
        val events = mutableListOf<HandoverEvent>()
        var prev: MeasurementEntity? = null
        ordered.forEach { cur ->
            val p = prev
            if (p != null && p.towerId != null && cur.towerId != null && p.towerId != cur.towerId) {
                val dt = cur.timestamp - p.timestamp
                val cause = when {
                    cur.rsrp - p.rsrp > 6 -> "Better signal"
                    p.rsrp < -110          -> "Weak source cell"
                    dt < 1000              -> "Fast handover"
                    else                   -> "Routine"
                }
                events += HandoverEvent(p.towerId, cur.towerId, dt, p.rsrp, cur.rsrp, "A3", cause)
            }
            prev = cur
        }
        return events
    }
}

// ================================================================
// PHASE 9 — MOVEMENT ANALYSIS
// ================================================================
enum class MovementMode { STATIC, WALKING, DRIVING, UNKNOWN }
data class MovementSample(val speed: Double, val acceleration: Double, val mode: MovementMode,
                          val heading: Double, val stopped: Boolean)

object MovementAnalyzer {
    fun analyzeStream(speeds: List<Pair<Double, Long>>, headings: List<Double>): List<MovementSample> {
        val out = mutableListOf<MovementSample>()
        for (i in speeds.indices) {
            val (s, t) = speeds[i]
            val acc = if (i == 0) 0.0 else {
                val dt = (t - speeds[i - 1].second).coerceAtLeast(1L) / 1000.0
                (s - speeds[i - 1].first) / dt
            }
            val mode = when {
                s < 0.3  -> MovementMode.STATIC
                s < 2.0  -> MovementMode.WALKING
                s >= 2.0 -> MovementMode.DRIVING
                else     -> MovementMode.UNKNOWN
            }
            out += MovementSample(s, acc, mode, headings.getOrElse(i) { 0.0 }, s < 0.2)
        }
        return out
    }
}

// ================================================================
// PHASE 10 — STATISTICS ENGINE
// ================================================================
data class Stats(val avg: Double, val median: Double, val variance: Double,
                 val min: Double, val max: Double, val sd: Double, val confidence: Double)

object StatsEngine {
    fun of(values: List<Double>): Stats {
        if (values.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sorted = values.sorted()
        val avg = sorted.average()
        val median = if (sorted.size % 2 == 0)
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0 else sorted[sorted.size / 2]
        val variance = sorted.sumOf { (it - avg).pow(2) } / sorted.size
        val sd = sqrt(variance)
        val confidence = (1.0 - (sd / (abs(avg) + 1.0))).coerceIn(0.0, 1.0) * 100.0
        return Stats(avg, median, variance, sorted.first(), sorted.last(), sd, confidence)
    }
}

// ================================================================
// PHASE 11 — TOWER CONFIDENCE ENGINE
// ================================================================
object ConfidenceEngine {
    fun scoreEstimation(measurements: List<MeasurementEntity>): Double {
        if (measurements.isEmpty()) return 0.0
        val sampleScore = (measurements.size.coerceAtMost(50) / 50.0) * 25.0
        val gpsScore = measurements.mapNotNull { it.accuracy?.toDouble() }.let {
            if (it.isEmpty()) 5.0 else (20.0 * (15.0 / (it.average().coerceAtLeast(1.0))).coerceAtMost(1.0))
        }
        val taScore = measurements.map { it.timingAdvance }.distinct().size.let {
            (it.coerceAtMost(10) / 10.0) * 15.0
        }
        val stability = 1.0 - (StatsEngine.of(measurements.map { it.rsrp.toDouble() }).sd / 40.0).coerceAtMost(1.0)
        val signalStability = stability * 20.0
        val movement = measurements.mapNotNull { it.heading?.toDouble() }.distinct().size.coerceAtMost(8) / 8.0 * 10.0
        val sectorConsistency = measurements.mapNotNull { it.pci }.distinct().size.let {
            if (it == 0) 0.0 else (10.0 / it).coerceAtMost(10.0)
        }
        return (sampleScore + gpsScore + taScore + signalStability + movement + sectorConsistency).coerceIn(0.0, 100.0)
    }
}

// ================================================================
// PHASE 12 — OFFLINE GEOLOCATION
// ================================================================
class OfflineGeolocator(private val db: CellularDatabase) {
    suspend fun resolve(cid: Long?, pci: Int?, earfcn: Int?): TowerEntity? = withContext(Dispatchers.IO) {
        db.towers().search(cid, pci, earfcn).maxByOrNull { it.confidenceScore }
    }
    suspend fun improveTower(towerId: String) = withContext(Dispatchers.Default) {
        val ms = db.measurements().forTower(towerId)
        val est = TowerEstimator.estimate(ms) ?: return@withContext
        val existing = db.towers().findById(towerId) ?: return@withContext
        val updated = existing.copy(
            lat = est.lat, lon = est.lon,
            estimatedAccuracy = est.radius,
            confidenceScore = est.confidence,
            seenCount = ms.size,
            lastSeen = ms.maxOf { it.timestamp },
            avgRsrp = ms.map { it.rsrp.toDouble() }.averageOrZero(),
            avgRsrq = ms.map { it.rsrq.toDouble() }.averageOrZero(),
            avgRssnr = ms.map { it.rssnr.toDouble() }.averageOrZero(),
            avgTimingAdvance = ms.map { it.timingAdvance.toDouble() }.averageOrZero(),
            avgDistance = ms.mapNotNull {
                if (it.lat != null && it.lon != null && est.lat != 0.0)
                    Geo.haversine(est.lat, est.lon, it.lat, it.lon) else null
            }.averageOrZero()
        )
        db.towers().upsert(updated)
    }
}

// ================================================================
// PHASE 13 — SMART CLUSTERING
// ================================================================
object Clustering {
    fun dbscan(points: List<Pair<Double, Double>>, epsMeters: Double, minPts: Int): List<Int> {
        val n = points.size
        val labels = IntArray(n) { -1 }
        var cluster = 0
        for (i in 0 until n) {
            if (labels[i] != -1) continue
            val neighbors = neighbors(points, i, epsMeters)
            if (neighbors.size < minPts) { labels[i] = -2; continue }
            labels[i] = cluster
            val queue = ArrayDeque(neighbors)
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (labels[j] == -2) labels[j] = cluster
                if (labels[j] != -1) continue
                labels[j] = cluster
                val njs = neighbors(points, j, epsMeters)
                if (njs.size >= minPts) queue.addAll(njs)
            }
            cluster++
        }
        return labels.toList()
    }
    private fun neighbors(pts: List<Pair<Double, Double>>, i: Int, eps: Double): List<Int> {
        val (lat, lon) = pts[i]
        return pts.indices.filter { Geo.haversine(lat, lon, pts[it].first, pts[it].second) <= eps }
    }

    fun kmeans(points: List<Pair<Double, Double>>, k: Int, iters: Int = 30): List<Int> {
        if (points.size <= k) return points.indices.toList()
        val centroids = points.shuffled().take(k).toMutableList()
        val labels = IntArray(points.size)
        repeat(iters) {
            points.forEachIndexed { idx, p ->
                labels[idx] = centroids.indices.minBy {
                    Geo.haversine(p.first, p.second, centroids[it].first, centroids[it].second)
                }
            }
            for (c in 0 until k) {
                val members = points.filterIndexed { i, _ -> labels[i] == c }
                if (members.isNotEmpty())
                    centroids[c] = members.map { it.first }.average() to members.map { it.second }.average()
            }
        }
        return labels.toList()
    }
}

// ================================================================
// PHASE 14 — SIGNAL PREDICTION
// ================================================================
object SignalPredictor {
    fun predict(target: Pair<Double, Double>, samples: List<CoveragePoint>, k: Int = 6): Double {
        if (samples.isEmpty()) return -120.0
        val nearest = samples.map { it to Geo.haversine(target.first, target.second, it.lat, it.lon) }
            .sortedBy { it.second }.take(k)
        var num = 0.0; var den = 0.0
        nearest.forEach { (s, d) ->
            val w = 1.0 / ((d + 1.0).pow(2))
            num += w * s.signal; den += w
        }
        return num / den
    }
}

// ================================================================
// PHASE 15 — DATA QUALITY ENGINE
// ================================================================
object QualityGate {
    fun score(m: MeasurementEntity, previous: MeasurementEntity? = null): Double {
        var s = 1.0
        if (m.accuracy != null && m.accuracy > 30) s -= 0.3
        if (m.rsrp >= 0 || m.rsrp < -140) s -= 0.4
        if (m.timingAdvance < 0) s -= 0.1
        if (previous != null && previous.timestamp == m.timestamp && previous.cid == m.cid) s -= 0.5
        return s.coerceIn(0.0, 1.0)
    }
    fun accept(m: MeasurementEntity, previous: MeasurementEntity? = null, min: Double = 0.4): Boolean =
        score(m, previous) >= min
}

// ================================================================
// PHASE 16 — OFFLINE MAPS (stub)
// ================================================================
class OfflineTileProvider(private val mbtilesPath: String) {
    fun isAvailable(): Boolean = File(mbtilesPath).exists()
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!isAvailable()) return null
        return null
    }
}

// ================================================================
// PHASE 17 — EXPORT ENGINE
// ================================================================
object ExportEngine {
    suspend fun exportCsv(file: File, measurements: List<MeasurementEntity>) = withContext(Dispatchers.IO) {
        FileWriter(file).use { w ->
            w.appendLine("id,sessionId,towerId,lat,lon,heading,speed,altitude,accuracy,cid,pci,earfcn,rsrp,rsrq,rssnr,ta,timestamp,qualityScore")
            measurements.forEach {
                w.appendLine("${it.id},${it.sessionId ?: ""},${it.towerId ?: ""},${it.lat ?: ""},${it.lon ?: ""},${it.heading ?: ""},${it.speed ?: ""},${it.altitude ?: ""},${it.accuracy ?: ""},${it.cid ?: ""},${it.pci ?: ""},${it.earfcn ?: ""},${it.rsrp},${it.rsrq},${it.rssnr},${it.timingAdvance},${it.timestamp},${it.qualityScore}")
            }
        }
    }

    suspend fun exportJson(file: File, towers: List<TowerEntity>) = withContext(Dispatchers.IO) {
        FileWriter(file).use { w ->
            w.append("[")
            towers.forEachIndexed { i, t ->
                if (i > 0) w.append(",")
                w.append("""{"towerId":"${t.towerId}","mcc":${t.mcc},"mnc":${t.mnc},"tac":${t.tac},"cid":${t.cid},"pci":${t.pci},"earfcn":${t.earfcn},"band":"${t.band ?: ""}","lat":${t.lat},"lon":${t.lon},"confidence":${t.confidenceScore}}""")
            }
            w.append("]")
        }
    }

    suspend fun exportGeoJson(file: File, towers: List<TowerEntity>) = withContext(Dispatchers.IO) {
        FileWriter(file).use { w ->
            w.append("""{"type":"FeatureCollection","features":[""")
            towers.filter { it.lat != null && it.lon != null }.forEachIndexed { i, t ->
                if (i > 0) w.append(",")
                w.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[${t.lon},${t.lat}]},"properties":{"cid":${t.cid},"pci":${t.pci},"confidence":${t.confidenceScore}}}""")
            }
            w.append("]}")
        }
    }

    suspend fun exportKml(file: File, towers: List<TowerEntity>) = withContext(Dispatchers.IO) {
        FileWriter(file).use { w ->
            w.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            w.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""")
            towers.filter { it.lat != null && it.lon != null }.forEach { t ->
                w.appendLine("<Placemark><name>${t.cid ?: t.towerId}</name><Point><coordinates>${t.lon},${t.lat},0</coordinates></Point></Placemark>")
            }
            w.appendLine("</Document></kml>")
        }
    }

    suspend fun exportGpx(file: File, measurements: List<MeasurementEntity>) = withContext(Dispatchers.IO) {
        FileWriter(file).use { w ->
            w.appendLine("""<?xml version="1.0"?><gpx version="1.1" creator="CellularMapperPro"><trk><trkseg>""")
            measurements.filter { it.lat != null && it.lon != null }.forEach {
                w.appendLine("""<trkpt lat="${it.lat}" lon="${it.lon}"><time>${it.timestamp}</time></trkpt>""")
            }
            w.appendLine("</trkseg></trk></gpx>")
        }
    }

    suspend fun backupSqlite(ctx: Context, dest: File) = withContext(Dispatchers.IO) {
        val src = ctx.getDatabasePath("cellular_pro.db")
        src.copyTo(dest, overwrite = true)
    }
}

// ================================================================
// PHASE 18 — SEARCH ENGINE
// ================================================================
class SearchEngine(private val db: CellularDatabase) {
    suspend fun byCid(cid: Long) = db.towers().search(cid, null, null)
    suspend fun byPci(pci: Int) = db.towers().search(null, pci, null)
    suspend fun byBand(band: String) = db.towers().observeAll().first().filter { it.band == band }
    suspend fun byEarfcn(earfcn: Int) = db.towers().search(null, null, earfcn)
    suspend fun byTowerId(id: String) = listOfNotNull(db.towers().findById(id))
    suspend fun bySession(id: String) = db.measurements().forSession(id)
    suspend fun byDateRange(from: Long, to: Long): List<MeasurementEntity> {
        val all = db.measurements().latest(Int.MAX_VALUE)
        return all.filter { it.timestamp in from..to }
    }
}

// ================================================================
// PHASE 19 — TIMELINE
// ================================================================
data class TimelineEntry(val timestamp: Long, val type: String, val label: String, val details: String)

object TimelineBuilder {
    suspend fun build(db: CellularDatabase): List<TimelineEntry> = withContext(Dispatchers.Default) {
        val out = mutableListOf<TimelineEntry>()
        db.towers().observeAll().first().forEach {
            out += TimelineEntry(it.firstSeen, "TOWER", "Tower ${it.cid}", "PCI=${it.pci} band=${it.band}")
        }
        db.sessions().all().forEach {
            out += TimelineEntry(it.start, "SESSION_START", "Session", "samples=${it.collectedSamples}")
            it.end?.let { e -> out += TimelineEntry(e, "SESSION_END", "Session end", "distance=${it.distance}m") }
        }
        db.measurements().latest(2000).forEach {
            out += TimelineEntry(it.timestamp, "MEASURE", "RSRP=${it.rsrp}", "PCI=${it.pci}")
        }
        out.sortedBy { it.timestamp }
    }
}

// ================================================================
// PHASE 20 — PERFORMANCE / BACKGROUND SCAFFOLDING
// ================================================================
object Scheduler {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val cpuScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun io(block: suspend CoroutineScope.() -> Unit) = ioScope.launch(block = block)
    fun cpu(block: suspend CoroutineScope.() -> Unit) = cpuScope.launch(block = block)
}

abstract class BaseSurveyService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
}

// ================================================================
// PHASE 21 — UI HELPERS
// ================================================================
object CyberTheme {
    const val BG       = 0xFF05070D.toInt()
    const val SURFACE  = 0xFF0B1020.toInt()
    const val PRIMARY  = 0xFF00E5FF.toInt()
    const val ACCENT   = 0xFF7CFFB2.toInt()
    const val DANGER   = 0xFFFF4D6D.toInt()
    const val MUTED    = 0xFF8893A8.toInt()
    const val FG       = 0xFFE8F1FF.toInt()
}

data class DashboardSnapshot(
    val totalTowers: Int, val totalSamples: Int, val avgRsrp: Double,
    val avgConfidence: Double, val activeSessions: Int, val deadZones: Int
)

class DashboardEngine(private val db: CellularDatabase) {
    suspend fun snapshot(): DashboardSnapshot = withContext(Dispatchers.IO) {
        val towers = db.towers().observeAll().first()
        val samples = db.measurements().count()
        DashboardSnapshot(
            totalTowers = towers.size,
            totalSamples = samples,
            avgRsrp = towers.map { it.avgRsrp }.averageOrZero(),
            avgConfidence = towers.map { it.confidenceScore }.averageOrZero(),
            activeSessions = db.sessions().all().count { it.end == null },
            deadZones = 0
        )
    }
}

// ================================================================
// SHARED UTILITIES
// ================================================================
object Geo {
    private const val R = 6_371_000.0
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1); val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

// ================================================================
// HIGH-LEVEL INGESTION PIPELINE (using CellularCellInfo only)
// ================================================================
class CellularPipeline(ctx: Context) {
    private val db = CellularDatabase.get(ctx)
    val geolocator = OfflineGeolocator(db)
    val dashboard  = DashboardEngine(db)
    val search     = SearchEngine(db)

    private var currentSessionId: String? = null
    private var lastMeasurement: MeasurementEntity? = null

    suspend fun startSession(): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        db.sessions().insert(SurveySessionEntity(id, System.currentTimeMillis(), null, 0.0, 0.0, 0, 0))
        currentSessionId = id
        id
    }

    suspend fun endSession() = withContext(Dispatchers.IO) {
        val id = currentSessionId ?: return@withContext
        val existing = db.sessions().byId(id) ?: return@withContext
        val ms = db.measurements().forSession(id)
        val distance = ms.zipWithNext().sumOf { (a, b) ->
            if (a.lat != null && a.lon != null && b.lat != null && b.lon != null)
                Geo.haversine(a.lat, a.lon, b.lat, b.lon) else 0.0
        }
        val avgSpeed = ms.mapNotNull { it.speed?.toDouble() }.averageOrZero()
        db.sessions().update(existing.copy(
            end = System.currentTimeMillis(), distance = distance, averageSpeed = avgSpeed,
            collectedSamples = ms.size, collectedTowers = ms.mapNotNull { it.towerId }.distinct().size
        ))
        currentSessionId = null
    }

    /** Ingest a CellularCellInfo (already extracted) into the advanced platform. */
    suspend fun ingest(cell: CellularCellInfo) = withContext(Dispatchers.IO) {
        val band = cell.earfcn?.let { BandAnalyzer.lteBand(it)?.name }
        val tower = upsertTower(cell, band)
        val m = MeasurementEntity(
            sessionId = currentSessionId, towerId = tower.towerId,
            lat = cell.lat, lon = cell.lon, heading = cell.heading, speed = cell.speed,
            altitude = null, accuracy = cell.accuracy,
            cid = cell.cid, pci = cell.pci, earfcn = cell.earfcn,
            rsrp = cell.rsrp, rsrq = cell.rsrq, rssnr = cell.rssnr,
            timingAdvance = cell.ta, timestamp = cell.timestamp
        )
        val quality = QualityGate.score(m, lastMeasurement)
        if (quality >= 0.4) {
            db.measurements().insert(m.copy(qualityScore = quality))
            lastMeasurement = m
            Scheduler.cpu { geolocator.improveTower(tower.towerId) }
            Scheduler.cpu { refreshSectors(tower.towerId) }
        }
    }

    private suspend fun upsertTower(c: CellularCellInfo, band: String?): TowerEntity {
        val existing = if (c.mcc != null && c.mnc != null && c.tac != null && c.cid != null)
            db.towers().findByKey(c.mcc, c.mnc, c.tac, c.cid) else null
        val now = System.currentTimeMillis()
        val tower = existing?.copy(lastSeen = now, seenCount = existing.seenCount + 1)
            ?: TowerEntity(
                towerId = UUID.randomUUID().toString(),
                mcc = c.mcc, mnc = c.mnc, tac = c.tac, cid = c.cid,
                pci = c.pci, earfcn = c.earfcn, band = band,
                lat = c.lat, lon = c.lon, estimatedAccuracy = 500.0,
                firstSeen = now, lastSeen = now, seenCount = 1,
                avgRsrp = c.rsrp.toDouble(), avgRsrq = c.rsrq.toDouble(),
                avgRssnr = c.rssnr.toDouble(), avgTimingAdvance = c.ta.toDouble(),
                avgDistance = 0.0, confidenceScore = 10.0, sectorCount = 1
            )
        db.towers().upsert(tower)
        return tower
    }

    private suspend fun refreshSectors(towerId: String) {
        val tower = db.towers().findById(towerId) ?: return
        if (tower.lat == null || tower.lon == null) return
        val ms = db.measurements().forTower(towerId)
        val sectors = SectorDetector.detect(towerId, tower.lat, tower.lon, ms)
        sectors.forEach { db.sectors().upsert(it) }
    }
}

// ======== END OF PART 2 ========

// ================================================================
//   ADVANCED EXTENSION PACK v2  (Additive — does NOT modify above)
//   22 Professional Engines + AdvancedSuite
//   PART 3/3 — يضاف بعد نهاية الجزء الثاني (بعد CellularPipeline)
// ================================================================

// ----------------------------------------------------------------
// 0) Shared data containers used by the advanced engines
// ----------------------------------------------------------------
data class AdvSample(
    val ts: Long,
    val lat: Double, val lon: Double,
    val accuracy: Double, val speed: Double, val heading: Double,
    val mcc: Int?, val mnc: Int?, val tac: Int?, val cid: Long?,
    val pci: Int?, val earfcn: Int?,
    val rsrp: Double, val rsrq: Double, val rssnr: Double,
    val ta: Int, val band: String?
)

data class AdvEstimatedTower(
    val key: String,
    val lat: Double, val lon: Double,
    val radiusErrorM: Double,
    val confidence: Double,
    val nMeasurements: Int,
    val method: String
)

// ----------------------------------------------------------------
// 1) Tower Geolocation Engine
// ----------------------------------------------------------------
object AdvTowerGeolocation {
    private fun rsrpWeight(rsrp: Double): Double {
        val x = (rsrp + 140.0).coerceIn(0.0, 60.0)
        return x * x + 1.0
    }

    fun weightedCentroid(samples: List<AdvSample>): Pair<Double, Double> {
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (s in samples) {
            val w = rsrpWeight(s.rsrp) / (s.accuracy.coerceAtLeast(1.0))
            sx += s.lat * w; sy += s.lon * w; sw += w
        }
        return if (sw == 0.0) samples.first().lat to samples.first().lon
        else (sx / sw) to (sy / sw)
    }

    private fun taDistance(ta: Int): Double = ta.coerceAtLeast(0) * 78.12

    fun leastSquaresRefine(
        samples: List<AdvSample>,
        seedLat: Double, seedLon: Double,
        iterations: Int = 25
    ): Triple<Double, Double, Double> {
        var lat = seedLat; var lon = seedLon
        val mPerLatDeg = 111_320.0
        val mPerLonDeg = 111_320.0 * cos(Math.toRadians(seedLat))
        var lastRms = Double.MAX_VALUE
        for (it in 0 until iterations) {
            var jtj00 = 0.0; var jtj01 = 0.0; var jtj11 = 0.0
            var jtr0 = 0.0; var jtr1 = 0.0
            var sse = 0.0; var n = 0
            for (s in samples) {
                val dx = (s.lon - lon) * mPerLonDeg
                val dy = (s.lat - lat) * mPerLatDeg
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.5)
                val r = if (s.ta > 0) taDistance(s.ta) else d
                val w = rsrpWeight(s.rsrp)
                val residual = d - r
                val jx = -dx / d; val jy = -dy / d
                jtj00 += w * jx * jx; jtj11 += w * jy * jy; jtj01 += w * jx * jy
                jtr0  += w * jx * residual; jtr1 += w * jy * residual
                sse += w * residual * residual; n++
            }
            val det = jtj00 * jtj11 - jtj01 * jtj01
            if (abs(det) < 1e-9) break
            val dx = ( jtj11 * jtr0 - jtj01 * jtr1) / det
            val dy = (-jtj01 * jtr0 + jtj00 * jtr1) / det
            lon -= dx / mPerLonDeg
            lat -= dy / mPerLatDeg
            val rms = sqrt(sse / max(1, n))
            if (abs(lastRms - rms) < 0.1) break
            lastRms = rms
        }
        return Triple(lat, lon, lastRms)
    }

    fun estimate(key: String, samples: List<AdvSample>): AdvEstimatedTower? {
        if (samples.size < 3) return null
        val (clat, clon) = weightedCentroid(samples)
        val (lat, lon, rms) = leastSquaresRefine(samples, clat, clon)
        val conf = AdvConfidence.score(samples, rms)
        return AdvEstimatedTower(key, lat, lon, rms, conf, samples.size, "WLS+Centroid")
    }
}

// ----------------------------------------------------------------
// 2) Multi-Lateration Engine
// ----------------------------------------------------------------
object AdvMultilateration {
    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(la2 - la1); val dLon = Math.toRadians(lo2 - lo1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun solve(samples: List<AdvSample>, distances: DoubleArray): Pair<Double, Double>? {
        if (samples.size < 3) return null
        val n = samples.size
        val ref = samples[0]
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(ref.lat))
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = (samples[i].lon - ref.lon) * mPerLon
            ys[i] = (samples[i].lat - ref.lat) * mPerLat
        }
        val A = Array(n - 1) { DoubleArray(2) }
        val b = DoubleArray(n - 1)
        val r0 = distances[0]
        for (i in 1 until n) {
            A[i - 1][0] = 2 * (xs[i] - xs[0])
            A[i - 1][1] = 2 * (ys[i] - ys[0])
            b[i - 1] = (r0 * r0 - distances[i] * distances[i]) -
                       (xs[0] * xs[0] - xs[i] * xs[i]) -
                       (ys[0] * ys[0] - ys[i] * ys[i])
        }
        var a00 = 0.0; var a01 = 0.0; var a11 = 0.0; var c0 = 0.0; var c1 = 0.0
        for (i in 0 until n - 1) {
            a00 += A[i][0] * A[i][0]
            a01 += A[i][0] * A[i][1]
            a11 += A[i][1] * A[i][1]
            c0  += A[i][0] * b[i]
            c1  += A[i][1] * b[i]
        }
        val det = a00 * a11 - a01 * a01
        if (abs(det) < 1e-9) return null
        val x = ( a11 * c0 - a01 * c1) / det
        val y = (-a01 * c0 + a00 * c1) / det
        return (ref.lat + y / mPerLat) to (ref.lon + x / mPerLon)
    }
}

// ----------------------------------------------------------------
// 3) Kalman Filters
// ----------------------------------------------------------------
class AdvKalman1D(private var q: Double = 0.01, private var r: Double = 1.0) {
    private var x: Double = 0.0
    private var p: Double = 1.0
    private var inited = false
    fun update(z: Double): Double {
        if (!inited) { x = z; inited = true; return x }
        p += q
        val k = p / (p + r)
        x += k * (z - x)
        p *= (1 - k)
        return x
    }
}

class AdvKalmanGps {
    private val kLat = AdvKalman1D(q = 1e-6, r = 5e-5)
    private val kLon = AdvKalman1D(q = 1e-6, r = 5e-5)
    fun update(lat: Double, lon: Double): Pair<Double, Double> =
        kLat.update(lat) to kLon.update(lon)
}

// ----------------------------------------------------------------
// 4) Particle Filter
// ----------------------------------------------------------------
class AdvParticleFilter(
    private val n: Int = 500,
    private val processNoiseM: Double = 25.0
) {
    data class P(var lat: Double, var lon: Double, var w: Double)
    private val particles = ArrayList<P>(n)
    private var initialized = false

    fun init(lat: Double, lon: Double, radiusM: Double = 1500.0) {
        particles.clear()
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat))
        repeat(n) {
            val r = radiusM * sqrt(Math.random())
            val a = Math.random() * 2 * PI
            particles.add(P(lat + (r * sin(a)) / mPerLat, lon + (r * cos(a)) / mPerLon, 1.0 / n))
        }
        initialized = true
    }

    fun step(measurement: AdvSample, expectedDist: Double) {
        if (!initialized) { init(measurement.lat, measurement.lon); return }
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(measurement.lat))
        var sumW = 0.0
        for (p in particles) {
            p.lat += (Math.random() - 0.5) * processNoiseM / mPerLat
            p.lon += (Math.random() - 0.5) * processNoiseM / mPerLon
            val dx = (p.lon - measurement.lon) * mPerLon
            val dy = (p.lat - measurement.lat) * mPerLat
            val d = sqrt(dx * dx + dy * dy)
            val err = d - expectedDist
            p.w = exp(-(err * err) / (2 * 250.0 * 250.0))
            sumW += p.w
        }
        if (sumW <= 0) return
        for (p in particles) p.w /= sumW
        val cdf = DoubleArray(n)
        cdf[0] = particles[0].w
        for (i in 1 until n) cdf[i] = cdf[i - 1] + particles[i].w
        val newParts = ArrayList<P>(n)
        val u0 = Math.random() / n
        var i = 0
        for (j in 0 until n) {
            val u = u0 + j.toDouble() / n
            while (i < n - 1 && u > cdf[i]) i++
            newParts.add(particles[i].copy(w = 1.0 / n))
        }
        particles.clear(); particles.addAll(newParts)
    }

    fun estimate(): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (p in particles) { sx += p.lat * p.w; sy += p.lon * p.w; sw += p.w }
        return (sx / sw) to (sy / sw)
    }
}

// ----------------------------------------------------------------
// 5) DBSCAN Clustering Engine
// ----------------------------------------------------------------
object AdvDbscan {
    private const val NOISE = -1
    private const val UNCLASSIFIED = 0

    private fun dist(a: AdvSample, b: AdvSample): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    fun cluster(samples: List<AdvSample>, epsMeters: Double = 100.0, minPts: Int = 5): IntArray {
        val n = samples.size
        val labels = IntArray(n) { UNCLASSIFIED }
        var cid = 0
        for (i in 0 until n) {
            if (labels[i] != UNCLASSIFIED) continue
            val neighbors = (0 until n).filter { it != i && dist(samples[i], samples[it]) <= epsMeters }
            if (neighbors.size < minPts) { labels[i] = NOISE; continue }
            cid++
            labels[i] = cid
            val queue = ArrayDeque(neighbors)
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (labels[j] == NOISE) labels[j] = cid
                if (labels[j] != UNCLASSIFIED) continue
                labels[j] = cid
                val nb2 = (0 until n).filter { it != j && dist(samples[j], samples[it]) <= epsMeters }
                if (nb2.size >= minPts) queue.addAll(nb2)
            }
        }
        return labels
    }
}

// ----------------------------------------------------------------
// 6) Circular Statistics Engine
// ----------------------------------------------------------------
object AdvCircularStats {
    fun circularMean(anglesDeg: DoubleArray, weights: DoubleArray? = null): Double {
        var sx = 0.0; var sy = 0.0
        for (i in anglesDeg.indices) {
            val w = weights?.get(i) ?: 1.0
            val r = Math.toRadians(anglesDeg[i])
            sx += w * cos(r); sy += w * sin(r)
        }
        val a = Math.toDegrees(atan2(sy, sx))
        return (a + 360.0) % 360.0
    }

    fun circularVariance(anglesDeg: DoubleArray): Double {
        var sx = 0.0; var sy = 0.0
        for (a in anglesDeg) { sx += cos(Math.toRadians(a)); sy += sin(Math.toRadians(a)) }
        val r = sqrt(sx * sx + sy * sy) / anglesDeg.size
        return 1 - r
    }

    fun vonMisesKappa(anglesDeg: DoubleArray): Double {
        var sx = 0.0; var sy = 0.0
        for (a in anglesDeg) { sx += cos(Math.toRadians(a)); sy += sin(Math.toRadians(a)) }
        val rBar = sqrt(sx * sx + sy * sy) / anglesDeg.size
        return when {
            rBar < 0.53 -> 2 * rBar + rBar.pow(3) + 5 * rBar.pow(5) / 6
            rBar < 0.85 -> -0.4 + 1.39 * rBar + 0.43 / (1 - rBar)
            else -> 1 / (rBar.pow(3) - 4 * rBar.pow(2) + 3 * rBar)
        }
    }
}

// ----------------------------------------------------------------
// 7) Radio Propagation Engine
// ----------------------------------------------------------------
object AdvPropagation {
    enum class Env { Urban, Suburban, Rural, OpenSpace }

    fun freeSpacePathLoss(freqMhz: Double, distanceM: Double): Double =
        20 * log10(distanceM / 1000.0) + 20 * log10(freqMhz) + 32.44

    fun logDistanceModel(freqMhz: Double, distanceM: Double, n: Double = 3.0): Double {
        val pl0 = freeSpacePathLoss(freqMhz, 1.0)
        return pl0 + 10 * n * log10(distanceM.coerceAtLeast(1.0))
    }

    fun okumuraHata(freqMhz: Double, hb: Double, hm: Double, dKm: Double, env: Env = Env.Urban): Double {
        val a = if (freqMhz >= 400)
            (1.1 * log10(freqMhz) - 0.7) * hm - (1.56 * log10(freqMhz) - 0.8)
        else
            8.29 * log10(1.54 * hm).pow(2) - 1.1
        val l = 69.55 + 26.16 * log10(freqMhz) - 13.82 * log10(hb) - a +
                (44.9 - 6.55 * log10(hb)) * log10(dKm.coerceAtLeast(0.01))
        return when (env) {
            Env.Urban -> l
            Env.Suburban -> l - 2 * (log10(freqMhz / 28)).pow(2) - 5.4
            Env.Rural -> l - 4.78 * (log10(freqMhz)).pow(2) + 18.33 * log10(freqMhz) - 40.94
            Env.OpenSpace -> l - 4.78 * (log10(freqMhz)).pow(2) + 18.33 * log10(freqMhz) - 35.94
        }
    }

    fun cost231Hata(freqMhz: Double, hb: Double, hm: Double, dKm: Double, env: Env = Env.Urban): Double {
        val a = (1.1 * log10(freqMhz) - 0.7) * hm - (1.56 * log10(freqMhz) - 0.8)
        val cm = if (env == Env.Urban) 3.0 else 0.0
        return 46.3 + 33.9 * log10(freqMhz) - 13.82 * log10(hb) - a +
               (44.9 - 6.55 * log10(hb)) * log10(dKm.coerceAtLeast(0.01)) + cm
    }

    fun estimateDistanceM(rsrp: Double, txPowerDbm: Double, freqMhz: Double, n: Double = 3.0): Double {
        val pl = txPowerDbm - rsrp
        val pl0 = freeSpacePathLoss(freqMhz, 1.0)
        val exp10 = (pl - pl0) / (10.0 * n)
        return 10.0.pow(exp10).coerceIn(1.0, 50_000.0)
    }
}

// ----------------------------------------------------------------
// 8) Coverage Prediction Engine
// ----------------------------------------------------------------
data class AdvCoverageGrid(val cellM: Double, val cells: Map<Pair<Int, Int>, Double>)

object AdvCoverageEngine {
    fun predict(samples: List<AdvSample>, cellM: Double = 50.0): AdvCoverageGrid {
        if (samples.isEmpty()) return AdvCoverageGrid(cellM, emptyMap())
        val ref = samples[0]
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(ref.lat))
        val acc = HashMap<Pair<Int, Int>, Pair<Double, Double>>()
        for (s in samples) {
            val x = ((s.lon - ref.lon) * mPerLon / cellM).toInt()
            val y = ((s.lat - ref.lat) * mPerLat / cellM).toInt()
            val w = 1.0 / (s.accuracy.coerceAtLeast(1.0))
            val cur = acc[x to y] ?: (0.0 to 0.0)
            acc[x to y] = (cur.first + w) to (cur.second + w * s.rsrp)
        }
        val cells = acc.mapValues { (_, v) -> v.second / v.first }
        return AdvCoverageGrid(cellM, cells)
    }

    fun classifyZone(rsrpAvg: Double): String = when {
        rsrpAvg >= -85 -> "Strong"
        rsrpAvg >= -100 -> "Good"
        rsrpAvg >= -110 -> "Weak"
        rsrpAvg >= -120 -> "Very Weak"
        else -> "Dead"
    }
}

// ----------------------------------------------------------------
// 9) Heatmap Engine
// ----------------------------------------------------------------
class AdvHeatmapEngine(private val power: Double = 2.0) {
    private val tileCache = ConcurrentHashMap<String, DoubleArray>()

    fun interpolate(samples: List<AdvSample>, lat: Double, lon: Double, channel: (AdvSample) -> Double): Double {
        var sw = 0.0; var sv = 0.0
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat))
        for (s in samples) {
            val dx = (s.lon - lon) * mPerLon; val dy = (s.lat - lat) * mPerLat
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)
            val w = 1.0 / d.pow(power)
            sw += w; sv += w * channel(s)
        }
        return if (sw == 0.0) Double.NaN else sv / sw
    }

    fun tile(key: String, builder: () -> DoubleArray): DoubleArray =
        tileCache.getOrPut(key, builder)

    fun clearCache() = tileCache.clear()
}

// ----------------------------------------------------------------
// 10) Fingerprinting Engine (advanced metrics)
// ----------------------------------------------------------------
data class AdvFingerprint(
    val key: String,
    val signal: DoubleArray,    // [rsrp, rsrq, rssnr]
    val neighbors: IntArray,    // PCIs seen
    val mobility: DoubleArray   // [avgSpeed, headingVar]
)

object AdvFingerprintEngine {
    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }
    fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0; for (i in a.indices) s += (a[i] - b[i]).pow(2); return sqrt(s)
    }
    fun mahalanobis(a: DoubleArray, b: DoubleArray, variance: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) {
            val v = variance[i].coerceAtLeast(1e-6)
            s += (a[i] - b[i]).pow(2) / v
        }
        return sqrt(s)
    }
    fun histogramMatch(h1: DoubleArray, h2: DoubleArray): Double {
        var s = 0.0; var n1 = 0.0; var n2 = 0.0
        for (i in h1.indices) { s += min(h1[i], h2[i]); n1 += h1[i]; n2 += h2[i] }
        return s / max(n1, n2).coerceAtLeast(1e-6)
    }
    fun bayesianPosterior(prior: Double, likelihood: Double, evidence: Double): Double =
        (likelihood * prior) / evidence.coerceAtLeast(1e-9)
    fun dtw(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size; val m = b.size
        val d = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE } }
        d[0][0] = 0.0
        for (i in 1..n) for (j in 1..m) {
            val cost = abs(a[i - 1] - b[j - 1])
            d[i][j] = cost + minOf(d[i - 1][j], d[i][j - 1], d[i - 1][j - 1])
        }
        return d[n][m]
    }
    fun match(a: AdvFingerprint, b: AdvFingerprint, variance: DoubleArray = doubleArrayOf(4.0, 4.0, 4.0)): Double {
        val sigSim = 1 - euclidean(a.signal, b.signal) / 100.0
        val mahSim = 1 - mahalanobis(a.signal, b.signal, variance) / 10.0
        val cos = cosine(a.signal, b.signal)
        val nbSim = run {
            val sa = a.neighbors.toSet(); val sb = b.neighbors.toSet()
            if (sa.isEmpty() && sb.isEmpty()) 1.0
            else sa.intersect(sb).size.toDouble() / sa.union(sb).size.coerceAtLeast(1)
        }
        return (0.4 * sigSim + 0.2 * mahSim + 0.2 * cos + 0.2 * nbSim).coerceIn(0.0, 1.0)
    }
}

// ----------------------------------------------------------------
// 11) Neighbor Relation Engine
// ----------------------------------------------------------------
class AdvNeighborGraph {
    private val edges = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    fun observe(from: String, to: String) {
        val m = edges.getOrPut(from) { ConcurrentHashMap() }
        m[to] = (m[to] ?: 0) + 1
    }
    fun transitionMatrix(): Map<String, Map<String, Double>> {
        val out = HashMap<String, Map<String, Double>>()
        for ((from, m) in edges) {
            val total = m.values.sum().toDouble().coerceAtLeast(1.0)
            out[from] = m.mapValues { it.value / total }
        }
        return out
    }
    fun strongNeighbors(key: String, k: Int = 5): List<String> =
        (edges[key] ?: emptyMap()).entries.sortedByDescending { it.value }.take(k).map { it.key }
    fun hiddenNeighbors(allCells: Set<String>): Map<String, Set<String>> {
        val result = HashMap<String, Set<String>>()
        for (a in allCells) {
            val known = (edges[a] ?: emptyMap()).keys
            result[a] = allCells - known - a
        }
        return result
    }
}

// ----------------------------------------------------------------
// 12) Mobility Analysis Engine
// ----------------------------------------------------------------
class AdvMobilityAnalyzer {
    private val handovers = ArrayDeque<Triple<String, String, Long>>()
    private var failures = 0
    private val cellStay = ConcurrentHashMap<String, MutableList<Long>>()

    fun onHandover(from: String, to: String, ts: Long, success: Boolean) {
        handovers.addLast(Triple(from, to, ts))
        if (!success) failures++
        if (handovers.size > 5000) handovers.removeFirst()
    }
    fun onCellStay(cell: String, durationMs: Long) {
        cellStay.getOrPut(cell) { mutableListOf() }.add(durationMs)
    }
    fun handoverRate(windowMs: Long = 60_000): Double {
        val now = System.currentTimeMillis()
        val n = handovers.count { now - it.third <= windowMs }
        return n / (windowMs / 1000.0)
    }
    fun pingPong(thresholdMs: Long = 5_000): Int {
        var c = 0
        val arr = handovers.toList()
        for (i in 1 until arr.size) {
            if (arr[i].second == arr[i - 1].first && arr[i].third - arr[i - 1].third < thresholdMs) c++
        }
        return c
    }
    fun failureRate(): Double = if (handovers.isEmpty()) 0.0 else failures.toDouble() / handovers.size
    fun averageStay(): Double {
        val all = cellStay.values.flatten()
        return if (all.isEmpty()) 0.0 else all.average()
    }
}

// ----------------------------------------------------------------
// 13) Fake Cell Detection Engine
// ----------------------------------------------------------------
data class AdvRiskBreakdown(
    val pciConflict: Double, val cidConflict: Double, val tacConflict: Double,
    val signalJump: Double, val neighborChanges: Double, val taAnomaly: Double,
    val freqChange: Double, val powerChange: Double, val registrationBehavior: Double,
    val locationConsistency: Double
)

enum class AdvRiskLevel { LOW, MEDIUM, HIGH, VERY_HIGH }

object AdvFakeCellEngine {
    fun score(b: AdvRiskBreakdown): Double {
        val w = doubleArrayOf(0.15, 0.15, 0.10, 0.12, 0.08, 0.10, 0.05, 0.05, 0.10, 0.10)
        val v = doubleArrayOf(
            b.pciConflict, b.cidConflict, b.tacConflict, b.signalJump,
            b.neighborChanges, b.taAnomaly, b.freqChange, b.powerChange,
            b.registrationBehavior, b.locationConsistency
        )
        var s = 0.0; for (i in v.indices) s += w[i] * v[i].coerceIn(0.0, 1.0)
        return s.coerceIn(0.0, 1.0)
    }
    fun level(score: Double): AdvRiskLevel = when {
        score < 0.30 -> AdvRiskLevel.LOW
        score < 0.55 -> AdvRiskLevel.MEDIUM
        score < 0.80 -> AdvRiskLevel.HIGH
        else -> AdvRiskLevel.VERY_HIGH
    }
}

// ----------------------------------------------------------------
// 14) Band Detection Engine (extended)
// ----------------------------------------------------------------
object AdvBandDetector {
    private data class LteBand(val band: Int, val dlLow: Int, val dlHigh: Int, val fDlLowMhz: Double)
    private val lte = listOf(
        LteBand(1, 0, 599, 2110.0), LteBand(2, 600, 1199, 1930.0),
        LteBand(3, 1200, 1949, 1805.0), LteBand(4, 1950, 2399, 2110.0),
        LteBand(5, 2400, 2649, 869.0), LteBand(7, 2750, 3449, 2620.0),
        LteBand(8, 3450, 3799, 925.0), LteBand(12, 5010, 5179, 729.0),
        LteBand(13, 5180, 5279, 746.0), LteBand(17, 5730, 5849, 734.0),
        LteBand(20, 6150, 6449, 791.0), LteBand(25, 8040, 8689, 1930.0),
        LteBand(26, 8690, 9039, 859.0), LteBand(28, 9210, 9659, 758.0),
        LteBand(38, 37750, 38249, 2570.0), LteBand(39, 38250, 38649, 1880.0),
        LteBand(40, 38650, 39649, 2300.0), LteBand(41, 39650, 41589, 2496.0),
        LteBand(42, 41590, 43589, 3400.0), LteBand(43, 43590, 45589, 3600.0)
    )

    private data class NrBand(val name: String, val arfcnLow: Long, val arfcnHigh: Long, val refMhz: Double, val step: Double)
    private val nr = listOf(
        NrBand("n1", 422_000, 434_000, 2110.0, 0.005),
        NrBand("n3", 361_000, 376_000, 1805.0, 0.005),
        NrBand("n7", 524_000, 538_000, 2620.0, 0.005),
        NrBand("n28", 151_600, 160_600, 758.0, 0.005),
        NrBand("n38", 514_000, 524_000, 2570.0, 0.005),
        NrBand("n40", 460_000, 480_000, 2300.0, 0.005),
        NrBand("n41", 499_200, 537_999, 2496.0, 0.015),
        NrBand("n77", 620_000, 680_000, 3300.0, 0.015),
        NrBand("n78", 620_000, 653_333, 3300.0, 0.015),
        NrBand("n79", 693_334, 733_333, 4400.0, 0.015)
    )

    fun lteEarfcnInfo(earfcn: Int): Triple<Int, Double, Int>? {
        val band = lte.firstOrNull { earfcn in it.dlLow..it.dlHigh } ?: return null
        val freq = band.fDlLowMhz + 0.1 * (earfcn - band.dlLow)
        return Triple(band.band, freq, estimateBandwidth(band.band))
    }
    fun nrArfcnInfo(nrarfcn: Long): Triple<String, Double, Double>? {
        val band = nr.firstOrNull { nrarfcn in it.arfcnLow..it.arfcnHigh } ?: return null
        val freq = band.refMhz + band.step * (nrarfcn - band.arfcnLow)
        return Triple(band.name, freq, 100.0)
    }
    private fun estimateBandwidth(band: Int): Int = when (band) {
        41, 42, 43 -> 20; 38, 40 -> 20; 7, 1, 3 -> 20; else -> 10
    }
}

// ----------------------------------------------------------------
// 15) Carrier Aggregation Engine
// ----------------------------------------------------------------
data class AdvCaCell(val cellId: String, val band: String, val bandwidth: Int, val isPrimary: Boolean)
class AdvCarrierAggregation {
    private val active = ConcurrentHashMap<String, AdvCaCell>()
    fun setPrimary(c: AdvCaCell) { active[c.cellId] = c.copy(isPrimary = true) }
    fun addSecondary(c: AdvCaCell) { active[c.cellId] = c.copy(isPrimary = false) }
    fun snapshot(): List<AdvCaCell> = active.values.toList()
    fun aggregatedBandwidth(): Int = active.values.sumOf { it.bandwidth }
    fun combination(): String = active.values.joinToString("+") { it.band }
}

// ----------------------------------------------------------------
// 16) Signal Quality Engine
// ----------------------------------------------------------------
object AdvSignalQuality {
    fun coverageScore(rsrp: Double): Double = ((rsrp + 140) / 65.0).coerceIn(0.0, 1.0)
    fun qualityScore(rsrq: Double): Double = ((rsrq + 20) / 17.0).coerceIn(0.0, 1.0)
    fun stabilityScore(stdRsrp: Double): Double = (1 - stdRsrp / 15.0).coerceIn(0.0, 1.0)
    fun reliabilityScore(samples: Int): Double = (samples / 100.0).coerceIn(0.0, 1.0)
    fun mobilityScore(handoverRate: Double): Double = (1 - handoverRate / 10.0).coerceIn(0.0, 1.0)
    fun grade(avg: Double): String = when {
        avg >= 0.85 -> "Excellent"; avg >= 0.7 -> "Good"
        avg >= 0.5 -> "Fair"; else -> "Poor"
    }
}

// ----------------------------------------------------------------
// 17) Confidence Engine
// ----------------------------------------------------------------
object AdvConfidence {
    fun score(samples: List<AdvSample>, rms: Double): Double {
        val n = samples.size
        val gpsAcc = samples.map { it.accuracy }.average()
        val taAvail = samples.count { it.ta > 0 }.toDouble() / n
        val signalStd = samples.map { it.rsrp }.let { stdDev(it.toDoubleArray()) }
        val movementDiv = samples.map { it.heading }.toDoubleArray().let { AdvCircularStats.circularVariance(it) }
        val nb = AdvSignalQuality.reliabilityScore(n)
        val gps = (1 - (gpsAcc / 50.0)).coerceIn(0.0, 1.0)
        val ta = taAvail
        val stab = AdvSignalQuality.stabilityScore(signalStd)
        val mov = movementDiv.coerceIn(0.0, 1.0)
        val rmsScore = (1 - (rms / 800.0)).coerceIn(0.0, 1.0)
        return (0.25 * nb + 0.20 * gps + 0.15 * ta + 0.15 * stab + 0.10 * mov + 0.15 * rmsScore)
            .coerceIn(0.0, 1.0)
    }
    fun stdDev(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val m = a.average()
        return sqrt(a.sumOf { (it - m).pow(2) } / a.size)
    }
}

// ----------------------------------------------------------------
// 18) Statistical Analysis Engine (with fixed iqrOutliers)
// ----------------------------------------------------------------
object AdvStats {
    fun mean(a: DoubleArray) = if (a.isEmpty()) 0.0 else a.average()
    fun median(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
    fun variance(a: DoubleArray): Double {
        if (a.size < 2) return 0.0
        val m = mean(a); return a.sumOf { (it - m).pow(2) } / (a.size - 1)
    }
    fun std(a: DoubleArray) = sqrt(variance(a))
    fun percentile(a: DoubleArray, p: Double): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray()
        val idx = ((s.size - 1) * (p / 100.0)).toInt().coerceIn(0, s.size - 1)
        return s[idx]
    }
    fun zScores(a: DoubleArray): DoubleArray {
        val m = mean(a); val sd = std(a).coerceAtLeast(1e-9)
        return DoubleArray(a.size) { (a[it] - m) / sd }
    }
    fun iqrOutliers(a: DoubleArray): List<Int> {
        if (a.size < 4) return emptyList()
        val q1 = percentile(a, 25.0); val q3 = percentile(a, 75.0)
        val iqr = q3 - q1; val lo = q1 - 1.5 * iqr; val hi = q3 + 1.5 * iqr
        val result = mutableListOf<Int>()
        for (i in a.indices) {
            val v = a[i]
            if (v < lo || v > hi) result.add(i)
        }
        return result
    }
    class MovingAverage(private val window: Int) {
        private val q = ArrayDeque<Double>(); private var sum = 0.0
        fun add(v: Double): Double {
            q.addLast(v); sum += v
            if (q.size > window) sum -= q.removeFirst()
            return sum / q.size
        }
    }
    class EMA(private val alpha: Double) {
        private var ema: Double = Double.NaN
        fun add(v: Double): Double {
            ema = if (ema.isNaN()) v else alpha * v + (1 - alpha) * ema
            return ema
        }
    }
}

// ----------------------------------------------------------------
// 19) Route Analysis Engine
// ----------------------------------------------------------------
data class AdvRoutePoint(val ts: Long, val lat: Double, val lon: Double, val cell: String?, val rsrp: Double)
class AdvRouteAnalyzer {
    private val points = ArrayList<AdvRoutePoint>()
    fun add(p: AdvRoutePoint) { points.add(p) }
    fun visitedCells(): List<String> = points.mapNotNull { it.cell }.distinct()
    fun coverageTimeline(): List<Pair<Long, Double>> = points.map { it.ts to it.rsrp }
    fun towerTimeline(): List<Pair<Long, String?>> = points.map { it.ts to it.cell }
    fun totalDistanceM(): Double {
        var d = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            d += AdvMultilateration.run {
                val r = 6371000.0
                val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
                val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
                2 * r * asin(sqrt(h))
            }
        }
        return d
    }
}

// ----------------------------------------------------------------
// 20) Offline Intelligence Database (in-memory cache)
// ----------------------------------------------------------------
class AdvOfflineIntelligence {
    val towerFingerprints = ConcurrentHashMap<String, AdvFingerprint>()
    val sectorFingerprints = ConcurrentHashMap<String, AdvFingerprint>()
    val operatorProfiles = ConcurrentHashMap<String, MutableMap<String, Any>>()
    val frequencyProfiles = ConcurrentHashMap<Int, MutableMap<String, Any>>()
    val countryProfiles = ConcurrentHashMap<Int, MutableMap<String, Any>>()
    val pciStats = ConcurrentHashMap<Int, Int>()
    val neighborGraph = AdvNeighborGraph()
    val coverageCache = ConcurrentHashMap<String, AdvCoverageGrid>()
    val historicalMeasurements = ConcurrentHashMap<String, MutableList<AdvSample>>()

    fun recordMeasurement(key: String, s: AdvSample) {
        historicalMeasurements.getOrPut(key) { mutableListOf() }.add(s)
        s.pci?.let { pciStats.merge(it, 1, Int::plus) }
        s.mcc?.let { mcc ->
            countryProfiles.getOrPut(mcc) { mutableMapOf() }["lastSeen"] = s.ts
        }
    }
}

// ----------------------------------------------------------------
// 21) Machine Learning Layer (logistic regression — quality classifier)
// ----------------------------------------------------------------
class AdvLogisticModel(private val nFeatures: Int) {
    private val w = DoubleArray(nFeatures)
    private var b = 0.0
    fun predict(x: DoubleArray): Double {
        var z = b; for (i in x.indices) z += w[i] * x[i]
        return 1.0 / (1.0 + exp(-z))
    }
    fun train(X: Array<DoubleArray>, y: DoubleArray, epochs: Int = 100, lr: Double = 0.05) {
        for (e in 0 until epochs) {
            for (i in X.indices) {
                val p = predict(X[i]); val err = p - y[i]
                for (j in 0 until nFeatures) w[j] -= lr * err * X[i][j]
                b -= lr * err
            }
        }
    }
}

class AdvQualityClassifier {
    private val model = AdvLogisticModel(4)
    fun featuresFromSample(s: AdvSample): DoubleArray =
        doubleArrayOf(s.rsrp, s.rsrq, s.rssnr, s.accuracy)
    fun trainFrom(samples: List<AdvSample>, label: (AdvSample) -> Double, epochs: Int = 100) {
        val X = samples.map { featuresFromSample(it) }.toTypedArray()
        val y = samples.map(label).toDoubleArray()
        if (X.isNotEmpty()) model.train(X, y, epochs)
    }
    fun classify(s: AdvSample): Double = model.predict(featuresFromSample(s))
}

// ----------------------------------------------------------------
// 22) Visualization Engine (layer descriptor — pure data, UI agnostic)
// ----------------------------------------------------------------
data class AdvLayer(val id: String, var visible: Boolean, val zIndex: Int, val type: String)
class AdvVisualizationEngine {
    val layers = mutableListOf(
        AdvLayer("towers", true, 10, "marker"),
        AdvLayer("confidenceCircles", true, 11, "circle"),
        AdvLayer("sectorWedges", true, 12, "polygon"),
        AdvLayer("heatmapRsrp", true, 5, "heatmap"),
        AdvLayer("heatmapRsrq", false, 5, "heatmap"),
        AdvLayer("heatmapSinr", false, 5, "heatmap"),
        AdvLayer("driveTestRoute", true, 7, "polyline"),
        AdvLayer("neighborGraph", false, 13, "graph")
    )
    fun toggle(id: String) { layers.firstOrNull { it.id == id }?.let { it.visible = !it.visible } }
    fun visibleLayers(): List<AdvLayer> = layers.filter { it.visible }.sortedBy { it.zIndex }

    fun colorForRsrp(rsrp: Double): Int = when {
        rsrp >= -85 -> 0xFF00C853.toInt()
        rsrp >= -95 -> 0xFFAEEA00.toInt()
        rsrp >= -105 -> 0xFFFFD600.toInt()
        rsrp >= -115 -> 0xFFFF6D00.toInt()
        else -> 0xFFD50000.toInt()
    }
    fun sectorWedgeVertices(lat: Double, lon: Double, azimuthDeg: Double, beamDeg: Double, radiusM: Double, steps: Int = 24): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat))
        pts.add(lat to lon)
        val half = beamDeg / 2.0
        for (i in 0..steps) {
            val a = Math.toRadians(azimuthDeg - half + (beamDeg * i / steps))
            pts.add(lat + (radiusM * cos(a)) / mPerLat to lon + (radiusM * sin(a)) / mPerLon)
        }
        pts.add(lat to lon)
        return pts
    }
}

// ================================================================
//   ADVANCED SUITE  — single entry-point that wires all 22 engines
// ================================================================
class AdvancedSuite(
    private val ctxOwner: Any?,
    private val pipeline: Any?
) {
    val geolocation = AdvTowerGeolocation
    val multilat = AdvMultilateration
    val kalmanGps = AdvKalmanGps()
    val kalmanRsrp = AdvKalman1D(q = 0.05, r = 4.0)
    val kalmanRsrq = AdvKalman1D(q = 0.05, r = 4.0)
    val kalmanSnr  = AdvKalman1D(q = 0.05, r = 4.0)
    val particles = ConcurrentHashMap<String, AdvParticleFilter>()
    val dbscan = AdvDbscan
    val circular = AdvCircularStats
    val propagation = AdvPropagation
    val coverage = AdvCoverageEngine
    val heatmap = AdvHeatmapEngine()
    val fingerprint = AdvFingerprintEngine
    val neighborGraph = AdvNeighborGraph()
    val mobility = AdvMobilityAnalyzer()
    val fakeCell = AdvFakeCellEngine
    val band = AdvBandDetector
    val ca = AdvCarrierAggregation()
    val quality = AdvSignalQuality
    val confidence = AdvConfidence
    val stats = AdvStats
    val route = AdvRouteAnalyzer()
    val intel = AdvOfflineIntelligence()
    val ml = AdvQualityClassifier()
    val visualization = AdvVisualizationEngine()

    private val cellBuckets = ConcurrentHashMap<String, MutableList<AdvSample>>()
    private val lastCellByDevice = ConcurrentHashMap<String, String>()
    private val _estimates = MutableStateFlow<Map<String, AdvEstimatedTower>>(emptyMap())
    val estimates: StateFlow<Map<String, AdvEstimatedTower>> = _estimates

    private fun cellKey(s: AdvSample): String =
        "${s.mcc ?: '-'}-${s.mnc ?: '-'}-${s.tac ?: '-'}-${s.cid ?: '-'}-${s.pci ?: '-'}-${s.earfcn ?: '-'}"

    suspend fun ingest(sample: AdvSample) = withContext(Dispatchers.Default) {
        val (lat, lon) = kalmanGps.update(sample.lat, sample.lon)
        val smoothed = sample.copy(
            lat = lat, lon = lon,
            rsrp = kalmanRsrp.update(sample.rsrp),
            rsrq = kalmanRsrq.update(sample.rsrq),
            rssnr = kalmanSnr.update(sample.rssnr)
        )
        val key = cellKey(smoothed)

        cellBuckets.getOrPut(key) { mutableListOf() }.add(smoothed)
        intel.recordMeasurement(key, smoothed)

        val deviceId = "device"
        lastCellByDevice[deviceId]?.let { prev ->
            if (prev != key) {
                neighborGraph.observe(prev, key)
                mobility.onHandover(prev, key, smoothed.ts, success = true)
            }
        }
        lastCellByDevice[deviceId] = key

        route.add(AdvRoutePoint(smoothed.ts, lat, lon, key, smoothed.rsrp))

        val samples = cellBuckets[key] ?: return@withContext
        if (samples.size in arrayOf(3, 5, 10, 20, 50, 100, 250, 500, 1000)) {
            geolocation.estimate(key, samples)?.let { est ->
                val pf = particles.getOrPut(key) { AdvParticleFilter().also { it.init(est.lat, est.lon, 1500.0) } }
                pf.step(samples.last(), 500.0)
                val (pfLat, pfLon) = pf.estimate()
                val merged = est.copy(
                    lat = (est.lat + pfLat) / 2.0,
                    lon = (est.lon + pfLon) / 2.0,
                    method = "WLS+PF"
                )
                _estimates.value = _estimates.value + (key to merged)
            }
        }
    }

    fun convertCellInfo(
        ts: Long, lat: Double, lon: Double, accuracy: Double, speed: Double, heading: Double,
        mcc: Int?, mnc: Int?, tac: Int?, cid: Long?, pci: Int?, earfcn: Int?,
        rsrp: Double, rsrq: Double, rssnr: Double, ta: Int, band: String?
    ): AdvSample = AdvSample(ts, lat, lon, accuracy, speed, heading, mcc, mnc, tac, cid, pci, earfcn,
        rsrp, rsrq, rssnr, ta, band)

    data class Snapshot(
        val estimatedTowers: Int,
        val avgConfidence: Double,
        val handoverRate: Double,
        val pingPong: Int,
        val routeDistanceM: Double,
        val activeLayers: Int,
        val caCombination: String,
        val caBandwidth: Int
    )

    fun snapshot(): Snapshot {
        val ests = _estimates.value.values
        return Snapshot(
            estimatedTowers = ests.size,
            avgConfidence = if (ests.isEmpty()) 0.0 else ests.sumOf { it.confidence } / ests.size,
            handoverRate = mobility.handoverRate(),
            pingPong = mobility.pingPong(),
            routeDistanceM = route.totalDistanceM(),
            activeLayers = visualization.visibleLayers().size,
            caCombination = ca.combination(),
            caBandwidth = ca.aggregatedBandwidth()
        )
    }
}

// ================================================================
//   END OF ADVANCED EXTENSION PACK v2
//   END OF PART 3/3
// ================================================================

// ================================================================
// INTEGRATION NOTES
// ================================================================
// 1) In MainActivity, after constructing CellularCellInfo from telephony
//    callbacks, call:
//        lifecycleScope.launch { pipeline.ingest(cellInfo) }
//    where `pipeline` is a single CellularPipeline(this) instance kept
//    in the activity.
//
// 2) Start / stop a session around active drive tests:
//        pipeline.startSession() ... pipeline.endSession()
//
// 3) The original UI is untouched. To add the new Dashboard / Heatmap /
//    Sector overlays, read snapshots from pipeline.dashboard and draw
//    them on top of the existing MapView using osmdroid overlays.
//
// 4) Exports live in ExportEngine — wire to existing menu items.
//
// 5) Everything in this file runs offline. Online tile sources remain
//    as in the original code; OfflineTileProvider can replace them when
//    an MBTiles file is provided.
// ================================================================
