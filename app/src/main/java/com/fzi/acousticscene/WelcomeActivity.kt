package com.fzi.acousticscene

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Welcome Screen / Landing Page
 * Wird beim App-Start angezeigt
 */
class WelcomeActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        
        val startAppButton: MaterialButton = findViewById(R.id.startAppButton)
        val viewHistoryButton: MaterialButton = findViewById(R.id.viewHistoryButton)
        
        // Navigation zu MainActivity
        startAppButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish() // WelcomeActivity nicht im Back Stack behalten
        }
        
        // Navigation zu HistoryActivity
        viewHistoryButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }
}
