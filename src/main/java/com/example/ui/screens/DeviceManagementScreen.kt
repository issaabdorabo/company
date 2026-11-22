package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.MultiDeviceControlSection
import com.example.ui.components.SupabaseAccountCard
import com.example.ui.viewmodel.InventoryViewModel

/** Device pairing and cloud account management. */
@Composable
fun DeviceManagementScreen(viewModel: InventoryViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("إدارة الأجهزة والاتصال السحابي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "إدارة حساب الاتصال عبر الإنترنت وربط الأجهزة الفرعية وتحديد صلاحياتها.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SupabaseAccountCard()
        Spacer(Modifier.height(16.dp))
        MultiDeviceControlSection(viewModel = viewModel)
    }
}
