package com.alinthit.pppoemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private data class Router(val id: Long, val name: String, val host: String, val port: Int, val user: String)
private data class PortInfo(val name: String, val running: Boolean, val rx: String, val tx: String)
private data class PppProfile(val name: String)
private data class PppUser(val name: String, val profile: String, val disabled: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ManagerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("router_list", android.content.Context.MODE_PRIVATE) }
    var routers by remember {
        mutableStateOf(runCatching {
            val a = JSONArray(prefs.getString("items", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let {
                Router(it.getLong("id"), it.getString("name"), it.getString("host"), it.getInt("port"), it.getString("user"))
            } }
        }.getOrDefault(emptyList()))
    }
    var selectedRouter by remember { mutableStateOf<Router?>(null) }
    var connected by remember { mutableStateOf<Router?>(null) }
    var apiPassword by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("MikroTik Router ကို ရွေးပြီး ချိတ်ဆက်ပါ") }
    var busy by remember { mutableStateOf(false) }
    var showAddRouter by remember { mutableStateOf(false) }
    var showAddUser by remember { mutableStateOf(false) }
    var ports by remember { mutableStateOf(emptyList<PortInfo>()) }
    var profiles by remember { mutableStateOf(emptyList<PppProfile>()) }
    var users by remember { mutableStateOf(emptyList<PppUser>()) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun saveRouters(list: List<Router>) {
        routers = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("user", it.user)) }
        prefs.edit().putString("items", a.toString()).apply()
    }

    fun refreshAll() {
        val r = connected ?: return
        val pass = apiPassword
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val p = RouterApi.interfaces(r.host, r.port, r.user, pass)
                    val pr = RouterApi.profiles(r.host, r.port, r.user, pass)
                    val u = RouterApi.secrets(r.host, r.port, r.user, pass)
                    Triple(p, pr, u)
                }
            }
            result.onSuccess { (p, pr, u) ->
                ports = p; profiles = pr; users = u
                status = "ချိတ်ဆက်ထားသည် — ${r.name}"
            }.onFailure { status = "ဖတ်မရပါ — ${it.message}" }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(38.dp).background(Color(0xFF123B70), CircleShape), contentAlignment = Alignment.Center) {
                        Text("PM", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    Column {
                        Text("PPPoE Manager MM")
                        Text("MikroTik RouterOS 7", style = MaterialTheme.typography.labelSmall)
                    }
                }
            })
        },
        floatingActionButton = {
            if (connected == null) FloatingActionButton(onClick = { showAddRouter = true }) { Text("+") }
            else FloatingActionButton(onClick = { showAddUser = true }) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(14.dp)) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            if (connected == null) {
                Text("သိမ်းထားသော Router များ", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(routers, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                Text("${r.host}:${r.port}  •  ${r.user}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { selectedRouter = r }) { Text("ချိတ်ဆက်ရန်") }
                                    OutlinedButton(onClick = { saveRouters(routers.filterNot { it.id == r.id }) }) { Text("ဖျက်ရန်") }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("LAN Ports") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("PPPoE Users") })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refreshAll() }, enabled = !busy) { Text("ပြန်စစ်မည်") }
                    OutlinedButton(onClick = { connected = null; apiPassword = ""; ports = emptyList(); users = emptyList(); profiles = emptyList(); status = "Router မှ ထွက်ပြီးပါပြီ" }) { Text("Disconnect") }
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)) }
                if (tab == 0) {
                    Text("Ethernet Port အခြေအနေ", style = MaterialTheme.typography.titleMedium)
                    Text("UP = Link တက်နေသည်၊ DOWN = Link မတက်ပါ။ Internet ရ/မရကို မဆိုလိုပါ။", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(ports, key = { it.name }) { p ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                                        Text("RX ${p.rx}  •  TX ${p.tx}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(if (p.running) "UP" else "DOWN", color = if (p.running) Color(0xFF16834A) else Color(0xFFB3261E), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PPPoE အကောင့်များ (${users.size})", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showAddUser = true }) { Text("အသစ်ထည့် +") }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(users, key = { it.name }) { u ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(u.name, style = MaterialTheme.typography.titleMedium)
                                    Text("Profile: ${u.profile}")
                                    Text(if (u.disabled) "ပိတ်ထားသည်" else "ဖွင့်ထားသည်", color = if (u.disabled) Color(0xFFB3261E) else Color(0xFF16834A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRouter) {
        var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("8728") }; var user by remember { mutableStateOf("apiuser") }
        AlertDialog(onDismissRequest = { showAddRouter = false }, title = { Text("Router အသစ်ထည့်ရန်") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Router အမည်") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("IP Address") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("API Port (8728)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("API Username") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = {
                val n = port.toIntOrNull()
                if (name.isNotBlank() && host.isNotBlank() && n != null && n in 1..65535 && user.isNotBlank()) {
                    saveRouters(routers + Router(System.currentTimeMillis(), name.trim(), host.trim(), n, user.trim()))
                    showAddRouter = false
                }
            }) { Text("သိမ်းမည်") } },
            dismissButton = { TextButton(onClick = { showAddRouter = false }) { Text("ပိတ်မည်") } })
    }

    selectedRouter?.let { r ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { selectedRouter = null }, title = { Text("${r.name} ချိတ်ဆက်ရန်") },
            text = { OutlinedTextField(pass, { pass = it }, label = { Text("API Password") }, singleLine = true) },
            confirmButton = { TextButton(enabled = pass.isNotBlank() && !busy, onClick = {
                val secret = pass; selectedRouter = null; busy = true; status = "Router ချိတ်ဆက်နေသည်..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) { RouterApi.identity(r.host, r.port, r.user, secret) }
                    result.onSuccess {
                        connected = r; apiPassword = secret; status = "ချိတ်ဆက်အောင်မြင် — $it"; tab = 0
                    }.onFailure { status = "ချိတ်ဆက်မအောင်မြင် — ${it.message}" }
                    busy = false
                    if (connected != null) refreshAll()
                }
            }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { selectedRouter = null }) { Text("မလုပ်တော့ပါ") } })
    }

    if (showAddUser && connected != null) {
        var uname by remember { mutableStateOf("") }; var pword by remember { mutableStateOf("") }
        var profile by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddUser = false }, title = { Text("PPPoE အကောင့်အသစ်") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(uname, { uname = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pword, { pword = it }, label = { Text("Password") }, singleLine = true)
                if (profiles.isEmpty()) Text("Profile မတွေ့ပါ။ Router ထဲတွင် PPP Profile ရှိ/မရှိ စစ်ပါ။")
                else {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(profile, { }, readOnly = true, label = { Text("PPP Profile") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            profiles.forEach { item ->
                                DropdownMenuItem(text = { Text(item.name) }, onClick = { profile = item.name; expanded = false })
                            }
                        }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = uname.isNotBlank() && pword.isNotBlank() && profile.isNotBlank() && !busy,
                onClick = {
                    val r = connected!!; val pass = apiPassword
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RouterApi.addSecret(r.host, r.port, r.user, pass, uname.trim(), pword, profile) }
                        result.onSuccess { status = "PPPoE အကောင့် ${uname.trim()} ဖန်တီးပြီးပါပြီ"; showAddUser = false; refreshAll() }
                            .onFailure { status = "အကောင့်ဖန်တီးမရပါ — ${it.message}" }
                        busy = false
                    }
                }) { Text("ဖန်တီးမည်") } },
            dismissButton = { TextButton(onClick = { showAddUser = false }) { Text("ပိတ်မည်") } })
    }
}

private object RouterApi {
    private fun call(host: String, port: Int, username: String, password: String, command: List<String>): List<List<String>> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 6000); socket.soTimeout = 6000
            val input = BufferedInputStream(socket.getInputStream()); val output = BufferedOutputStream(socket.getOutputStream())
            send(output, listOf("/login", "=name=$username", "=password=$password"))
            var ok = false
            var attempts = 0
            while (!ok && attempts++ < 10) {
                val s = read(input)
                if (s.any { it.startsWith("!trap") }) error(s.joinToString(" "))
                if (s.any { it == "!done" }) ok = true
            }
            if (!ok) error("API login မအောင်မြင်ပါ")
            send(output, command)
            val rows = mutableListOf<List<String>>()
            var done = false
            repeat(1000) {
                if (done) return@repeat
                val s = read(input)
                if (s.any { it.startsWith("!re") }) rows += s
                if (s.any { it.startsWith("!trap") }) error(s.joinToString(" "))
                if (s.any { it == "!done" }) done = true
            }
            if (!done) error("Router response timeout")
            return rows
        }
    }
    fun identity(h:String,p:Int,u:String,pw:String): Result<String> = runCatching {
        val rows = call(h,p,u,pw,listOf("/system/identity/print"))
        rows.firstOrNull()?.firstOrNull { it.startsWith("=name=") }?.substringAfter("=name=") ?: "MikroTik"
    }
    fun interfaces(h:String,p:Int,u:String,pw:String): List<PortInfo> = call(h,p,u,pw,listOf("/interface/ethernet/print","=.proplist=name,running,rx-byte,tx-byte")).mapNotNull { row ->
        fun v(k:String)=row.firstOrNull { it.startsWith("=$k=") }?.substringAfter("=$k=") ?: ""
        val name=v("name")
        if (name.isBlank()) null else PortInfo(name, v("running")=="true", prettyBytes(v("rx-byte")), prettyBytes(v("tx-byte")))
    }
    fun profiles(h:String,p:Int,u:String,pw:String): List<PppProfile> = call(h,p,u,pw,listOf("/ppp/profile/print","=.proplist=name")).mapNotNull { row ->
        row.firstOrNull { it.startsWith("=name=") }?.substringAfter("=name=")?.let { PppProfile(it) }
    }
    fun secrets(h:String,p:Int,u:String,pw:String): List<PppUser> = call(h,p,u,pw,listOf("/ppp/secret/print","=.proplist=name,profile,disabled")).mapNotNull { row ->
        fun v(k:String)=row.firstOrNull { it.startsWith("=$k=") }?.substringAfter("=$k=") ?: ""
        v("name").takeIf { it.isNotBlank() }?.let { PppUser(it, v("profile"), v("disabled")=="true") }
    }
    fun addSecret(h:String,p:Int,u:String,pw:String,name:String,secret:String,profile:String): Result<Unit> = runCatching {
        call(h,p,u,pw,listOf("/ppp/secret/add","=name=$name","=password=$secret","=service=pppoe","=profile=$profile"))
        Unit
    }
    private fun prettyBytes(s:String):String = s.toLongOrNull()?.let { n ->
        when { n >= 1073741824 -> "%.2f GB".format(n/1073741824.0); n >= 1048576 -> "%.1f MB".format(n/1048576.0); n >= 1024 -> "%.1f KB".format(n/1024.0); else -> "$n B" }
    } ?: "—"
    private fun send(out:BufferedOutputStream, words:List<String>) {
        words.forEach { val b=it.toByteArray(Charsets.UTF_8); length(out,b.size); out.write(b) }; out.write(0); out.flush()
    }
    private fun length(out:BufferedOutputStream,n:Int) {
        when { n<0x80 -> out.write(n); n<0x4000 -> { out.write((n shr 8) or 0x80); out.write(n) }; n<0x200000 -> { out.write((n shr 16) or 0xC0); out.write(n shr 8); out.write(n) }; n<0x10000000 -> { out.write((n shr 24) or 0xE0); out.write(n shr 16); out.write(n shr 8); out.write(n) }; else -> { out.write(0xF0); out.write(n shr 24); out.write(n shr 16); out.write(n shr 8); out.write(n) } }
    }
    private fun readByte(i:BufferedInputStream):Int = i.read().also { if(it<0) error("Socket ပိတ်သွားသည်") }
    private fun readLength(i:BufferedInputStream):Int {
        val c=readByte(i)
        return when { c and 0x80==0 -> c; c and 0xC0==0x80 -> ((c and 0x3F) shl 8) or readByte(i); c and 0xE0==0xC0 -> ((c and 0x1F) shl 16) or (readByte(i) shl 8) or readByte(i); c and 0xF0==0xE0 -> ((c and 0x0F) shl 24) or (readByte(i) shl 16) or (readByte(i) shl 8) or readByte(i); else -> error("API packet length မမှန်ပါ") }
    }
    private fun read(i:BufferedInputStream):List<String> {
        val words=mutableListOf<String>()
        while(true) { val n=readLength(i); if(n==0) return words; val b=ByteArray(n); var off=0
            while(off<n) { val got=i.read(b,off,n-off); if(got<0) error("Response မပြည့်စုံပါ"); off+=got }
            words += String(b,Charsets.UTF_8)
        }
    }
}
