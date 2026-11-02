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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class Customer(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val startedAt: Long = System.currentTimeMillis(),
    val usedGb: Double = 0.0
) {
    val expired: Boolean
        get() = System.currentTimeMillis() >= startedAt + 30L * 24 * 60 * 60 * 1000 ||
            usedGb >= 800.0
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ManagerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerScreen() {
    var customers by remember { mutableStateOf(listOf<Customer>()) }
    var username by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PPPoE စီမံခန့်ခွဲမှု") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Package: 800GB / ရက် ၃၀", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("စုစုပေါင်း Customer: ${customers.size}")
            Text("သက်တမ်းကုန် / Quota ပြည့်: ${customers.count { it.expired }}")
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(customers, key = { it.id }) { customer ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(customer.username, style = MaterialTheme.typography.titleMedium)
                            Text("အသုံးပြုပြီး: ${customer.usedGb} GB / 800 GB")
                            Text("စတင်ရက်: ${dateFormat.format(Date(customer.startedAt))}")
                            Text("အခြေအနေ: ${if (customer.expired) "သက်တမ်းကုန်/Quota ပြည့်" else "အသုံးပြုနိုင်"}")
                            Button(onClick = {
                                customers = customers.map {
                                    if (it.id == customer.id) it.copy(
                                        startedAt = System.currentTimeMillis(),
                                        usedGb = 0.0
                                    ) else it
                                }
                            }) { Text("RENEW") }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Customer အသစ်ထည့်ရန်") },
            text = {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("PPPoE Username") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (username.isNotBlank()) {
                        customers = customers + Customer(username = username.trim())
                        username = ""
                        showAdd = false
                    }
                }) { Text("ထည့်မည်") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("မလုပ်တော့ပါ") }
            }
        )
    }
}
