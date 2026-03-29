package com.neuropulse.app.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.neuropulse.app.MainActivity;
import com.neuropulse.app.R;
import com.neuropulse.app.utils.InterventionContentProvider;

/**
 * Full-screen blocking overlay shown during cooldown.
 * Displays breathing exercises, countdown timer, and motivational content.
 * Cannot be dismissed until cooldown expires.
 */
public class BlockingOverlayActivity extends Activity {

    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_APP_NAME = "blocked_app";

    private CountDownTimer countDownTimer;
    private ValueAnimator breathingAnimator;
    private View breathingCircle;
    private long totalDurationMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Prevent screenshots and show above lock screen
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_blocking_overlay);

        totalDurationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 5 * 60 * 1000L);
        String blockedApp = getIntent().getStringExtra(EXTRA_APP_NAME);

        // Bind views
        breathingCircle = findViewById(R.id.breathingCircle);
        TextView breathingTitle = findViewById(R.id.breathingTitle);
        TextView breathingInstructions = findViewById(R.id.breathingInstructions);
        TextView cooldownTimer = findViewById(R.id.cooldownTimer);
        ProgressBar cooldownProgress = findViewById(R.id.cooldownProgress);
        TextView quoteText = findViewById(R.id.quoteText);
        TextView suggestionText = findViewById(R.id.suggestionText);
        MaterialButton btnReturn = findViewById(R.id.btnReturnToApp);

        // Set intervention content
        String[] bundle = InterventionContentProvider.getInterventionBundle();
        breathingTitle.setText(bundle[0]);
        breathingInstructions.setText(bundle[1]);
        quoteText.setText(bundle[2]);
        suggestionText.setText(bundle[3]);

        // Start breathing animation
        startBreathingAnimation();

        // Start countdown
        countDownTimer = new CountDownTimer(totalDurationMs, 1000) {
            @Override
            public void onTick(long millisRemaining) {
                int min = (int) (millisRemaining / 60000);
                int sec = (int) ((millisRemaining % 60000) / 1000);
                cooldownTimer.setText(String.format("%d:%02d", min, sec));

                int progress = (int) ((millisRemaining * 100) / totalDurationMs);
                cooldownProgress.setProgress(progress);
            }

            @Override
            public void onFinish() {
                cooldownTimer.setText("0:00");
                cooldownProgress.setProgress(0);

                // Cooldown complete — allow exit
                btnReturn.setText("Cooldown Complete — Continue");
                btnReturn.setOnClickListener(v -> finish());

                // Auto-close after 5 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing()) finish();
                }, 5000);
            }
        };
        countDownTimer.start();

        // Return button — goes to NeuroPulse main (not back to blocked app)
        btnReturn.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        });

        // Rotate content every 30 seconds
        Handler rotateHandler = new Handler(Looper.getMainLooper());
        Runnable rotateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                String[] newBundle = InterventionContentProvider.getInterventionBundle();
                breathingTitle.setText(newBundle[0]);
                breathingInstructions.setText(newBundle[1]);
                quoteText.setText(newBundle[2]);
                suggestionText.setText(newBundle[3]);
                rotateHandler.postDelayed(this, 30_000);
            }
        };
        rotateHandler.postDelayed(rotateRunnable, 30_000);
    }

    private void startBreathingAnimation() {
        // Breathing animation: scale up (inhale) and scale down (exhale)
        breathingAnimator = ValueAnimator.ofFloat(0.8f, 1.3f);
        breathingAnimator.setDuration(4000); // 4 seconds per breath phase
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        breathingAnimator.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            breathingCircle.setScaleX(scale);
            breathingCircle.setScaleY(scale);
            // Also animate alpha for a "glow" effect
            breathingCircle.setAlpha(0.5f + (scale - 0.8f) * 0.6f);
        });
        breathingAnimator.start();
    }

    @Override
    public void onBackPressed() {
        // Block back press during cooldown — do nothing
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) countDownTimer.cancel();
        if (breathingAnimator != null) breathingAnimator.cancel();
    }
}
