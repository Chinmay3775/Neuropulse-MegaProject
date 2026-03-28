package com.neuropulse.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import com.neuropulse.app.R;

public class AlertActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show above lock screen
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        String appName = getIntent().getStringExtra("app_name");

        TextView tv = new TextView(this);
        tv.setText("⚠ Take a break from " + appName);
        tv.setTextSize(22f);
        tv.setPadding(40, 60, 40, 60);

        setContentView(tv);
    }
}
