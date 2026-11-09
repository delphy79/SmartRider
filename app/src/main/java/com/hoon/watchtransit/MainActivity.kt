package com.hoon.watchtransit

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.hoon.watchtransit.data.TransitConfigManager
import com.hoon.watchtransit.ui.TransitConfigScreen
import com.hoon.watchtransit.ui.theme.SmartRiderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var configManager: TransitConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = TransitConfigManager(applicationContext)

        setContent {
            SmartRiderTheme {
                val savedTargets by configManager.savedTargetsFlow.collectAsState(initial = emptyList())

                TransitConfigScreen(
                    savedTargets = savedTargets,
                    configManager = configManager,
                    onSaveTargets = { updatedList ->
                        lifecycleScope.launch {
                            val result = configManager.saveAndSyncTargets(updatedList)
                            result.onSuccess {
                                Toast.makeText(this@MainActivity, "워치 동기화 요청 완료", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "로컬 저장 완료 (워치 미연결: ${e.message})", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}
