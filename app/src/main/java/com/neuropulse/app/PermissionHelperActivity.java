package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.neuropulse.app.services.NeuropulseAccessibilityService;
import com.neuropulse.app.services.OverlayManager;

/**
 * Guides the user through enabling all required permissions:
 * 1. Usage Access (required for usage stats)
 * 2. Draw Over Other Apps (required for overlay interventions)
 * 3. Accessibility Service (required for real-time app detection)
 */
public class PermissionHelperActivity extends AppCompatActivity {

    private LinearLayout usageSection;
    private LinearLayout overlaySection;
    private LinearLayout accessibilitySection;

    private TextView usageStatus;
    private TextView overlayStatus;
    private TextView accessibilityStatus;

    private Button usageBtn;
    private Button overlayBtn;
    private Button accessibilityBtn;
    private Button recheckBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_helper);

        // Bind existing views
        TextView instructionText = findViewById(R.id.instructionText);
        Button openSettingsBtn = findViewById(R.id.openSettingsBtn);
        recheckBtn = findViewById(R.id.recheckBtn);

        // Update instruction text for all permissions
        instructionText.setText(
                "🛡️ PERMISSIONS REQUIRED\n\n" +
                "NeuroPulse needs 3 permissions to protect you from doomscrolling:\n\n" +
                "1️⃣ Usage Access — detect which apps you use\n" +
                "2️⃣ Draw Over Apps — show alerts on top of apps\n" +
                "3️⃣ Accessibility — real-time app monitoring\n\n" +
                "Tap each button below to grant permissions."
        );

        // Dynamically add permission sections
        LinearLayout container = (LinearLayout) instructionText.getParent();

        // --- Usage Access ---
        usageSection = createPermissionSection("1️⃣ Usage Access", "Detects which apps you're using");
        usageStatus = (TextView) usageSection.getChildAt(1);
        usageBtn = (Button) usageSection.getChildAt(2);
        usageBtn.setText("Open Usage Settings");
        usageBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            Toast.makeText(this, "Enable 'Neuropulse' in the list", Toast.LENGTH_LONG).show();
        });
        container.addView(usageSection, container.indexOfChild(openSettingsBtn));

        // --- Overlay ---
        overlaySection = createPermissionSection("2️⃣ Draw Over Other Apps", "Shows alerts on top of any app");
        overlayStatus = (TextView) overlaySection.getChildAt(1);
        overlayBtn = (Button) overlaySection.getChildAt(2);
        overlayBtn.setText("Open Overlay Settings");
        overlayBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });
        container.addView(overlaySection, container.indexOfChild(openSettingsBtn));

        // --- Accessibility ---
        accessibilitySection = createPermissionSection("3️⃣ Accessibility Service", "Real-time foreground app detection");
        accessibilityStatus = (TextView) accessibilitySection.getChildAt(1);
        accessibilityBtn = (Button) accessibilitySection.getChildAt(2);
        accessibilityBtn.setText("Open Accessibility Settings");
        accessibilityBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Find 'NeuroPulse' and enable it", Toast.LENGTH_LONG).show();
        });
        container.addView(accessibilitySection, container.indexOfChild(openSettingsBtn));

        // Hide original "Open Settings" button (replaced by individual buttons)
        openSettingsBtn.setVisibility(View.GONE);

        // Recheck button
        recheckBtn.setOnClickListener(v -> {
            updatePermissionStatuses();
            if (allPermissionsGranted()) {
                Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "❌ Some permissions still needed", Toast.LENGTH_LONG).show();
            }
        });

        updatePermissionStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatuses();
        if (allPermissionsGranted()) {
            Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void updatePermissionStatuses() {
        boolean usage = hasUsageStatsPermission();
        boolean overlay = OverlayManager.canDrawOverlays(this);
        boolean a11y = NeuropulseAccessibilityService.isAccessibilityEnabled(this);

        updateStatus(usageStatus, usageBtn, usage);
        updateStatus(overlayStatus, overlayBtn, overlay);
        updateStatus(accessibilityStatus, accessibilityBtn, a11y);
    }

    private void updateStatus(TextView statusView, Button btn, boolean granted) {
        if (granted) {
            statusView.setText("✅ Granted");
            statusView.setTextColor(getColor(R.color.accent_green));
            btn.setEnabled(false);
            btn.setAlpha(0.5f);
        } else {
            statusView.setText("❌ Not granted");
            statusView.setTextColor(getColor(R.color.accent_red));
            btn.setEnabled(true);
            btn.setAlpha(1.0f);
        }
    }

    private boolean allPermissionsGranted() {
        return hasUsageStatsPermission() &&
                OverlayManager.canDrawOverlays(this) &&
                NeuropulseAccessibilityService.isAccessibilityEnabled(this);
    }

    private LinearLayout createPermissionSection(String title, String description) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, 24, 0, 16);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(16f);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        section.addView(titleView);

        TextView statusView = new TextView(this);
        statusView.setText("Checking...");
        statusView.setTextColor(getColor(R.color.text_muted));
        statusView.setTextSize(13f);
        statusView.setPadding(0, 4, 0, 8);
        section.addView(statusView);

        Button actionBtn = new Button(this);
        actionBtn.setTextColor(getColor(R.color.text_primary));
        actionBtn.setTextSize(14f);
        actionBtn.setAllCaps(false);
        actionBtn.setBackgroundColor(getColor(R.color.bg_card));
        actionBtn.setPadding(32, 16, 32, 16);
        section.addView(actionBtn);

        return section;
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
