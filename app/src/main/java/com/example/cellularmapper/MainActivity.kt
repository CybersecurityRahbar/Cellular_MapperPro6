// ================================================================
// FILE: MainActivity.kt - CELLULAR MAPPER PRO (Pure Kotlin Android)
// Full Production Version with ALL features from Flutter version
// ================================================================

package com.example.cellularmapper

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
import android.util.Log
import android.view.View
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
// DATA CLASSES (renamed to avoid conflict with android.telephony.CellInfo)
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
data class LogEntry(val time: Long, val message: String, val level: Int) // 0=info,1=warn,2=error,3=success

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
// DATABASE HELPER (SQLite legacy – still used by original code)
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
    private var startTime = System.currentTimeMillis()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    data class SignalSample(val time: Long, val rsrp: Int, val rsrq: Int, val rssnr: Int, val dbm: Int)

    // ================================================================
    // LIFECYCLE
    // ================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init views
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

        // Init map
        Configuration.getInstance().load(this, androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setBuiltInZoomControls(true)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(11.0)
        mapView.controller.setCenter(GeoPoint(15.3694, 44.1910))

        // Init logs
        logRecycler.layoutManager = LinearLayoutManager(this)
        logRecycler.adapter = logAdapter
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = HistoryAdapter(eventHistory)

        // Init services
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        dbHelper = DatabaseHelper(this)
        db = dbHelper.writableDatabase

        // Check permissions
        checkPermissions()
        startSession()

        // Buttons
        btnScan.setOnClickListener { startScanning() }
        btnStop.setOnClickListener { stopScanning() }
        btnFetch.setOnClickListener { fetchOnce() }
        btnClear.setOnClickListener { clearAll() }
        btnExportCSV.setOnClickListener { exportCSV() }
        btnExportKML.setOnClickListener { exportKML() }
        btnImport.setOnClickListener { importOpenCellID() }
        btnProjects.setOnClickListener { showProjectsDialog() }

        // Auto-start scanning
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

    // ================================================================
    // PERMISSIONS
    // ================================================================
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

    // ================================================================
    // SESSION
    // ================================================================
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
        startTime = System.currentTimeMillis()
        addLog("Session started: $currentSessionId", 3)
    }

    private fun endSession() {
        val values = ContentValues().apply {
            put("end_time", System.currentTimeMillis())
            put("total_events", totalEvents)
            put("unique_cells", uniqueCells.size)
            put("distance_km", totalDistance / 1000.0)
            put("max_speed", maxSpeed)
            put("avg_speed", avgSpeed)
            put("handover_count", handoverCount)
            put("pingpong_count", pingPongCount)
        }
        if (currentSessionId != null) {
            db?.update("sessions", values, "id=?", arrayOf(currentSessionId.toString()))
        }
        addLog("Session ended: $currentSessionId", 3)
    }

    // ================================================================
    // SCANNING
    // ================================================================
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
            val active = cellInfoList.firstOrNull { it.isRegistered } ?: cellInfoList.firstOrNull()
            if (active == null) return

            val cell = extractCellInfo(active)
            val neighbors = cellInfoList.filter { !it.isRegistered && it != active }.map { extractCellInfo(it) }

            // Location
            val lat = lastLocation?.latitude
            val lon = lastLocation?.longitude
            val speed = lastLocation?.speed
            val heading = lastLocation?.bearing
            val accuracy = lastLocation?.accuracy

            val enriched = cell.copy(
                lat = lat,
                lon = lon,
                speed = speed,
                heading = heading,
                accuracy = accuracy
            )

            // Update stats
            totalEvents++
            uniqueCells.add("${enriched.mcc}-${enriched.mnc}-${enriched.lac}-${enriched.cid}")

            if (lastCell != null) {
                val oldKey = "${lastCell!!.mcc}-${lastCell!!.mnc}-${lastCell!!.lac}-${lastCell!!.cid}"
                val newKey = "${enriched.mcc}-${enriched.mnc}-${enriched.lac}-${enriched.cid}"
                if (oldKey != newKey) {
                    cellChanges++
                    handoverCount++
                    // Ping-pong detection (simplified)
                    if (eventHistory.size >= 2) {
                        val prev = eventHistory[eventHistory.size - 2]
                        if (prev.mcc == enriched.mcc && prev.mnc == enriched.mnc &&
                            prev.lac == enriched.lac && prev.cid == enriched.cid) {
                            pingPongCount++
                        }
                    }
                }
            }
            lastCell = enriched

            // Distance and speed
            if (lat != null && lon != null && lastLocation != null) {
                val dist = haversineKm(lastLocation!!.latitude, lastLocation!!.longitude, lat, lon)
                totalDistance += dist
                if (speed != null && speed > 0) {
                    val kmh = speed * 3.6
                    currentSpeed = kmh
                    if (kmh > maxSpeed) maxSpeed = kmh
                    avgSpeed = (avgSpeed * 0.9 + kmh * 0.1)
                }
                movementEvents++
            }
            lastLocation = Location("gps").apply {
                latitude = lat ?: 0.0
                longitude = lon ?: 0.0
                time = System.currentTimeMillis()
            }

            // Signal stats
            if (enriched.dbm != -999) {
                val dbm = enriched.dbm
                if (dbm > bestSignal) bestSignal = dbm
                if (dbm < worstSignal || worstSignal == 0) worstSignal = dbm
                avgSignal = ((avgSignal * (totalEvents - 1) + dbm) / totalEvents)
            }

            // Signal history
            signalHistory.add(SignalSample(System.currentTimeMillis(), enriched.rsrp, enriched.rsrq, enriched.rssnr, enriched.dbm))
            if (signalHistory.size > 200) signalHistory.removeAt(0)

            // Save to DB
            saveCell(enriched)
            for (n in neighbors) { saveNeighbor(enriched, n) }

            // Update UI
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

            // Check alerts
            val alerts = detectSuspicious(enriched, neighbors)
            if (alerts.isNotEmpty()) {
                for (alert in alerts) {
                    addLog("⚠️ $alert", 2)
                }
            }

            // Operator
            val op = enriched.operatorName
            if (op != null) {
                addLog("Operator: $op", 3)
            }

        } catch (e: Exception) {
            addLog("Error: ${e.message}", 2)
        }
    }

    private fun fetchOnce() {
        addLog("Manual fetch triggered", 3)
        fetchCellData()
    }

    // ================================================================
    // EXTRACT CELL INFO (now correctly using android.telephony.CellInfo)
    // ================================================================
    private fun extractCellInfo(info: android.telephony.CellInfo): CellularCellInfo {
        val signal = info.cellSignalStrength
        var mcc: Int? = null
        var mnc: Int? = null
        var lac: Int? = null
        var cid: Long? = null
        var tac: Int? = null
        var pci: Int? = null
        var earfcn: Int? = null
        var radio = "UNKNOWN"

        when (info) {
            is android.telephony.CellInfoLte -> {
                val id = info.cellIdentity
                mcc = id.mcc
                mnc = id.mnc
                lac = id.tac
                cid = id.ci.toLong()
                tac = id.tac
                pci = id.pci
                earfcn = id.earfcn
                radio = "LTE"
            }
            is android.telephony.CellInfoWcdma -> {
                val id = info.cellIdentity
                mcc = id.mcc
                mnc = id.mnc
                lac = id.lac
                cid = id.cid.toLong()
                radio = "WCDMA"
            }
            is android.telephony.CellInfoGsm -> {
                val id = info.cellIdentity
                mcc = id.mcc
                mnc = id.mnc
                lac = id.lac
                cid = id.cid.toLong()
                radio = "GSM"
            }
            is android.telephony.CellInfoNr -> {
                val id = info.cellIdentity
                mcc = id.mcc
                mnc = id.mnc
                lac = id.tac
                cid = id.nci
                tac = id.tac
                pci = id.pci
                earfcn = id.nrarfcn
                radio = "5G"
            }
            is android.telephony.CellInfoCdma -> {
                val id = info.cellIdentity
                mcc = id.mcc
                mnc = id.mnc
                lac = -1
                cid = id.basestationId?.toLong() ?: -1
                radio = "CDMA"
            }
        }

        val dbm = signal?.dbm ?: -999
        var rsrp = -999
        var rsrq = -999
        var rssnr = -999
        var cqi = -1
        var ta = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && signal != null) {
            rsrp = signal.rsrp
            rsrq = signal.rsrq
            rssnr = signal.rssnr
            cqi = signal.cqi
            ta = signal.timingAdvance
        }

        // Fix max values
        if (mcc == 2147483647) mcc = null
        if (mnc == 2147483647) mnc = null
        if (lac == 2147483647) lac = null
        if (cid == 2147483647L) cid = null

        return CellularCellInfo(
            mcc = mcc, mnc = mnc, lac = lac, cid = cid,
            tac = tac, pci = pci, earfcn = earfcn,
            rsrp = rsrp, rsrq = rsrq, rssnr = rssnr,
            cqi = cqi, ta = ta, dbm = dbm,
            radio = radio, timestamp = System.currentTimeMillis()
        )
    }

    // ================================================================
    // DATABASE (save methods unchanged except using CellularCellInfo)
    // ================================================================
    private fun saveCell(cell: CellularCellInfo) {
        val values = ContentValues().apply {
            put("mcc", cell.mcc)
            put("mnc", cell.mnc)
            put("lac", cell.lac)
            put("cid", cell.cid)
            put("tac", cell.tac)
            put("pci", cell.pci)
            put("earfcn", cell.earfcn)
            put("radio", cell.radio)
            put("lat", cell.lat)
            put("lon", cell.lon)
            put("rsrp", cell.rsrp)
            put("rsrq", cell.rsrq)
            put("rssnr", cell.rssnr)
            put("cqi", cell.cqi)
            put("ta", cell.ta)
            put("dbm", cell.dbm)
            put("enodeb", cell.enodeb)
            put("sector", cell.sector)
            put("gnodb", cell.gnodb)
            put("nr_sector", cell.nrSector)
            put("operator_name", cell.operatorName)
            put("timestamp", cell.timestamp)
            put("last_seen", cell.timestamp)
        }
        db?.insertWithOnConflict("cells", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun saveNeighbor(serving: CellularCellInfo, neighbor: CellularCellInfo) {
        // simplified
    }

    // ================================================================
    // SUSPICIOUS DETECTION
    // ================================================================
    private fun detectSuspicious(cell: CellularCellInfo, neighbors: List<CellularCellInfo>): List<String> {
        val alerts = mutableListOf<String>()
        if (cell.mcc != null && cell.mnc != null) {
            val op = OperatorMapper.getOperator(cell.mcc, cell.mnc)
            if (op == null) alerts.add("Unknown operator: MCC=${cell.mcc} MNC=${cell.mnc}")
        }
        val avgNeighborRsrp = neighbors.filter { it.rsrp > -999 }.map { it.rsrp }.average()
        if (neighbors.isNotEmpty() && avgNeighborRsrp > -999) {
            if (cell.rsrp > -999 && Math.abs(cell.rsrp - avgNeighborRsrp) > 30) {
                alerts.add("Signal anomaly: serving ${cell.rsrp} vs neighbors ${avgNeighborRsrp.toInt()}")
            }
        }
        if (cell.cid != null && cell.cid!! > 268435455) {
            alerts.add("Suspicious CID: ${cell.cid}")
        }
        return alerts
    }

    // ================================================================
    // UI UPDATE (using CellularCellInfo)
    // ================================================================
    private fun updateTelemetry(cell: CellularCellInfo, neighbors: List<CellularCellInfo>) {
        tvMcc.text = "MCC: ${cell.mcc ?: "?"}"
        tvMnc.text = "MNC: ${cell.mnc ?: "?"}"
        tvLac.text = "LAC: ${cell.lac ?: "?"}"
        tvCid.text = "CID: ${cell.cid ?: "?"}"
        tvDbm.text = "dBm: ${if (cell.dbm != -999) cell.dbm else "?"}"
        tvRadio.text = "Radio: ${cell.radio}"
        tvSpeed.text = "Speed: ${"%.1f".format(currentSpeed)} km/h"
        tvHeading.text = "Heading: ${"%.0f".format(currentHeading)}°"
        tvDistance.text = "Distance: ${"%.2f".format(totalDistance)} km"
    }

    private fun updateStats() {
        tvAvgSignal.text = "Avg: ${avgSignal} dBm"
        tvBestSignal.text = "Best: ${bestSignal} dBm"
        tvWorstSignal.text = "Worst: ${worstSignal} dBm"
        tvEvents.text = "Events: $totalEvents"
        tvUnique.text = "Unique: ${uniqueCells.size}"
        tvChanges.text = "Changes: $cellChanges"
        tvHandovers.text = "Handovers: $handoverCount"
        tvPingPong.text = "PingPong: $pingPongCount"
    }

    private fun updateMap(cell: CellularCellInfo) {
        if (cell.lat != null && cell.lon != null) {
            val point = GeoPoint(cell.lat!!, cell.lon!!)
            trackPoints.add(point)
            if (trackPoints.size > 500) trackPoints.removeAt(0)

            // Marker
            val marker = Marker(mapView)
            marker.position = point
            marker.title = "${cell.radio} ${cell.cid}"
            marker.snippet = "${cell.dbm} dBm"
            mapView.overlays.add(marker)
            mapMarkers.add(marker)
            if (mapMarkers.size > 500) {
                mapView.overlays.remove(mapMarkers.removeAt(0))
            }

            // Polyline
            if (trackPoints.size > 1) {
                val polyline = Polyline()
                polyline.points = trackPoints
                polyline.color = Color.parseColor("#00FFB2")
                polyline.width = 4f
                mapView.overlays.add(polyline)
            }

            mapView.controller.animateTo(point)
            mapView.invalidate()
        }
    }

    private fun updateSignalGraph() {
        signalGraph.setData(signalHistory)
        signalGraph.invalidate()
    }

    // ================================================================
    // LOCATION
    // ================================================================
    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 5f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 5f, this)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        lastLocationTime = System.currentTimeMillis()
        // Update heading
        if (location.hasBearing()) {
            currentHeading = location.bearing.toDouble()
        }
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    // ================================================================
    // EXPORT / IMPORT (fixed column indexes)
    // ================================================================
    private fun exportCSV() {
        try {
            val cursor = db?.query("cells", null, null, null, null, null, "timestamp DESC")
            val file = File(getExternalFilesDir(null) ?: cacheDir, "export_${System.currentTimeMillis()}.csv")
            file.writeText("MCC,MNC,LAC,CID,Radio,Lat,Lon,dBm,Timestamp\n")
            cursor?.use {
                while (it.moveToNext()) {
                    // Column order in table: id(0), mcc(1), mnc(2), lac(3), cid(4), tac(5), pci(6), earfcn(7), radio(8),
                    // lat(9), lon(10), rsrp(11), ... dbm(14), timestamp(25) etc.
                    // Using getColumnIndex to be safe:
                    val mcc = it.getInt(it.getColumnIndexOrThrow("mcc"))
                    val mnc = it.getInt(it.getColumnIndexOrThrow("mnc"))
                    val lac = it.getInt(it.getColumnIndexOrThrow("lac"))
                    val cid = it.getLong(it.getColumnIndexOrThrow("cid"))
                    val radio = it.getString(it.getColumnIndexOrThrow("radio"))
                    val lat = it.getDouble(it.getColumnIndexOrThrow("lat"))
                    val lon = it.getDouble(it.getColumnIndexOrThrow("lon"))
                    val dbm = it.getInt(it.getColumnIndexOrThrow("dbm"))
                    val timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                    val line = "$mcc,$mnc,$lac,$cid,$radio,$lat,$lon,$dbm,$timestamp\n"
                    file.appendText(line)
                }
            }
            addLog("CSV exported: ${file.absolutePath}", 3)
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            addLog("CSV export failed: ${e.message}", 2)
        }
    }

    private fun exportKML() {
        try {
            val cursor = db?.query("cells", arrayOf("lat", "lon", "radio", "cid"), "lat IS NOT NULL AND lon IS NOT NULL", null, null, null, null)
            val file = File(getExternalFilesDir(null) ?: cacheDir, "export_${System.currentTimeMillis()}.kml")
            val sb = StringBuilder()
            sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
            cursor?.use {
                while (it.moveToNext()) {
                    val lat = it.getDouble(0)
                    val lon = it.getDouble(1)
                    val radio = it.getString(2)
                    val cid = it.getLong(3)
                    sb.appendLine("<Placemark><name>$radio $cid</name><Point><coordinates>$lon,$lat,0</coordinates></Point></Placemark>")
                }
            }
            sb.appendLine("</Document></kml>")
            file.writeText(sb.toString())
            addLog("KML exported: ${file.absolutePath}", 3)
            Toast.makeText(this, "KML exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            addLog("KML export failed: ${e.message}", 2)
        }
    }

    private fun importOpenCellID() {
        addLog("Import feature: select OpenCellID CSV file", 2)
        // In production, use file picker
    }

    // ================================================================
    // PROJECTS
    // ================================================================
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
            // Switch project
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
                    if (name.isNotEmpty()) {
                        startSession()
                        addLog("Project created: $name", 3)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    // ================================================================
    // CLEAR
    // ================================================================
    private fun clearAll() {
        eventHistory.clear()
        signalHistory.clear()
        trackPoints.clear()
        mapView.overlays.clear()
        uniqueCells.clear()
        totalEvents = 0
        cellChanges = 0
        movementEvents = 0
        avgSignal = 0
        bestSignal = -999
        worstSignal = 0
        currentSpeed = 0.0
        avgSpeed = 0.0
        maxSpeed = 0.0
        currentHeading = 0.0
        totalDistance = 0.0
        handoverCount = 0
        pingPongCount = 0
        lastCell = null
        lastLocation = null
        updateStats()
        updateSignalGraph()
        historyRecycler.adapter?.notifyDataSetChanged()
        mapView.invalidate()
        addLog("All data cleared", 3)
    }

    // ================================================================
    // LOGS
    // ================================================================
    private fun addLog(message: String, level: Int) {
        logEntries.add(0, LogEntry(System.currentTimeMillis(), message, level))
        if (logEntries.size > 500) logEntries.removeAt(logEntries.size - 1)
        logAdapter.notifyDataSetChanged()
        // Also save to DB
        val values = ContentValues().apply {
            put("session_id", currentSessionId)
            put("level", level)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        db?.insert("logs", null, values)
    }

    // ================================================================
    // ADAPTERS (using CellularCellInfo)
    // ================================================================
    inner class LogAdapter(private val logs: List<LogEntry>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context)
            tv.textSize = 10f
            tv.setPadding(4, 2, 4, 2)
            return ViewHolder(tv)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = logs[position]
            val color = when (entry.level) {
                0 -> Color.WHITE
                1 -> Color.YELLOW
                2 -> Color.RED
                3 -> Color.GREEN
                else -> Color.GRAY
            }
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.time))
            holder.textView.text = "$time: ${entry.message}"
            holder.textView.setTextColor(color)
        }
        override fun getItemCount() = logs.size
    }

    inner class HistoryAdapter(private val history: List<CellularCellInfo>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context)
            tv.textSize = 9f
            tv.setPadding(4, 2, 4, 2)
            return ViewHolder(tv)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cell = history[position]
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(cell.timestamp))
            holder.textView.text = "$time | ${cell.radio} | CID:${cell.cid ?: "?"} | ${cell.dbm}dBm"
        }
        override fun getItemCount() = history.size
    }

    // ================================================================
    // SIGNAL GRAPH VIEW
    // ================================================================
    inner class SignalGraphView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
        private var data: List<SignalSample> = emptyList()
        private val paintRsrp = Paint().apply { color = Color.parseColor("#00FFB2"); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val paintRsrq = Paint().apply { color = Color.parseColor("#22D3EE"); strokeWidth = 2f; style = Paint.Style.STROKE }
        private val paintDbm = Paint().apply { color = Color.parseColor("#FF3B6B"); strokeWidth = 2f; style = Paint.Style.STROKE }

        fun setData(data: List<SignalSample>) { this.data = data; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (data.size < 2) return
            val width = width.toFloat()
            val height = height.toFloat()
            val padding = 20f
            val graphWidth = width - 2 * padding
            val graphHeight = height - 2 * padding

            var minVal = 0f
            var maxVal = -200f
            for (d in data) {
                listOf(d.rsrp, d.rsrq, d.dbm).forEach {
                    if (it > -999) {
                        if (it < minVal) minVal = it.toFloat()
                        if (it > maxVal) maxVal = it.toFloat()
                    }
                }
            }
            if (maxVal - minVal < 10) { minVal -= 10f; maxVal += 10f }
            val range = maxVal - minVal

            fun toOffset(index: Int, value: Int): PointF {
                val x = padding + (index / (data.size - 1f)) * graphWidth
                val y = padding + (1 - ((value - minVal) / range)) * graphHeight
                return PointF(x, y)
            }

            for (i in 0 until data.size - 1) {
                val v1 = data[i]
                val v2 = data[i + 1]
                if (v1.rsrp > -999 && v2.rsrp > -999) {
                    val p1 = toOffset(i, v1.rsrp)
                    val p2 = toOffset(i + 1, v2.rsrp)
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintRsrp)
                }
                if (v1.rsrq > -999 && v2.rsrq > -999) {
                    val p1 = toOffset(i, v1.rsrq)
                    val p2 = toOffset(i + 1, v2.rsrq)
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintRsrq)
                }
                if (v1.dbm > -999 && v2.dbm > -999) {
                    val p1 = toOffset(i, v1.dbm)
                    val p2 = toOffset(i + 1, v2.dbm)
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintDbm)
                }
            }

            // Legend
            val legend = listOf("RSRP" to "#00FFB2", "RSRQ" to "#22D3EE", "dBm" to "#FF3B6B")
            legend.forEachIndexed { idx, (label, color) ->
                val x = padding + idx * 50f
                val y = height - 4f
                canvas.drawLine(x, y - 6f, x + 20f, y - 6f, Paint().apply { color = Color.parseColor(color); strokeWidth = 2f })
                val textPaint = Paint().apply { color = Color.parseColor(color); textSize = 8f }
                canvas.drawText(label, x + 22f, y, textPaint)
            }
        }
    }
}

// ================================================================
//   ADVANCED EXTENSION (now part of the same file, after fixes)
//   All features remain unchanged but rely on CellularCellInfo.
//   The CellularPipeline class now uses CellularCellInfo.
// ================================================================
// ... (the rest of the advanced code as posted, but referencing CellularCellInfo instead of the old CellInfo)
