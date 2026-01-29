package com.fzi.acousticscene

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Welcome Screen / Landing Page
 * Wird beim App-Start angezeigt
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge aktivieren für moderne Geräte
        enableEdgeToEdge()

        setContentView(R.layout.activity_welcome)

        // Window Insets für dynamisches Padding (Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
