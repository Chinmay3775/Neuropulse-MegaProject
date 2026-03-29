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
                            R.color.text_muted
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
        int colorRes = R.color.accent_blue;
        
        String lowerLabel = label.toLowerCase();
        
        if (lowerLabel.contains("action") && value.contains("break")) {
            colorRes = R.color.accent_red;
        } else if (lowerLabel.contains("action")) {
            colorRes = R.color.accent_green;
        }
        
        if (lowerLabel.contains("addiction") && value.contains("High")) {
            colorRes = R.color.accent_red;
        } else if (lowerLabel.contains("addiction") && value.contains("Risk")) {
            colorRes = R.color.accent_amber;
        }

        if (lowerLabel.contains("classification")) {
            if (value.contains("PRODUCTIVE")) colorRes = R.color.accent_green;
            else if (value.contains("ADDICTIVE")) colorRes = R.color.accent_red;
            else if (value.contains("MODERATE")) colorRes = R.color.accent_amber;
        }
        
        if (lowerLabel.contains("trend")) {
            if (value.contains("↑")) colorRes = R.color.accent_red;
            else if (value.contains("↓")) colorRes = R.color.accent_green;
            else colorRes = R.color.text_muted;
        }

        int finalColor = ContextCompat.getColor(holder.itemView.getContext(), colorRes);
        holder.valueText.setTextColor(finalColor);
        holder.indicatorStrip.setBackgroundColor(finalColor);

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
        final View indicatorStrip;

        EnhancedViewHolder(@NonNull View itemView) {
            super(itemView);
            labelText = itemView.findViewById(R.id.textEnhancedLabel);
            valueText = itemView.findViewById(R.id.textEnhancedValue);
            indicatorStrip = itemView.findViewById(R.id.indicatorStrip);
        }

        void bind(String label, String value) {
            labelText.setText(label);
            valueText.setText(value);
        }
    }
}
