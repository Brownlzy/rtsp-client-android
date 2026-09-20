package com.alexvas.rtsp.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        val container: View = findViewById(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            if ((toolbar.layoutParams as ViewGroup.MarginLayoutParams).topMargin != topInset) {
                toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = topInset
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(container)

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val navController = findNavController(R.id.nav_host_fragment)
        navView.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            toolbar.visibility = if (destination.id == R.id.navigation_logs) View.VISIBLE else View.GONE
            supportActionBar?.title = destination.label
            invalidateOptionsMenu()
        }
    }
}
