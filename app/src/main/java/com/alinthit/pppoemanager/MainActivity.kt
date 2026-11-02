package com.alinthit.pppoemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

private data class Router(val id: Long, val name: String, val host: String, val port: Int, val user: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RouterManager() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterManager() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("router_list", android.content.Context.MODE_PRIVATE) }
    var routers by remember {
        mutableStateOf(runCatching {
            val arr = JSONArray(prefs.getString("items", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Router(o.getLong("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("user"))
            }
        }.getOrDefault(emptyList()))
    }
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Router?>(null) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Router မချိတ်ရသေးပါ") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save(items: List<Router>) {
        routers = items
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("name", r.name).put("host", r.host)
                .put("port", r.port).put("user", r.user))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("PPPoE Manager MM") }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("သိမ်းထားသော MikroTik Router များ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Router အရေအတွက်: ${routers.size}")
            Text(status, color = if (status.startsWith("ချိတ်ဆက်အောင်မြင်")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(routers, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text("${r.host}:${r.port}  •  ${r.user}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { selected = r; password = "" }) { Text("ချိတ်ဆက်ရန်") }
                                OutlinedButton(onClick = { save(routers.filterNot { it.id == r.id }); status = "Router ဖျက်ပြီးပါပြီ" }) { Text("ဖျက်ရန်") }
                            }
                        }
                    }
                }
            }
            if (busy) { CircularProgressIndicator(); Text("Router ကို စစ်ဆေးနေသည်...") }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("8728") }
        var user by remember { mutableStateOf("apiuser") }
        AlertDialog(onDismissRequest = { showAdd = false },
            title = { Text("Router အသစ်ထည့်ရန်") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Router အမည်") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("IP Address") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("API Port (ပုံမှန် 8728)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("API Username") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = {
                val p = port.toIntOrNull()
                if (name.isNotBlank() && host.isNotBlank() && p != null && p in 1..65535 && user.isNotBlank()) {
                    save(routers + Router(System.currentTimeMillis(), name.trim(), host.trim(), p, user.trim()))
                    showAdd = false
                }
            }) { Text("သိမ်းမည်") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("မလုပ်တော့ပါ") } })
    }

    selected?.let { r ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text("${r.name} ချိတ်ဆက်ရန်") },
            text = { OutlinedTextField(password, { password = it }, label = { Text("Router API Password") }, singleLine = true) },
            confirmButton = { TextButton(enabled = password.isNotBlank() && !busy, onClick = {
                val pass = password
                selected = null
                busy = true
                status = "${r.name} ကို ချိတ်ဆက်နေသည်..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) { RouterApi.identity(r.host, r.port, r.user, pass) }
                    busy = false
                    status = result.fold({ "ချိတ်ဆက်အောင်မြင်ပါသည် — Router: $it" }, { "ချိတ်ဆက်မအောင်မြင်ပါ — ${it.message ?: "စစ်ဆေးပါ"}" })
                    password = ""
                }
            }) { Text("Connect စမ်းမည်") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("ပိတ်မည်") } })
    }
}

private object RouterApi {
    fun identity(host: String, port: Int, username: String, password: String): Result<String> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            sendSentence(output, listOf("/login", "=name=$username", "=password=$password"))
            var loggedIn = false
            repeat(20) {
                val sentence = readSentence(input)
                if (sentence.any { it.startsWith("!trap") }) error("Login ငြင်းပယ်ခံရသည်")
                if (sentence.any { it == "!done" }) loggedIn = true
                if (loggedIn) return@repeat
            }
            if (!loggedIn) error("Login အဖြေမရပါ")
            sendSentence(output, listOf("/system/identity/print"))
            repeat(20) {
                val sentence = readSentence(input)
                val name = sentence.firstOrNull { it.startsWith("=name=") }?.substringAfter("=name=")
                if (name != null) return@use name
                if (sentence.any { it.startsWith("!trap") }) error("Identity ဖတ်မရပါ")
            }
            error("Router အမည် မရရှိပါ")
        }
    }

    private fun sendSentence(out: BufferedOutputStream, words: List<String>) {
        words.forEach { word ->
            val b = word.toByteArray(Charsets.UTF_8)
            writeLength(out, b.size)
            out.write(b)
        }
        out.write(0)
        out.flush()
    }
    private fun writeLength(out: BufferedOutputStream, n: Int) {
        when {
            n < 0x80 -> out.write(n)
            n < 0x4000 -> { out.write((n shr 8) or 0x80); out.write(n) }
            n < 0x200000 -> { out.write((n shr 16) or 0xC0); out.write(n shr 8); out.write(n) }
            n < 0x10000000 -> { out.write((n shr 24) or 0xE0); out.write(n shr 16); out.write(n shr 8); out.write(n) }
            else -> { out.write(0xF0); out.write(n shr 24); out.write(n shr 16); out.write(n shr 8); out.write(n) }
        }
    }
    private fun readLength(input: BufferedInputStream): Int {
        val c = input.read()
        if (c < 0) error("Connection ပိတ်သွားသည်")
        return when {
            c and 0x80 == 0 -> c
            c and 0xC0 == 0x80 -> ((c and 0x3F) shl 8) or readByte(input)
            c and 0xE0 == 0xC0 -> ((c and 0x1F) shl 16) or (readByte(input) shl 8) or readByte(input)
            c and 0xF0 == 0xE0 -> ((c and 0x0F) shl 24) or (readByte(input) shl 16) or (readByte(input) shl 8) or readByte(input)
            else -> { repeat(4) { readByte(input) }; error("အလွန်ရှည်သော API response") }
        }
    }
    private fun readByte(input: BufferedInputStream): Int {
        val v = input.read()
        if (v < 0) error("Response မပြည့်စုံပါ")
        return v
    }
    private fun readSentence(input: BufferedInputStream): List<String> {
        val words = mutableListOf<String>()
        while (true) {
            val length = readLength(input)
            if (length == 0) return words
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) error("Response မပြည့်စုံပါ")
                offset += count
            }
            words += String(bytes, Charsets.UTF_8)
        }
    }
}
