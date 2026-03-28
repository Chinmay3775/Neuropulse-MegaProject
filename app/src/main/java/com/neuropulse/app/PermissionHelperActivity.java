package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class PermissionHelperActivity extends AppCompatActivity {

    private TextView instructionText;
    private Button openSettingsBtn;
    private Button recheckBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ MUST set layout
        setContentView(R.layout.activity_permission_helper);

        // ✅ MUST bind views
        instructionText = findViewById(R.id.instructionText);
        openSettingsBtn = findViewById(R.id.openSettingsBtn);
        recheckBtn = findViewById(R.id.recheckBtn);

        // ✅ Safe to call now
        setupUI();
    }

    private void setupUI() {
        instructionText.setText(
                "⚠️ USAGE ACCESS REQUIRED\n\n" +
                        "Neuropulse needs Usage Access permission to detect which apps you're using.\n\n" +
                        "STEPS:\n" +
                        "1. Click 'Open Settings' below\n" +
                        "2. Find 'Neuropulse' in the list\n" +
                        "3. Toggle the switch ON\n" +
                        "4. Return here and click 'Recheck'"
        );

        openSettingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Enable 'Neuropulse' in the list", Toast.LENGTH_LONG).show();
        });

        recheckBtn.setOnClickListener(v -> {
            if (hasUsageStatsPermission()) {
                Toast.makeText(this, "✅ Permission granted!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "❌ Permission still not granted", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasUsageStatsPermission()) {
            Toast.makeText(this, "✅ Permission already granted!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps =
                (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);

        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName()
        );

        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
