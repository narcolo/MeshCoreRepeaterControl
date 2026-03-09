package com.example.meshcorerepeatercontrol;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meshcorerepeatercontrol.model.DiscoveredRepeater;
import com.example.meshcorerepeatercontrol.widget.SignalView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DiscoveredRepeaterAdapter extends RecyclerView.Adapter<DiscoveredRepeaterAdapter.ViewHolder> {

    private static final int COLOR_RED = 0xFFF44336;
    private static final int COLOR_ORANGE = 0xFFFF9800;
    private static final int COLOR_GREEN = 0xFF4CAF50;

    private final List<DiscoveredRepeater> repeaters = new ArrayList<>();

    public void addRepeater(DiscoveredRepeater repeater) {
        // Deduplicate by pubKey - update SNR values if already present
        for (int i = 0; i < repeaters.size(); i++) {
            if (Arrays.equals(repeaters.get(i).getPubKey(), repeater.getPubKey())) {
                repeaters.get(i).setSnr(repeater.getSnr());
                repeaters.get(i).setSnrIn(repeater.getSnrIn());
                // Update name if the new one looks like a real name (not hex)
                if (repeater.getName() != null && !repeater.getName().matches("[0-9A-Fa-f]+")) {
                    repeaters.get(i).setName(repeater.getName());
                }
                notifyItemChanged(i);
                return;
            }
        }
        repeaters.add(repeater);
        notifyItemInserted(repeaters.size() - 1);
    }

    public void clear() {
        repeaters.clear();
        notifyDataSetChanged();
    }

    public List<DiscoveredRepeater> getRepeaters() {
        return new ArrayList<>(repeaters);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_discovered_repeater, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DiscoveredRepeater repeater = repeaters.get(position);
        holder.name.setText(repeater.getName());
        holder.type.setText(repeater.getTypeString());

        // Determine signal level and color from snrIn
        double snrIn = repeater.getSnrIn();
        int level;
        int color;
        if (snrIn < -4) {
            level = 1;
            color = COLOR_RED;
        } else if (snrIn <= 4) {
            level = 2;
            color = COLOR_ORANGE;
        } else {
            level = 3;
            color = COLOR_GREEN;
        }

        holder.signalView.setSignal(level, color);
        holder.snrIn.setText(String.format("%.1f dB", snrIn));
        holder.snr.setText(String.format("%.1f dB", repeater.getSnr()));

        // Distance
        double dist = repeater.getDistanceKm();
        if (dist < 0) {
            holder.distance.setText("N/A");
        } else if (dist < 1) {
            holder.distance.setText(String.format("%.0f m", dist * 1000));
        } else {
            holder.distance.setText(String.format("%.2f km", dist));
        }
    }

    @Override
    public int getItemCount() {
        return repeaters.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView type;
        TextView distance;
        SignalView signalView;
        TextView snrIn;
        TextView snr;

        ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.repeater_name);
            type = view.findViewById(R.id.repeater_type);
            distance = view.findViewById(R.id.repeater_distance);
            signalView = view.findViewById(R.id.signal_view);
            snrIn = view.findViewById(R.id.repeater_snr_in);
            snr = view.findViewById(R.id.repeater_snr);
        }
    }
}
