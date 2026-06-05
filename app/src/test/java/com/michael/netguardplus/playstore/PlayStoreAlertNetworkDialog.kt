package com.michael.netguardplus.playstore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michael.netguardplus.domain.model.AlertNetworkType

@Composable
fun PlayStoreAlertNetworkDialog() {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.55f)),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      tonalElevation = 6.dp,
      modifier = Modifier
        .fillMaxWidth(0.88f)
        .padding(horizontal = 24.dp),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Choose Network Type",
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Text(
          text = "Which connection should this alert watch?",
          style = MaterialTheme.typography.bodyMedium,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = true,
            onClick = {},
            label = { Text("Mobile Data") },
            leadingIcon = {
              Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier.weight(1f),
          )
          FilterChip(
            selected = false,
            onClick = {},
            label = { Text("Wi-Fi") },
            modifier = Modifier.weight(1f),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = {}) { Text("Cancel") }
          TextButton(onClick = {}) { Text("Enable Alert") }
        }
      }
    }
  }
}
