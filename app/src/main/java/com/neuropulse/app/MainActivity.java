package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.neuropulse.app.services.UsageMonitorService;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button startBtn, debugBtn, permissionBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        startBtn = findViewById(R.id.startMonitoringBtn);
        debugBtn = findViewById(R.id.debugBtn);
        permissionBtn = findViewById(R.id.permissionsBtn);

        updateStatus();

        startBtn.setOnClickListener(v -> {
            if (hasUsagePermission()) {
                startForegroundService(
                        new Intent(this, UsageMonitorService.class)
                );
                Toast.makeText(this, "Monitoring Started", Toast.LENGTH_SHORT).show();
            } else {
                openPermissionHelper();
            }
        });

        debugBtn.setOnClickListener(v -> {
            if (!hasUsagePermission()) {
                openPermissionHelper();
                return;
            }
            startActivity(new Intent(this, EnhancedDebugActivity.class));
        });

        permissionBtn.setOnClickListener(v -> openPermissionHelper());
    }

    private void updateStatus() {
        if (hasUsagePermission()) {
            statusText.setText("✅ Permission granted\nReady to monitor usage");
        } else {
            statusText.setText("⚠️ Usage Access permission required");
        }
    }

    private boolean hasUsagePermission() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void openPermissionHelper() {
        startActivity(new Intent(this, PermissionHelperActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }
}
