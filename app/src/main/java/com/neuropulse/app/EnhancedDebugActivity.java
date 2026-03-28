package com.neuropulse.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neuropulse.app.adapters.EnhancedDebugAdapter;
import com.neuropulse.app.features.EnhancedFeatureExtractor;
import com.neuropulse.app.features.RealTimeAppDetector;
import com.neuropulse.app.ml.AddictionPredictor;
import com.neuropulse.app.models.SessionFeatures;
import com.neuropulse.app.models.EnhancedDebugInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EnhancedDebugActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private EnhancedDebugAdapter adapter;

    private EnhancedFeatureExtractor featureExtractor;
    private RealTimeAppDetector appDetector;
    private AddictionPredictor predictor;

    private ExecutorService executor;
    private Handler mainHandler;

    private long sessionStartTime = System.currentTimeMillis();
    private String currentPkg = null;

    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_enhanced_debug);

        if (!hasUsagePermission()) {
            Toast.makeText(this, "Usage Access permission required", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        recyclerView = findViewById(R.id.recyclerEnhancedDebug);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new EnhancedDebugAdapter();
        recyclerView.setAdapter(adapter);

        featureExtractor = new EnhancedFeatureExtractor(this);
        appDetector = new RealTimeAppDetector(this);
        predictor = new AddictionPredictor(this);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        running = true;
        startLiveDebug();
    }

    private void startLiveDebug() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) return;

                executor.execute(() -> updateDebugData());
                mainHandler.postDelayed(this, 3000);
            }
        }, 1000);
    }

    private void updateDebugData() {

        RealTimeAppDetector.CurrentAppInfo app =
                appDetector.getCurrentAppWithRisk();

        if (app == null || "unknown".equals(app.packageName)) return;

        long now = System.currentTimeMillis();

        if (!app.packageName.equals(currentPkg)) {
            currentPkg = app.packageName;
            sessionStartTime = now;
        }

        SessionFeatures features = featureExtractor.extract(
                app.category,
                sessionStartTime,
                now
        );

        AddictionPredictor.PredictionResult result =
                predictor.predict(features);

        EnhancedDebugInfo debugInfo =
                EnhancedDebugInfo.from(features, app, result);

        mainHandler.post(() -> adapter.updateEnhancedInfo(debugInfo));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (executor != null) executor.shutdownNow();
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
    }
}
