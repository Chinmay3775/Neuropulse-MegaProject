package com.neuropulse.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.neuropulse.app.R;
import com.neuropulse.app.models.EnhancedDebugInfo;

public class EnhancedDebugAdapter
        extends RecyclerView.Adapter<EnhancedDebugAdapter.EnhancedViewHolder> {

    private EnhancedDebugInfo debugInfo;

    // ================= PUBLIC API =================

    public void updateEnhancedInfo(EnhancedDebugInfo info) {
        this.debugInfo = info;
        notifyDataSetChanged();
    }

    // ================= ADAPTER CORE =================

    @NonNull
    @Override
    public EnhancedViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {

        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_enhanced_debug, parent, false);
        return new EnhancedViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull EnhancedViewHolder holder, int position) {

        // -------- SAFETY --------
        if (debugInfo == null ||
                debugInfo.featureLabels == null ||
                debugInfo.featureValues == null ||
                position >= debugInfo.featureLabels.length ||
                position >= debugInfo.featureValues.length) {

            holder.bind("Loading...", "...");
            holder.valueText.setTextColor(
                    ContextCompat.getColor(
                            holder.itemView.getContext(),
                            android.R.color.darker_gray
                    )
            );
            return;
        }

        String label = debugInfo.featureLabels[position];
        String value = debugInfo.featureValues[position];

        if (label == null || value == null) {
            holder.bind("N/A", "N/A");
            return;
        }

        holder.bind(label, value);

        // -------- COLOR LOGIC (TEXT-BASED) --------
        int colorRes = android.R.color.holo_blue_dark;

        // Dopamine risk coloring
        if (label.contains("Dopamine Risk")) {
            try {
                float risk = Float.parseFloat(value);
                if (risk >= 0.7f) colorRes = android.R.color.holo_red_dark;
                else if (risk >= 0.4f) colorRes = android.R.color.holo_orange_dark;
                else colorRes = android.R.color.holo_green_dark;
            } catch (Exception ignored) {}
        }

        // Risk level coloring
        else if (label.contains("Risk Level")) {
            if (value.equalsIgnoreCase("HIGH"))
                colorRes = android.R.color.holo_red_dark;
            else if (value.equalsIgnoreCase("MEDIUM"))
                colorRes = android.R.color.holo_orange_dark;
            else
                colorRes = android.R.color.holo_green_dark;
        }

        // Addiction state coloring
        else if (label.contains("Addiction")) {
            if (value.contains("High"))
                colorRes = android.R.color.holo_red_dark;
            else if (value.contains("Risk"))
                colorRes = android.R.color.holo_orange_dark;
            else
                colorRes = android.R.color.holo_green_dark;
        }

        // Binge flag
        else if (label.contains("Binge") && value.equalsIgnoreCase("YES")) {
            colorRes = android.R.color.holo_red_dark;
        }

        holder.valueText.setTextColor(
                ContextCompat.getColor(holder.itemView.getContext(), colorRes)
        );

        // Accessibility
        holder.itemView.setContentDescription(label + ": " + value);
    }

    @Override
    public int getItemCount() {
        return (debugInfo != null && debugInfo.featureLabels != null)
                ? debugInfo.featureLabels.length
                : 0;
    }

    // ================= VIEW HOLDER =================

    static class EnhancedViewHolder extends RecyclerView.ViewHolder {

        final TextView labelText;
        final TextView valueText;

        EnhancedViewHolder(@NonNull View itemView) {
            super(itemView);
            labelText = itemView.findViewById(R.id.textEnhancedLabel);
            valueText = itemView.findViewById(R.id.textEnhancedValue);
        }

        void bind(String label, String value) {
            labelText.setText(label);
            valueText.setText(value);
        }
    }
}
