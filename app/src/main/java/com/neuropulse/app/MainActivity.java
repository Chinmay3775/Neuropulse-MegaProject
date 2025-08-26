package com.neuropulse.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MainActivity extends Activity {

    private Button btnStart, btnStop, btnReqUsage, btnReqNotif;
    private RecyclerView rv;
    private SessionAdapter adapter;
    private TextView tvStatus, tvTotalTime, tvMostUsed, tvAddictionRisk;

    private boolean isMonitoring = false;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        // Hook up UI
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        tvMostUsed = findViewById(R.id.tvMostUsed);
        tvAddictionRisk = findViewById(R.id.tvAddictionRisk);
        rv = findViewById(R.id.rvSessions);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter();
        rv.setAdapter(adapter);

        // Start monitoring
        btnStart.setOnClickListener(v -> {
            startService(new Intent(MainActivity.this, UsageMonitorService.class));
            isMonitoring = true;
            tvStatus.setText("Monitoring: ON");
        });

        // Stop monitoring
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, UsageMonitorService.class));
            isMonitoring = false;
            tvStatus.setText("Monitoring: OFF");
            loadSessions(); // refresh list
            updateDashboard(); // refresh dashboard
        });

        // Optional buttons for requesting permissions
        btnReqUsage = new Button(this);
        btnReqUsage.setText("Grant Usage Access");
        btnReqUsage.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        btnReqNotif = new Button(this);
        btnReqNotif.setText("Grant Notification Access");
        btnReqNotif.setOnClickListener(v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        // Initial load
        loadSessions();
        updateDashboard();
    }

    private void loadSessions() {
        List<SessionEntity> rows = AppDatabase.getInstance(getApplicationContext())
                .sessionDao()
                .getAllSessions();
        adapter.setSessions(rows);
    }

    private void updateDashboard() {
        List<SessionEntity> rows = AppDatabase.getInstance(getApplicationContext())
                .sessionDao()
                .getAllSessions();

        long totalDuration = 0;
        String mostUsedApp = "N/A";
        long maxDuration = 0;

        for (SessionEntity s : rows) {
            totalDuration += s.sessionDurationSec; // <-- corrected

            if (s.sessionDurationSec > maxDuration) {
                maxDuration = s.sessionDurationSec;
                mostUsedApp = s.appPackage; // <-- corrected
            }
        }

        // Format into hours/minutes
        long minutes = totalDuration / 60;
        long hours = minutes / 60;
        minutes = minutes % 60;

        tvTotalTime.setText(hours + "h " + minutes + "m");
        tvMostUsed.setText(mostUsedApp);

        // Simple rule-based addiction risk
        if (totalDuration > 4 * 60 * 60) { // >4h
            tvAddictionRisk.setText("High");
            tvAddictionRisk.setTextColor(getResources().getColor(R.color.red));
        } else if (totalDuration > 2 * 60 * 60) {
            tvAddictionRisk.setText("Moderate");
            tvAddictionRisk.setTextColor(getResources().getColor(R.color.gray));
        } else {
            tvAddictionRisk.setText("Low");
            tvAddictionRisk.setTextColor(getResources().getColor(R.color.teal_700));
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
        updateDashboard();
    }
}
